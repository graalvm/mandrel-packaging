//usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.9.0.202009080501-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.pgm:5.9.0.202009080501-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.9.0.202009080501-r
//DEPS info.picocli:picocli:4.5.0
//DEPS org.kohsuke:github-api:1.116

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.console.ConsoleCredentialsProvider;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.github.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "mandrel-release", mixinStandardHelpOptions = true,
        description = "Scipt automating part of the Mandrel release process")
class MandrelRelease implements Callable<Integer> {

    public static final String GITFORGE_URL = "git@github.com:";
    public static final String REPOSITORY_NAME = "graalvm/mandrel";

    @CommandLine.Parameters(index = "0", description = "The kind of steps to execute, must be one of \"prepare\" or \"release\"")
    private String phase;

    @CommandLine.Option(names = {"-m", "--mandrel-repo"}, description = "The path to the mandrel repository", defaultValue = "./")
    private String mandrelRepo;

    @CommandLine.Option(names = {"-s", "--suffix"}, description = "The release suffix, e.g, Final, Alpha2, Beta1, etc. (default: \"${DEFAULT-VALUE}\")", defaultValue = "Final")
    private String suffix;

    @CommandLine.Option(names = {"-f", "--fork-name"}, description = "The repository name of the github fork to push the changes to (default: \"${DEFAULT-VALUE}\")", defaultValue = "zakkak/mandrel")
    private String forkName;

    @CommandLine.Option(names = {"-S", "--sign-commits"}, description = "Sign commits")
    private boolean signCommits;

    @CommandLine.Option(names = {"-D", "--dry-run"}, description = "Perform a dry run (no remote pushes and PRs)")
    private boolean dryRun;

    private static final String REMOTE_NAME = "mandrel-release-fork";
    private MandrelVersion version = null;
    private String releaseBranch = null;
    private String baseBranch = null;
    private MandrelVersion newVersion = null;
    private String developBranch = null;

    public static void main(String... args) {
        int exitCode = new CommandLine(new MandrelRelease()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        if (!suffix.equals("Final") && !Pattern.compile("^(Alpha|Beta)\\d*$").matcher(suffix).find()) {
            error("Invalid version suffix : " + suffix);
        }

        if (!Path.of(mandrelRepo).toFile().exists()) {
            error("Path " + mandrelRepo + " does not exist");
        }

        version = getCurrentVersion();
        info("Current version is " + version);
        version.suffix = suffix; // TODO: if Alpha/Beta autobump suffix number?

        if (suffix.equals("Final")) {
            newVersion = version.getNewVersion();
            info("New version will be " + newVersion.majorMinorMicroPico());
            developBranch = "develop/mandrel-" + newVersion;
        }
        releaseBranch = "release/mandrel-" + version;
        baseBranch = "mandrel/" + version.majorMinor();

        if (!phase.equals("prepare") && !phase.equals("release")) {
            throw new IllegalArgumentException(phase + " is not a valid phase. Please use \"prepare\" or \"release\".");
        }

        if (phase.equals("release")) {
            createGHRelease();
        }
        if (!suffix.equals("Final")) {
            return 0;
        }
        checkAndPrepareRepository();
        if (phase.equals("release")) {
            updateSuites(this::updateReleaseAndBumpVersionInSuite);
        } else {
            updateSuites(this::markSuiteAsRelease);
        }
        final String authorEmail = commitAndPushChanges();
        openPR(authorEmail);
        return 0;
    }

    private void checkAndPrepareRepository() {
        try (Git git = Git.open(new File(mandrelRepo))) {
            if (!git.getRepository().getBranch().equals(baseBranch)) {
                error("Please checkout " + baseBranch + " and try again!");
            }
            final Status status = git.status().call();
            if (status.hasUncommittedChanges() || !status.getChanged().isEmpty()) {
                error("Status of branch " + baseBranch + " is not clean, aborting!");
            }

            maybeCreateRemote(git);
            final String newBranch = phase.equals("release") ? developBranch : releaseBranch;
            try {
                git.checkout().setCreateBranch(true).setName(newBranch).setStartPoint(baseBranch).call();
                info("Created new branch " + newBranch + " based on " + baseBranch);
            } catch (RefAlreadyExistsException e) {
                warn(e.getMessage());
                gitCheckout(git, newBranch);
                git.reset().setRef(baseBranch).setMode(ResetCommand.ResetType.HARD).call();
                warn(newBranch + " reset (hard) to " + baseBranch);
            }
        } catch (IOException | GitAPIException | URISyntaxException e) {
            e.printStackTrace();
            error(e.getMessage());
        }
    }

    private void maybeCreateRemote(Git git) throws URISyntaxException, GitAPIException {
        final String forkURL = GITFORGE_URL + forkName;
        final URIish forkURI = new URIish(forkURL);
        RemoteConfig remote = getRemoteConfig(git);
        if (remote != null && !remote.getURIs().stream().anyMatch(u -> forkURI.equals(u))) {
            error("Remote " + REMOTE_NAME + " already exists and does not point to " + forkURL +
                    "\nPlease remove with `git remote remove " + REMOTE_NAME + "` and try again.");
        }
        git.remoteAdd().setName(REMOTE_NAME).setUri(forkURI).call();
        remote = getRemoteConfig(git);
        if (remote != null && remote.getURIs().stream().anyMatch(u -> forkURI.equals(u))) {
            info("Git remote " + REMOTE_NAME + " points to " + forkURL);
        } else {
            error("Failed to add remote " + REMOTE_NAME + " pointing to " + forkURL);
        }
    }

    private RemoteConfig getRemoteConfig(Git git) throws GitAPIException {
        final List<RemoteConfig> remotes = git.remoteList().call();
        RemoteConfig remote = null;
        for (int i = 0; i < remotes.size(); i++) {
            if (remotes.get(i).getName().equals(REMOTE_NAME)) {
                remote = remotes.get(i);
                break;
            }
        }
        return remote;
    }

    /**
     * Visit all suite.py files and change the "release" and "version" fields
     *
     */
    private void updateSuites(Consumer<File> updater) {
        File cwd = new File(mandrelRepo);
        Stream.of(Objects.requireNonNull(cwd.listFiles()))
                .filter(File::isDirectory)
                .flatMap(path -> Stream.of(Objects.requireNonNull(path.listFiles())).filter(child -> child.getName().startsWith("mx.")))
                .map(path -> new File(path, "suite.py"))
                .filter(File::exists)
                .forEach(updater);
        info("Updated suites");
    }

    /**
     * Visit {@code suite} file and change the "release" value to {@code asRelease}
     *  @param suite
     *
     */
    private void markSuiteAsRelease(File suite) {
        try {
            info("Marking " + suite.getPath());
            List<String> lines = Files.readAllLines(suite.toPath());
            final String pattern = "(.*\"release\" : )False(.*)";
            final Pattern releasePattern = Pattern.compile(pattern);
            for (int i = 0; i < lines.size(); i++) {
                final Matcher releaseMatcher = releasePattern.matcher(lines.get(i));
                if (releaseMatcher.find()) {
                    String newLine = releaseMatcher.group(1) + "True" + releaseMatcher.group(2);
                    lines.set(i, newLine);
                    break;
                }
            }
            Files.write(suite.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            error(e.getMessage());
        }
    }

    private void updateReleaseAndBumpVersionInSuite(File suite) {
        try {
            info("Updating " + suite.getPath());
            List<String> lines = Files.readAllLines(suite.toPath());
            final Pattern releasePattern = Pattern.compile("(.*\"release\" : )True(.*)");
            final Pattern versionPattern = Pattern.compile("(.*\"version\" : \")" + version.majorMinorMicroPico() + "(\".*)");
            for (int i = 0; i < lines.size(); i++) {
                final Matcher releaseMatcher = releasePattern.matcher(lines.get(i));
                if (releaseMatcher.find()) {
                    String newLine = releaseMatcher.group(1) + "False" + releaseMatcher.group(2);
                    lines.set(i, newLine);
                }
                final Matcher versionMatcher = versionPattern.matcher(lines.get(i));
                if (versionMatcher.find()) {
                    String newLine = versionMatcher.group(1) + newVersion.majorMinorMicroPico() + versionMatcher.group(2);
                    lines.set(i, newLine);
                }
            }
            Files.write(suite.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            error(e.getMessage());
        }
    }

    private String commitAndPushChanges() {
        try (Git git = Git.open(new File(mandrelRepo))) {
            ConsoleCredentialsProvider.install(); // Needed for gpg signing
            final String message;
            if (phase.equals("release")) {
                message = "Unmark suites and bump version to " + newVersion.majorMinorMicroPico();
            } else {
                message = "Mark suites for " + version + " release";
            }
            final RevCommit commit = git.commit().setAll(true).setMessage(message).setSign(signCommits).call();
            final String author = commit.getAuthorIdent().getEmailAddress();
            info("Changes commited");
            git.push().setForce(true).setRemote(REMOTE_NAME).setDryRun(dryRun).call();
            if (dryRun) {
                warn("Changes not pushed to remote due to --dry-run being present");
            } else {
                info("Changes pushed to remote " + REMOTE_NAME);
            }
            gitCheckout(git, baseBranch);
            return author;
        } catch (IOException | GitAPIException e) {
            e.printStackTrace();
            error(e.getMessage());
        }
        return null;
    }

    private void gitCheckout(Git git, String baseBranch) throws GitAPIException {
        git.checkout().setName(baseBranch).call();
        info("Checked out " + baseBranch);
    }

    private void openPR(String authorEmail) {
        assert suffix.equals("Final");
        GitHub github = connectToGitHub();
        try {
            final GHRepository repository = github.getRepository(REPOSITORY_NAME);
            if (dryRun) {
                warn("Pull request creation skipped due to --dry-run being present");
                return;
            }

            final String title;
            final String body;
            String head = "";
            if (!forkName.equals(REPOSITORY_NAME)) {
                head = forkName.split("/")[0] + ":";
            }
            if (phase.equals("release")) {
                title = "Unmark suites and bump version to " + newVersion.majorMinorMicroPico();
                head += developBranch;
                body = "This PR was automatically generated by `mandrel-release.java` of graalvm/mandrel-packaging!";
            } else {
                title = "Mark suites for " + version + " release";
                head += releaseBranch;
                body = "This PR was automatically generated by `mandrel-release.java` of graalvm/mandrel-packaging!\n\n" +
                        "Please tag branch `" + baseBranch + "` after merging, and push the tag.\n" +
                        "```\n" +
                        "git checkout " + baseBranch + "\n" +
                        "git pull upstream " + baseBranch + "\n" +
                        "git tag -a mandrel-" + version + " -m \"mandrel-" + version + "\" -s\n" +
                        "git push upstream mandrel-" + version + "\n" +
                        "```\n" +
                        "where `upstream` is the git remote showing to https://github.com/graalvm/mandrel\n\n" +
                        "Make sure to create the same tag in the mandrel-packaging repository!";
            }

            final GHPullRequest pullRequest = repository.createPullRequest(title, head, baseBranch, body, true, false);

            final GHUser galderz = github.getUser("galderz");
            final GHUser jerboaa = github.getUser("jerboaa");
            final GHUser karm = github.getUser("Karm");
            final GHUser zakkak = github.getUser("zakkak");

            ArrayList<GHUser> reviewers = new ArrayList<>();
            if (!authorEmail.contains("galder")) {
                reviewers.add(galderz);
            } else {
                pullRequest.addAssignees(Collections.singletonList(galderz));
            }
            if (!authorEmail.contains("sgehwolf") && !authorEmail.contains("jerboaa")) {
                reviewers.add(jerboaa);
            } else {
                pullRequest.addAssignees(Collections.singletonList(jerboaa));
            }
            if (!authorEmail.contains("karm")) {
                reviewers.add(karm);
            } else {
                pullRequest.addAssignees(Collections.singletonList(karm));
            }
            if (!authorEmail.contains("zakkak")) {
                reviewers.add(zakkak);
            } else {
                pullRequest.addAssignees(Collections.singletonList(zakkak));
            }
            pullRequest.requestReviewers(reviewers);

            info("Pull request " + pullRequest.getHtmlUrl() + " created");
        } catch (IOException e) {
            e.printStackTrace();
            error(e.getMessage());
        }
    }

    /**
     * Returns current version of substratevm
     *
     * @return
     */
    private MandrelVersion getCurrentVersion() {
        final Path substrateSuite = Path.of(mandrelRepo, "substratevm", "mx.substratevm", "suite.py");
        try {
            final List<String> lines = Files.readAllLines(substrateSuite);
            final Pattern versionPattern = Pattern.compile("\"version\" : \"([\\d.]+)\"");
            for (String line : lines) {
                final Matcher versionMatcher = versionPattern.matcher(line);
                if (versionMatcher.find()) {
                    final String version = versionMatcher.group(1);
                    return new MandrelVersion(version);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            error(e.getMessage());
        }
        return null;
    }

    private void createGHRelease() {
        GitHub github = connectToGitHub();
        try {
            final GHRepository repository = github.getRepository(REPOSITORY_NAME);
            final PagedIterable<GHMilestone> ghMilestones = repository.listMilestones(GHIssueState.OPEN);
            final String finalVersion = version.majorMinorMicroPico() + "-Final";
            final GHMilestone milestone = ghMilestones.toList().stream().filter(m -> m.getTitle().equals(finalVersion)).findAny().orElse(null);
            final List<GHTag> tags = repository.listTags().toList();
            String changelog = createChangelog(repository, milestone, tags);
            if (dryRun) {
                warn("Skipping release due to --dry-run");
                info("Release body would look like");
                System.out.println(releaseMainBody(version, changelog));
                return;
            }
            // Ensure that the tag exists
            final String tag = "mandrel-" + version;
            if (tags.stream().noneMatch(x -> x.getName().equals(tag))) {
                error("Please create tag " + tag + " and try again");
            }
            final GHRelease ghRelease = repository.createRelease(tag)
                    .name("Mandrel " + version)
                    .prerelease(!suffix.equals("Final"))
                    .body(releaseMainBody(version, changelog))
                    .draft(true)
                    .create();
            uploadAssets(version.toString(), ghRelease);
            info("Created new draft release: " + ghRelease.getHtmlUrl());
            info("Please review and publish!");
            manageMilestones(repository, ghMilestones, milestone);
        } catch (IOException e) {
            e.printStackTrace();
            error(e.getMessage());
        }
    }

    private void uploadAssets(String fullVersion, GHRelease ghRelease) throws IOException {
        final File[] assets = {
                new File("mandrel-java11-linux-amd64-" + fullVersion + ".tar.gz"),
                new File("mandrel-java11-linux-amd64-" + fullVersion + ".tar.gz.sha1"),
                new File("mandrel-java11-linux-amd64-" + fullVersion + ".tar.gz.sha256"),
                new File("mandrel-java11-windows-amd64-" + fullVersion + ".zip"),
                new File("mandrel-java11-windows-amd64-" + fullVersion + ".zip.sha1"),
                new File("mandrel-java11-windows-amd64-" + fullVersion + ".zip.sha256")};

        for (File f : assets) {
            if (!f.exists()) {
                warn("Archive \"" + f.getName() + "\" was not found. Skipping asset upload.");
                warn("Please upload assets manually.");
                return;
            }
        }

        for (File f : assets) {
            info("Uploading " + f.getName());
            if (f.getName().endsWith("tar.gz")) {
                ghRelease.uploadAsset(f, "application/gzip");
            } else if (f.getName().endsWith("zip")) {
                ghRelease.uploadAsset(f, "application/zip");
            } else {
                ghRelease.uploadAsset(f, "text/plain");
            }
            info("Uploaded " + f.getName());
        }
    }

    private String releaseMainBody(MandrelVersion version, String changelog) {
        return "# Mandrel\n" +
                "\n" +
                "Mandrel " + version + " is a downstream distribution of the [GraalVM community edition " + version.majorMinorMicro() + "](https://github.com/graalvm/graalvm-ce-builds/releases/tag/vm-" + version.majorMinorMicro() + ").\n" +
                "Mandrel's main goal is to provide a `native-image` release specifically to support [Quarkus](https://quarkus.io).\n" +
                "The aim is to align the `native-image` capabilities from GraalVM with OpenJDK and Red Hat Enterprise Linux libraries to improve maintainability for native Quarkus applications.\n" +
                "\n" +
                "## How Does Mandrel Differ From Graal\n" +
                "\n" +
                "Mandrel releases are built from a code base derived from the upstream GraalVM code base, with only minor changes but some significant exclusions.\n" +
                "They support the same native image capability as GraalVM with no significant changes to functionality.\n" +
                "They do not include support for Polyglot programming via the Truffle interpreter and compiler framework.\n" +
                "In consequence, it is not possible to extend Mandrel by downloading languages from the Truffle language catalogue.\n" +
                "\n" +
                "Mandrel is also built slightly differently to GraalVM, using the standard OpenJDK project release of jdk11u.\n" +
                "This means it does not profit from a few small enhancements that Oracle have added to the version of OpenJDK used to build their own GraalVM downloads.\n" +
                "Most of these enhancements are to the JVMCI module that allows the Graal compiler to be run inside OpenJDK.\n" +
                "The others are small cosmetic changes to behaviour.\n" +
                "These enhancements may in some cases cause minor differences in the progress of native image generation.\n" +
                "They should not cause the resulting images themselves to execute in a noticeably different manner.\n" +
                "\n" +
                "### Prerequisites\n" +
                "\n" +
                "Mandrel's `native-image` depends on the following packages:\n" +
                "* glibc-devel\n" +
                "* zlib-devel\n" +
                "* freetype-devel\n" +
                "* gcc\n" +
                "\n" +
                "On Fedora/CentOS/RHEL they can be installed with:\n" +
                "```bash\n" +
                "dnf install glibc-devel zlib-devel gcc freetype-devel libstdc++-static\n" +
                "```\n" +
                "\n" +
                "Note the package might be called `glibc-static` instead of `libstdc++-static`.\n" +
                "\n" +
                "On Ubuntu-like systems with:\n" +
                "```bash\n" +
                "apt install g++ zlib1g-dev libfreetype6-dev\n" +
                "```\n" +
                "\n" +
                "## Quick start\n" +
                "\n" +
                "```\n" +
                "$ tar -xf mandrel-java11-linux-amd64-" + version + ".tar.gz\n" +
                "$ export JAVA_HOME=\"$( pwd )/mandrel-java11-" + version + "\"\n" +
                "$ export GRAALVM_HOME=\"${JAVA_HOME}\"\n" +
                "$ export PATH=\"${JAVA_HOME}/bin:${PATH}\"\n" +
                "$ curl -O -J https://code.quarkus.io/d?e=io.quarkus:quarkus-resteasy\n" +
                "$ unzip code-with-quarkus.zip\n" +
                "$ cd code-with-quarkus/\n" +
                "$ ./mvnw package -Pnative\n" +
                "$ ./target/code-with-quarkus-1.0.0-SNAPSHOT-runner\n" +
                "```\n" +
                "\n" +
                "### Quarkus builder image\n" +
                "\n" +
                "The Quarkus builder image for this release is still being prepared, please try again later.\n" +
                "<!--\n" +
                "Mandrel Quarkus builder image can be used to build a Quarkus native Linux executable right away without any GRAALVM_HOME setup.\n" +
                "\n" +
                "```bash\n" +
                "curl -O -J  https://code.quarkus.io/d?e=io.quarkus:quarkus-resteasy\n" +
                "unzip code-with-quarkus.zip\n" +
                "cd code-with-quarkus\n" +
                "        ./mvnw package -Pnative -Dquarkus.native.container-build=true -Dquarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel:" + version + "-java11\n" +
                "        ./target/code-with-quarkus-1.0.0-SNAPSHOT-runner\n" +
                "```\n" +
                "\n" +
                "One can use the builder image on Windows with Docker Desktop (mind `Resources-> File sharing` settings so as Quarkus project directory is mountable).\n" +
                "\n" +
                "```batchfile\n" +
                "powershell -c \"Invoke-WebRequest -OutFile quarkus.zip -Uri https://code.quarkus.io/d?e=io.quarkus:quarkus-resteasy\"\n" +
                "powershell -c \"Expand-Archive -Path quarkus.zip -DestinationPath . -Force\n" +
                "cd code-with-quarkus\n" +
                "mvnw package -Pnative -Dquarkus.native.container-build=true -Dquarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel:" + version + "-java11\n" +
                "docker build -f src/main/docker/Dockerfile.native -t my-quarkus-mandrel-app .\n" +
                "        docker run -i --rm -p 8080:8080 my-quarkus-mandrel-app\n" +
                "```\n" +
                "-->\n" +
                changelog +
                "\n---\n" +
                "Mandrel " + version + "\n" +
                "OpenJDK used: " + System.getProperty("java.runtime.version") + "\n";
    }

    private String createChangelog(GHRepository repository, GHMilestone milestone, List<GHTag> tags) throws IOException {
        if (milestone == null) {
            error("No milestone titled " + version.majorMinorMicroPico() + "-Final! Can't produce changelog without it!");
        }
        if (suffix.equals("Final") && milestone.getOpenIssues() != 0) {
            error("There are still open issues in milestone " + milestone.getTitle() + ". Please take care of them and try again.");
        }
        info("Getting merged PRs for " + milestone.getTitle() + " (" + milestone.getNumber() + ")");
        final Stream<GHPullRequest> mergedPRsInMilestone = repository.getPullRequests(GHIssueState.CLOSED).stream()
                .filter(pr -> includeInChangelog(pr, milestone));
        final Map<Integer, List<GHPullRequest>> collect = mergedPRsInMilestone.collect(Collectors.groupingBy(this::getGroup));
        StringBuilder changelogBuilder = new StringBuilder("\n### Changelog\n\n");
        final String latestReleasedTag = version.getLatestReleasedTag(tags);
        final List<GHPullRequest> noteworthyPRs = collect.get(0);
        if (noteworthyPRs != null && noteworthyPRs.size() != 0) {
            noteworthyPRs.forEach(pr ->
                    changelogBuilder.append(" * #").append(pr.getNumber()).append(" - ").append(pr.getTitle()).append("\n"));
        }
        final List<GHPullRequest> backportPRs = collect.get(1);
        if (backportPRs != null && backportPRs.size() != 0) {
            changelogBuilder.append("\n#### Backports\n\n");
            backportPRs.forEach(pr ->
                    changelogBuilder.append(" * #").append(pr.getNumber()).append(" - ").append(pr.getTitle()).append("\n"));
        }
        changelogBuilder.append("\nFor a complete list of changes please visit https://github.com/" + REPOSITORY_NAME + "/compare/")
                .append(latestReleasedTag).append("...mandrel-").append(version).append("\n");
        return changelogBuilder.toString();
    }

    private void manageMilestones(GHRepository repository, PagedIterable<GHMilestone> ghMilestones, GHMilestone milestone) throws IOException {
        if (dryRun || !suffix.equals("Final")) {
            return;
        }
        if (milestone != null) {
            milestone.close();
            info("Closed milestone " + milestone.getTitle() + " (" + milestone.getNumber() + ")");
        }
        GHMilestone newMilestone = ghMilestones.toList().stream().filter(m -> m.getTitle().equals(newVersion.toString())).findAny().orElse(null);
        if (newMilestone == null) {
            newMilestone = repository.createMilestone(newVersion.toString(), "");
            info("Created milestone " + newMilestone.getTitle() + " (" + newMilestone.getNumber() + ")");
        }
    }

    private static boolean includeInChangelog(GHPullRequest pr, GHMilestone milestone) {
        try {
            if (!pr.isMerged()) {
                return false;
            }
            return pr.getMilestone() != null && pr.getMilestone().getNumber() == milestone.getNumber();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private Integer getGroup(GHPullRequest pr) {
        try {
            if (pr.getLabels().stream().anyMatch(l -> l.getName().equals("release/noteworthy-feature"))) {
                return 0;
            }
            if (pr.getLabels().stream().anyMatch(l -> l.getName().equals("backport"))) {
                return 1;
            }
        } catch (IOException e) {
            e.printStackTrace();
            error(e.getMessage());
        }
        return 2;
    }

    private GitHub connectToGitHub() {
        GitHub github = null;
        try {
             github = GitHubBuilder.fromPropertyFile().build();
        } catch (IOException e) {
            try {
                github = GitHubBuilder.fromEnvironment().build();
            } catch (IOException ioException) {
                ioException.printStackTrace();
                error(ioException.getMessage());
            }
        }
        return github;
    }

    private static void info(String message) {
        System.err.println(Ansi.AUTO.string("[@|bold INFO|@] ") + message);
    }

    private static void warn(String message) {
        System.err.println(Ansi.AUTO.string("[@|bold,yellow WARN|@] ") + message);
    }

    private static void error(String message) {
        System.err.println(Ansi.AUTO.string("[@|bold,red ERROR|@] ") + message);
        System.exit(1);
    }

    class MandrelVersion implements Comparable<MandrelVersion> {
        final static String MANDREL_VERSION_REGEX = "(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)(-(Final|(Alpha|Beta)\\d*))?";

        int major;
        int minor;
        int micro;
        int pico;
        String suffix;

        public MandrelVersion(MandrelVersion mandrelVersion) {
            this.major = mandrelVersion.major;
            this.minor = mandrelVersion.minor;
            this.micro = mandrelVersion.micro;
            this.pico = mandrelVersion.pico;
            this.suffix = mandrelVersion.suffix;
        }

        public MandrelVersion(String version) {
            final Pattern versionPattern = Pattern.compile(MandrelVersion.MANDREL_VERSION_REGEX);
            final Matcher versionMatcher = versionPattern.matcher(version);
            boolean found = versionMatcher.find();
            if (!found) {
                error("Wrong version format! " + version + " does not match pattern: " + MandrelVersion.MANDREL_VERSION_REGEX);
            }
            major = Integer.parseInt(versionMatcher.group(1));
            minor = Integer.parseInt(versionMatcher.group(2));
            micro = Integer.parseInt(versionMatcher.group(3));
            pico = Integer.parseInt(versionMatcher.group(4));
            suffix = versionMatcher.group(6);
        }

        /**
         * Calculates the new version by bumping pico in major.minor.micro.pico
         *
         * @return The new version
         */
        private MandrelVersion getNewVersion() {
            final MandrelVersion mandrelVersion = new MandrelVersion(this);
            mandrelVersion.pico++;
            return mandrelVersion;
        }

        /**
         * Calculates the latest final version by checking major.minor.micro.pico
         *
         * @return The latest final version
         * @param tags
         */
        private String getLatestReleasedTag(List<GHTag> tags) {
            final String tagPrefix = "mandrel-";
            List<MandrelVersion> finalVersions = tags.stream()
                    .filter(x -> x.getName().startsWith(tagPrefix + majorMinorMicro()) && x.getName().endsWith("Final"))
                    .map(x -> new MandrelVersion(x.getName().substring(tagPrefix.length())))
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            if (finalVersions.isEmpty()) {
                // There is no Mandrel release for this major.minor.micro, return upstream graal tag instead
                final String upstreamTag = "vm-" + majorMinorMicro();
                if (tags.stream().noneMatch(x -> x.getName().equals(upstreamTag))) {
                    error("Upstream tag " + upstreamTag + " not found in " + REPOSITORY_NAME + " please push it and try again.");
                }
                return upstreamTag;
            }
            return tagPrefix + finalVersions.get(0).toString();
        }

        private String majorMinor() {
            return major + "." + minor;
        }

        private String majorMinorMicro() {
            return major + "." + minor + "." + micro;
        }

        private String majorMinorMicroPico() {
            return major + "." + minor + "." + micro + "." + pico;
        }


        @Override
        public String toString() {
            String version = majorMinorMicroPico();
            if (suffix != null) {
                version += "-" + suffix;
            }
            return version;
        }

        @Override
        public int compareTo(MandrelVersion o) {
            assert suffix.equals(o.suffix);
            if (major > o.major) {
                return 1;
            } else if (major == o.major) {
                if (minor > o.minor) {
                    return 1;
                } else if (minor == o.minor) {
                    if (micro > o.micro) {
                        return 1;
                    } else if (micro == o.micro) {
                        if (pico > o.pico) {
                            return 1;
                        } else if (pico == o.pico) {
                            return 0;
                        }
                    }
                }
            }
            return -1;
        }
    }
}
