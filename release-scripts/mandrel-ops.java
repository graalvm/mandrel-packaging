//usr/bin/env jbang --ea "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.pgm:7.8.0.202609011348-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.gpg.bc:7.8.0.202609011348-r
//DEPS info.picocli:picocli:4.7.7
//DEPS org.kohsuke:github-api:1.330

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.console.ConsoleCredentialsProvider;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.kohsuke.github.GHAsset;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHMilestone;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Security;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class MandrelConfig {
    // Used in Quickstarts markdown text, github release body.
    // e.g. powershell -Command "Invoke-WebRequest -Uri 'https://code.quarkus.io/d?e=rest&cn=code.quarkus.io&j=21&S=io.quarkus.platform%3A3.27' -OutFile 'code-with-quarkus.zip'"
    static final Map<String, String> QUARKUS_VERSIONS = Map.of(
            // which mandrel : which quarkus
            "23.1", "3.27",
            "25.0", "3.40"
    );
}

/**
 * The file is just a collection of isolated steps. Each step is documented in the README.md.
 */
@Command(name = "mandrel-ops", mixinStandardHelpOptions = true,
        description = "Automates the cascading release workflow from GraalVM Community to Mandrel",
        subcommands = {
                UpstreamMark.class,
                UpstreamFinalize.class,
                DownstreamSyncMark.class,
                DownstreamFinalize.class,
                TagMandrel.class,
                PublishRelease.class,
                UpdateQuarkusImages.class,
                SyncUpstream.class
        })
class MandrelOps {
    static void main(String... args) {
        System.exit(new CommandLine(new MandrelOps()).execute(args));
    }
}

@Command(name = "tag-mandrel", description = "Tags the downstream repository (e.g., mandrel-packaging)")
class TagMandrel implements Callable<Integer> {
    @Option(names = { "-d", "--dir" }, defaultValue = "./")
    File repoDir;
    @Option(names = { "-b", "--branch" }, required = true)
    String branch;
    @Option(names = { "-v", "--version" }, required = true)
    String version;
    @Option(names = { "-u", "--upstream-remote" }, defaultValue = "origin")
    String upstreamRemote;
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
        SuiteOpsUtils.setup();
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(branch).call();
            git.pull().setRemote(upstreamRemote).setRemoteBranchName(branch).call();
            git.tag().setName(version).setMessage(version).setSigned(true).call();
            if (!dryRun) {
                SuiteOpsUtils.pushRef(git, upstreamRemote, "refs/tags/" + version);
            }
        }
        return 0;
    }
}

class SuiteOpsUtils {
    static final Pattern VERSION_PATTERN = Pattern.compile("^([ \\t]{1,4})\"version\"(\\s*:\\s*)\"([^\"]+)\"(.*)$");
    static final Pattern RELEASE_PATTERN = Pattern.compile("^([ \\t]{1,4})\"release\"(\\s*:\\s*)(True|False)(.*)$");

    /**
     * Removes a prefix from a version string if present.
     * @param version the version string
     * @param prefix the prefix to remove (e.g., "mandrel-", "graal-", "vm-")
     * @return version without prefix
     */
    static String stripVersionPrefix(String version, String prefix) {
        return version != null && version.startsWith(prefix) ? version.substring(prefix.length()) : version;
    }

    /**
     * Removes a suffix from a version string if present.
     * @param version the version string
     * @param suffix the suffix to remove (e.g., "-Final")
     * @return version without suffix
     */
    static String stripVersionSuffix(String version, String suffix) {
        return version != null && version.endsWith(suffix) ? version.substring(0, version.length() - suffix.length()) : version;
    }

    /**
     * Decrements a version string based on release type.
     * <p>
     * For CSPU: Decrements the last digit (e.g., 25.0.4.1 → 25.0.4.0 or 25.0.4.1.1 → 25.0.4.1.0).
     * If last digit is 1, removes the segment entirely (e.g., 25.0.4.1 → 25.0.4).
     * <p>
     * For CPU: Not typically used for decrementing CPU versions, but would decrement the 3rd segment.
     *
     * @param version the version string (e.g., "25.0.4.1" or "25.0.4.1.1")
     * @param cspu whether this is a CSPU release
     * @return decremented version string
     */
    static String decrementVersion(String version, boolean cspu) {
        final String[] parts = version.split("\\.");
        if (cspu) {
            final int last = Integer.parseInt(parts[parts.length - 1]);
            if (last > 1) {
                parts[parts.length - 1] = String.valueOf(last - 1);
                return String.join(".", parts);
            } else {
                // Last digit is 1, remove the segment (e.g., 25.0.4.1 → 25.0.4)
                return version.substring(0, version.lastIndexOf('.'));
            }
        } else {
            // CPU: decrement 3rd digit (not commonly used for decrement, but included for completeness)
            if (parts.length >= 3) {
                parts[2] = String.valueOf(Integer.parseInt(parts[2]) - 1);
            }
            return String.join(".", parts);
        }
    }

    /**
     * Increments a version string for DOWNSTREAM (Mandrel) releases.
     * <p>
     * For CSPU: Increments the last digit (e.g., 25.0.4.0 → 25.0.4.1 or 25.0.4.1 → 25.0.4.2).
     * Supports version strings of any length (4, 5, or more digits).
     * <p>
     * For CPU: Increments the 3rd digit and resets the 4th digit to 0 if it exists
     * (e.g., 25.0.4.0 → 25.0.5.0 or 25.0.4 → 25.0.5). Does NOT add a 4th digit if one doesn't exist.
     *
     * @param version the version string (e.g., "25.0.4.0" or "25.0.4.1")
     * @param cspu whether this is a CSPU release
     * @return incremented version string
     */
    static String incrementVersionDownstream(String version, boolean cspu) {
        final String[] parts = version.split("\\.");
        if (cspu) {
            // CSPU: increment the last digit regardless of version length
            parts[parts.length - 1] = String.valueOf(Integer.parseInt(parts[parts.length - 1]) + 1);
        } else {
            // CPU: increment 3rd digit and reset 4th digit to 0 if it exists
            if (parts.length >= 3) {
                parts[2] = String.valueOf(Integer.parseInt(parts[2]) + 1);
                if (parts.length >= 4) {
                    parts[3] = "0";
                }
            }
        }
        return String.join(".", parts);
    }

    /**
     * Increments a version string for UPSTREAM (GraalVM) releases.
     * <p>
     * For CSPU: Appends .1 (e.g., 25.0.4 → 25.0.4.1 or 25.0.4.1 → 25.0.4.1.1).
     * This creates 4-digit, 5-digit, or even longer version strings as CSPU releases accumulate.
     * <p>
     * For CPU: Increments the 3rd digit (e.g., 25.0.4 → 25.0.5 or 25.0.4.1.1 → 25.0.5).
     * Removes any CSPU segments (4th digit onwards) for the next CPU cycle.
     *
     * @param version the version string (e.g., "25.0.4" or "25.0.4.1.1")
     * @param cspu whether this is a CSPU release
     * @return incremented version string
     */
    static String incrementVersionUpstream(String version, boolean cspu) {
        final String[] parts = version.split("\\.");
        if (cspu) {
            // CSPU: append .1 (grows the version string)
            return version + ".1";
        } else {
            // CPU: increment 3rd digit and remove any CSPU segments
            if (parts.length >= 3) {
                parts[2] = String.valueOf(Integer.parseInt(parts[2]) + 1);
                // Return only first 3 segments for CPU
                return parts[0] + "." + parts[1] + "." + parts[2];
            }
            return version;
        }
    }

    /**
     * Extracts EXPLICITLY STATED JDK version from release text (name or body).
     * Supports version strings with any number of digits (e.g., 21.0.12, 25.0.5.0, 25.0.5.0.3).
     * <p>
     * IMPORTANT: This does NOT infer JDK versions from Mandrel/GraalVM versions!
     * Mandrel 23.1.x uses JDK 21.0.x (not 23.1.x), Mandrel 25.0.x uses JDK 25.0.x.
     * <p>
     * Looks for patterns:
     * - "jdk X.Y.Z" (case-insensitive, e.g., "GraalVM Community Edition jdk 25.0.5.0.3")
     * - "OpenJDK used: X.Y.Z" (e.g., from Mandrel release body: "OpenJDK used: 21.0.12+7-LTS")
     * <p>
     * Examples:
     * - "GraalVM Community Edition jdk 25.0.5" → "25.0.5"
     * - "GraalVM Community Edition jdk 25.0.5.0.3" → "25.0.5.0.3"
     * - "OpenJDK used: 21.0.12+7-LTS" → "21.0.12" (Mandrel release body)
     * - "Mandrel 23.1.12.0-Final" → null (no explicit JDK version stated)
     *
     * @param releaseName the release name or body text
     * @return the extracted JDK version, or null if not found
     */
    static String extractJdkVersionFromReleaseName(String releaseName) {
        // Pattern supports any number of digits: 21.0.12 or 25.0.5 or 25.0.5.0 or 25.0.5.0.3
        final Matcher m = Pattern.compile("jdk (\\d+(?:\\.\\d+)+)").matcher(releaseName);
        if (m.find()) {
            return m.group(1);
        }
        // Also try "OpenJDK used:" pattern from Mandrel release body
        final Matcher m2 = Pattern.compile("OpenJDK used:\\s*(\\d+(?:\\.\\d+)+)").matcher(releaseName);
        if (m2.find()) {
            return m2.group(1);
        }
        return null;
    }

    static void setup() {
        if (System.console() != null) {
            ConsoleCredentialsProvider.install();
        }
        Security.addProvider(new BouncyCastleProvider());
    }

    static File downloadIfNotExists(String urlStr, File dir) throws Exception {
        final URI uri = URI.create(urlStr);
        final String path = uri.getPath();
        final String fileName = path.substring(path.lastIndexOf('/') + 1);
        final File dest = new File(dir, fileName);
        if (dest.exists() && dest.length() > 0) {
            System.out.println("File " + fileName + " already exists locally. Skipping download.");
            return dest;
        }
        System.out.println("Downloading " + fileName + "...");
        final HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            final HttpResponse<Path> response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(dest.toPath(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING));
            if (response.statusCode() >= 400) {
                Files.deleteIfExists(dest.toPath());
                throw new RuntimeException("Failed to download " + fileName + " (HTTP " + response.statusCode() + ")");
            }
        }
        return dest;
    }

    /**
     * Determines if a CSPU is Empty by comparing two explicit commit references.
     * <p>
     * An "empty" CSPU contains only JDK updates with no substantive code changes in the Mandrel codebase itself.
     * This is detected by checking if:
     * 1. The only changed files are suite.py files
     * 2. The changes in those suite.py files are ONLY to "version" and "release" lines
     * <p>
     * Any other changes (e.g., dependency updates like ASM_9.7.1 → ASM_9.8) make this a non-empty CSPU.
     * Empty CSPUs skip certain workflow steps like milestone creation and Mark/Unmark suite PRs.
     * <p>
     * This is the generic utility variant that works with any two commit ObjectIds. It is used in downstream
     * workflows (e.g., DownstreamSyncMark) where the base and target commits are already known from fetched tags.
     * See also {@link UpstreamMark#isEmptyCSPU(Git, boolean, String)} for the upstream-specific variant.
     *
     * @param git    the Git repository
     * @param cspu   whether this is a CSPU release (if false, always returns false)
     * @param base   the base commit ObjectId to compare from
     * @param target the target commit ObjectId to compare to
     * @return true if this is an empty CSPU (only benign suite.py version/release changes), false otherwise
     * @throws Exception if git operations fail
     */
    static boolean isEmptyCSPU(final Git git, final boolean cspu, final ObjectId base, final ObjectId target) throws Exception {
        if (!cspu || base == null || target == null) {
            return false;
        }
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            final RevCommit baseCommit = walk.parseCommit(base);
            final RevCommit targetCommit = walk.parseCommit(target);
            return UpstreamMark.walkTheDiff(git, baseCommit, targetCommit);
        }
    }

    /**
     * Reads the current version from sdk/mx.sdk/suite.py.
     * <p>
     * The version is defined in sdk/mx.sdk/suite.py across all GraalVM repositories:
     * - graalvm-community-jdk21u, jdk25u: direct version definition
     * - oracle/graal master: version defined here, compiler uses "version_from": "sdk"
     *
     * @param repoDir the repository directory
     * @return the version string (e.g., "25.0.5" or "23.1.12")
     * @throws RuntimeException if version cannot be found
     */
    static String getCurrentVersion(File repoDir) throws IOException {
        final File suitePy = new File(repoDir, "sdk/mx.sdk/suite.py");
        if (suitePy.exists()) {
            final List<String> lines = Files.readAllLines(suitePy.toPath());
            for (String l : lines) {
                final Matcher m = VERSION_PATTERN.matcher(l);
                if (m.matches()) {
                    return m.group(3);
                }
            }
        }
        throw new RuntimeException("Could not find version in " + suitePy.getAbsolutePath());
    }

    /**
     * There is this one specific kind of conflict in suite.py files, it's the version, three versus four digits and release:
     * true or false. The script autoresolves this one and only this one kind of conflict automatically.
     */
    static void resolveSuiteConflict(File file, String targetVersion, boolean targetRelease) throws IOException {
        final List<String> lines = Files.readAllLines(file.toPath());
        final List<String> resolved = new ArrayList<>();
        boolean inConflict = false;
        String indent = "  ";
        String vSep = " : ";
        String rSep = " : ";
        boolean hasTrailingCommaVersion = true;
        boolean hasTrailingCommaRelease = true;
        boolean sawReleaseInConflict = false;
        for (String line : lines) {
            if (line.startsWith("<<<<<<< HEAD")) {
                inConflict = true;
                sawReleaseInConflict = false;
                continue;
            }
            if (inConflict) {
                if (line.startsWith("=======")) {
                    continue;
                }
                if (line.startsWith(">>>>>>>")) {
                    inConflict = false;
                    resolved.add("%s\"version\"%s\"%s\"%s"
                            .formatted(indent, vSep, targetVersion, hasTrailingCommaVersion ? "," : ""));
                    if (sawReleaseInConflict) {
                        resolved.add("%s\"release\"%s%s%s"
                                .formatted(indent, rSep, targetRelease ? "True" : "False", hasTrailingCommaRelease ? "," : ""));
                    }
                    continue;
                }
                final Matcher vm = VERSION_PATTERN.matcher(line);
                if (vm.matches()) {
                    indent = vm.group(1);
                    vSep = vm.group(2);
                    hasTrailingCommaVersion = vm.group(4).trim().endsWith(",");
                }
                final Matcher rm = RELEASE_PATTERN.matcher(line);
                if (rm.matches()) {
                    rSep = rm.group(2);
                    hasTrailingCommaRelease = rm.group(4).trim().endsWith(",");
                    sawReleaseInConflict = true;
                }
            } else {
                resolved.add(line);
            }
        }
        if (inConflict) {
            throw new RuntimeException("Unclosed conflict block in " + file.getName());
        }
        Files.write(file.toPath(), resolved, StandardCharsets.UTF_8);
        System.out.println("Resolved conflict in: " + file.getPath());
    }

    static GHMilestone inferOpenMilestone(GHRepository repo, File repoDir, String nextVersion) throws Exception {
        String currentVersion = null;
        try {
            currentVersion = getCurrentVersion(repoDir);
        } catch (Exception e) {
            System.out.println("WARN: getCurrentVersion(" + repoDir.getAbsolutePath() + ") failed, keeping fallback option. Reason: " + e.getMessage());
        }
        GHMilestone fallback = null;
        int openCount = 0;
        for (GHMilestone m : repo.listMilestones(GHIssueState.OPEN)) {
            openCount++;
            fallback = m;
            // better exact match with target nextVersion if provided
            if (nextVersion != null && m.getTitle().contains(nextVersion)) {
                return m;
            }
            // fallback to match with the version extracted from suite.py
            if (currentVersion != null && m.getTitle().contains(currentVersion)) {
                return m;
            }
        }
        // one open milestone, it's clear which one to pick...
        if (openCount == 1) {
            return fallback;
        }
        return null;
    }

    private static boolean isRelevantSuite(List<String> lines, String targetVersion) {
        if (targetVersion == null) {
            return true;
        }
        final String targetMajor = targetVersion.split("\\.")[0] + ".";
        for (String line : lines) {
            final Matcher vm = VERSION_PATTERN.matcher(line);
            if (vm.matches() && vm.group(3).startsWith(targetMajor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param release can be null, e.g. empty CSPU won't modify `release: True/False' in suite.py
     */
    static void modifySuitesReleaseState(File repoDir, Boolean release, String targetVersion) {
        Stream.of(Objects.requireNonNull(repoDir.listFiles()))
                .filter(File::isDirectory)
                .flatMap(path -> Stream.of(Objects.requireNonNull(path.listFiles()))
                        .filter(child -> child.getName().startsWith("mx.")))
                .map(path -> new File(path, "suite.py"))
                .filter(File::exists)
                .forEach(suite -> {
                    try {
                        final List<String> lines = Files.readAllLines(suite.toPath());
                        if (targetVersion != null && !isRelevantSuite(lines, targetVersion)) {
                            return;
                        }
                        boolean modified = false;
                        boolean versionUpdated = false;
                        boolean releaseUpdated = false;
                        for (int i = 0; i < lines.size(); i++) {
                            final String line = lines.get(i);
                            if (release != null && !releaseUpdated) {
                                final Matcher rm = RELEASE_PATTERN.matcher(line);
                                if (rm.matches()) {
                                    lines.set(i,
                                            rm.group(1) + "\"release\"" + rm.group(2) + (release ? "True" : "False")
                                                    + rm.group(4));
                                    modified = true;
                                    releaseUpdated = true;
                                }
                            }
                            if (targetVersion != null && !versionUpdated) {
                                final Matcher vm = VERSION_PATTERN.matcher(line);
                                if (vm.matches()) {
                                    lines.set(i,
                                            vm.group(1) + "\"version\"" + vm.group(2) + "\"" + targetVersion + "\""
                                                    + vm.group(4));
                                    modified = true;
                                    versionUpdated = true;
                                }
                            }
                        }
                        if (modified) {
                            Files.write(suite.toPath(), lines, StandardCharsets.UTF_8);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
    }

    static void pushRef(Git git, String remoteName, String refName) throws Exception {
        System.out.println("Pushing " + refName + " to " + remoteName);
        for (PushResult result : git.push().setRemote(remoteName).setRefSpecs(new RefSpec(refName + ":" + refName)).call()) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                if (update.getStatus() != RemoteRefUpdate.Status.OK && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new RuntimeException(
                            "Git push failed for " + refName + ": [" + update.getStatus() + "] " + update.getMessage());
                } else {
                    System.out.println("   [OK] Pushed " + refName + " (" + update.getStatus() + ")");
                }
            }
        }
    }

    static void pushToFork(Git git, String forkName, String branchName) throws Exception {
        final String remoteName = "mandrel-ops-fork";
        final String forkUrl = "git@github.com:" + forkName + ".git";
        try {
            git.remoteRemove().setRemoteName(remoteName).call();
        } catch (Exception ignored) {
            //no-op
        }
        git.remoteAdd().setName(remoteName).setUri(new URIish(forkUrl)).call();
        System.out.println("Pushing branch to fork " + forkUrl);
        for (PushResult result : git.push().setForce(true).setRemote(remoteName).add(branchName).call()) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                if (update.getStatus() != RemoteRefUpdate.Status.OK && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                    throw new RuntimeException("Git push failed: [" + update.getStatus() + "] " + update.getMessage());
                }
            }
        }
    }

    static GitHub connectToGitHub() throws IOException {
        try {
            return GitHubBuilder.fromPropertyFile().build();
        } catch (IOException e) {
            return GitHubBuilder.fromEnvironment().build();
        }
    }

    static void setupPRReviewers(GitHub github, GHPullRequest pullRequest, boolean isUpstream) throws IOException {
        final String currentUser = github.getMyself().getLogin();
        final List<GHUser> reviewers = new ArrayList<>();
        final List<GHUser> assignees = new ArrayList<>();
        // Nothing like important constants buried deep withing a 1Kloc script.
        final List<String> targetReviewers = isUpstream
                ? List.of("Karm", "zakkak")
                : List.of("galderz", "jerboaa", "Karm", "zakkak");
        for (String dev : targetReviewers) {
            final GHUser user = github.getUser(dev);
            if (currentUser.equalsIgnoreCase(dev)) {
                assignees.add(user);
            } else {
                reviewers.add(user);
            }
        }
        if (!reviewers.isEmpty()) {
            pullRequest.requestReviewers(reviewers);
        }
        if (!assignees.isEmpty()) {
            pullRequest.addAssignees(assignees);
        }
    }

    static void dealWithMilestones(GHRepository repo, String version, String nextVersion, boolean dryRun) throws IOException {
        boolean currentClosed = false;
        boolean nextExists = false;
        for (GHMilestone ms : repo.listMilestones(GHIssueState.OPEN)) {
            if (ms.getTitle().equals(version)) {
                if (!dryRun) {
                    ms.close();
                }
                System.out.println("Closed open milestone: " + version);
                currentClosed = true;
            }
            if (nextVersion != null && ms.getTitle().equals(nextVersion)) {
                System.out.println("Verified: Next milestone already exists -> " + nextVersion);
                nextExists = true;
            }
        }
        if (!currentClosed) {
            for (GHMilestone ms : repo.listMilestones(GHIssueState.CLOSED)) {
                if (ms.getTitle().equals(version)) {
                    System.out.println("Verified: Milestone " + version + " is already closed.");
                    break;
                }
            }
        }
        if (nextVersion != null && !nextExists) {
            if (!dryRun) {
                repo.createMilestone(nextVersion, "Created by mandrel-ops.java");
            }
            System.out.println("Created milestone: " + nextVersion);
        }
    }
}

/**
 * STEP 1: UPSTREAM MARK RELEASE
 * See README.md
 */
@Command(name = "upstream-mark", description = "Sets suites to release: True on upstream repo and opens PR")
class UpstreamMark implements Callable<Integer> {
    @Option(names = { "-d", "--dir" }, defaultValue = "./")
    File repoDir;
    @Option(names = { "-f", "--fork" }, required = true)
    String forkName;
    @Option(names = { "-b", "--base-branch" }, required = true)
    String baseBranch;
    @Option(names = { "-r", "--repo" }, required = true)
    String upstreamRepo;
    @Option(names = { "-v", "--version" }, required = true)
    String version; // e.g. 23.1.11
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;
    @Option(names = { "--test-run" })
    boolean testRun;
    @Option(names = { "--cspu" }, description = "Flag for CSPU release")
    boolean cspu;

    @Override
    public Integer call() throws Exception {
        SuiteOpsUtils.setup();
        final String branchName = "release-prep-" + System.currentTimeMillis();
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            final boolean emptyCspu = isEmptyCSPU(git, cspu, version);
            git.checkout().setCreateBranch(true).setName(branchName).setStartPoint(baseBranch).call();
            SuiteOpsUtils.modifySuitesReleaseState(repoDir, emptyCspu ? null : true, version);
            final String titleAndCommit = emptyCspu ? "Bump version for CSPU " + version + " [skip ci]" : "Mark suite files for " + version + " release [skip ci]";
            if (!git.status().call().hasUncommittedChanges()) {
                throw new RuntimeException("No changes to commit. Suites already configured for version " + version + ". " +
                        "This likely means upstream-mark was already run for this version, or the version was manually set.");
            }
            git.commit().setAll(true).setMessage(titleAndCommit).setSign(true).call();
            if (!dryRun) {
                SuiteOpsUtils.pushToFork(git, forkName, branchName);
                final GitHub github = SuiteOpsUtils.connectToGitHub();
                final GHRepository repo = github.getRepository(upstreamRepo);
                final String head = forkName.split("/")[0] + ":" + branchName;
                final GHPullRequest pr = repo.createPullRequest(titleAndCommit, head, baseBranch,
                        "Automated PR to mark suites for release.", true, false);
                if (!testRun) {
                    SuiteOpsUtils.setupPRReviewers(github, pr, true);
                }
                System.out.println("PR created: " + pr.getHtmlUrl());
            } else {
                System.out.println("[DRY RUN] Would push branch " + branchName + " and create PR: " + titleAndCommit);
            }
        }
        return 0;
    }

    /**
     * Determines if a CSPU is Empty by comparing the current HEAD against
     * a derived previous version tag.
     * <p>
     * An "empty" CSPU contains only JDK updates with no substantive code changes in the Mandrel codebase itself.
     * This is detected by checking if:
     * 1. The only changed files are suite.py files
     * 2. The changes in those suite.py files are ONLY to "version" and "release" lines
     * <p>
     * Any other changes (e.g., dependency updates like ASM_9.7.1 → ASM_9.8) make this a non-empty CSPU.
     * Empty CSPUs skip certain workflow steps like milestone creation and Mark/Unmark suite PRs.
     * <p>
     * This is the upstream-specific variant used in UpstreamMark and UpstreamFinalize workflows. It automatically
     * calculates the previous version tag by decrementing the version number (e.g., 25.0.4.1 → vm-25.0.4.0 or
     * 25.0.4.0 → vm-25.0.4) and compares that tag against HEAD. If the base tag doesn't exist, it conservatively
     * returns true (assumes empty).
     * <p>
     * See also {@link SuiteOpsUtils#isEmptyCSPU(Git, boolean, ObjectId, ObjectId)} for the generic variant that
     * works with explicit commit references.
     *
     * @param git     the Git repository
     * @param cspu    whether this is a CSPU release (if false, always returns false)
     * @param version the current version string (e.g., "25.0.4.1")
     * @return true if this is an empty CSPU (only benign suite.py version/release changes), false otherwise
     * @throws Exception if git operations fail
     */
    public static boolean isEmptyCSPU(final Git git, final boolean cspu, final String version) throws Exception {
        if (!cspu) {
            return false;
        }
        final String baseTag = "vm-" + SuiteOpsUtils.decrementVersion(version, cspu);
        final ObjectId baseId = git.getRepository().resolve("refs/tags/" + baseTag);
        if (baseId != null) {
            try (RevWalk walk = new RevWalk(git.getRepository())) {
                final RevCommit baseCommit = walk.parseCommit(baseId);
                final RevCommit headCommit = walk.parseCommit(git.getRepository().resolve("HEAD"));
                return walkTheDiff(git, baseCommit, headCommit);
            }
        }
        return true;
    }

    /**
     * Checks if the diff between two commits contains only benign suite.py changes.
     * <p>
     * An "empty" CSPU has changes ONLY to suite.py files, and those changes are ONLY
     * to "version" and "release" lines. Any other changes (e.g., dependency updates)
     * make this a non-empty release.
     * <p>
     * This uses the same logic as {@link #resolveSuiteConflict}: only changes that
     * match VERSION_PATTERN or RELEASE_PATTERN are considered benign.
     *
     * @param git the Git repository
     * @param baseCommit the base commit to compare from
     * @param headCommit the head commit to compare to
     * @return true if all changes are benign suite.py version/release changes, false otherwise
     * @throws IOException if git operations fail
     */
    public static boolean walkTheDiff(Git git, RevCommit baseCommit, RevCommit headCommit) throws IOException {
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(git.getRepository());
            for (DiffEntry entry : diffFormatter.scan(baseCommit.getTree(), headCommit.getTree())) {
                String path = entry.getNewPath();
                if (DiffEntry.DEV_NULL.equals(path)) {
                    path = entry.getOldPath();
                }
                // First check: only suite.py files
                if (!path.endsWith("suite.py")) {
                    return false;
                }
                // Second check: only version/release changes in suite.py
                if (!isBenignSuitePyChange(git, entry)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checks if a suite.py file diff contains only benign version/release changes.
     * <p>
     * Parses the actual diff line-by-line and verifies that all added/removed lines are one of:
     * - VERSION_PATTERN matches (version changes)
     * - RELEASE_PATTERN matches (release True/False changes)
     * - Empty/whitespace only
     * - Structural syntax only (braces, brackets, commas: {, }, [, ], ,)
     * <p>
     * Any other changes (e.g., to dependencies like "ASM_9.7.1" → "ASM_9.8") are considered non-benign.
     *
     * @param git the Git repository
     * @param entry the DiffEntry for the suite.py file
     * @return true if all changes are to version/release lines only, false otherwise
     * @throws IOException if diff formatting fails
     */
    private static boolean isBenignSuitePyChange(Git git, DiffEntry entry) throws IOException {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
             final DiffFormatter diffFormatter = new DiffFormatter(out)) {
            diffFormatter.setRepository(git.getRepository());
            diffFormatter.setContext(0); // Only changed lines, no context
            diffFormatter.format(entry);

            final String diffText = out.toString(StandardCharsets.UTF_8);
            final String[] lines = diffText.split("\n");

            for (String line : lines) {
                // Skip diff metadata lines
                if (line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("--- ") || line.startsWith("+++ ") ||
                    line.startsWith("@@ ")) {
                    continue;
                }

                // Only examine added/removed lines (starts with + or -)
                if (!line.startsWith("+") && !line.startsWith("-")) {
                    continue;
                }

                // Remove the +/- prefix to get actual line content
                final String content = line.substring(1);
                final String trimmed = content.trim();

                // Empty lines are benign (whitespace changes)
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Structural/syntax characters are benign (braces, brackets, commas)
                if (trimmed.matches("^[\\{\\}\\[\\],]+$")) {
                    continue;
                }

                // Check if this line matches version or release pattern
                final boolean isVersionLine = SuiteOpsUtils.VERSION_PATTERN.matcher(content).matches();
                final boolean isReleaseLine = SuiteOpsUtils.RELEASE_PATTERN.matcher(content).matches();

                if (!isVersionLine && !isReleaseLine) {
                    // This is a change to something other than version/release
                    // (e.g., dependency update, new library, etc.)
                    System.out.println("Non-benign change detected in suite.py: " + trimmed);
                    return false;
                }
            }
        }
        return true;
    }
}

/**
 * STEP 2: UPSTREAM FINALIZE
 * See README.md
 */
@Command(name = "upstream-finalize", description = "Tags upstream, manages milestones, and unmarks suites")
class UpstreamFinalize implements Callable<Integer> {
    @Option(names = { "-d", "--dir" }, defaultValue = "./")
    File repoDir;
    @Option(names = { "-f", "--fork" }, required = true)
    String forkName;
    @Option(names = { "-b", "--base-branch" }, required = true)
    String baseBranch;
    @Option(names = { "-r", "--repo" }, required = true)
    String upstreamRepo;
    @Option(names = { "-v", "--version" }, required = true)
    String version; // e.g. 23.1.11
    @Option(names = { "-j", "--jdk-version" }, required = true)
    String jdkVersion; // e.g. 21.0.11
    @Option(names = { "-u", "--upstream-remote" }, defaultValue = "upstream")
    String upstreamRemote;
    @Option(names = { "-n", "--next-version" })
    String nextVersion;
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;
    @Option(names = { "--test-run" })
    boolean testRun;
    @Option(names = { "--cspu" }, description = "Flag for CSPU release")
    boolean cspu;

    @Override
    public Integer call() throws Exception {
        SuiteOpsUtils.setup();
        if (nextVersion == null) {
            // Upstream uses append-style CSPU versioning (25.0.4 → 25.0.4.1 → 25.0.4.1.1)
            nextVersion = SuiteOpsUtils.incrementVersionUpstream(version, cspu);
            System.out.println("Auto-calculated next version: " + nextVersion);
        }
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            git.pull().setRemote(upstreamRemote).setRemoteBranchName(baseBranch).call();
            final boolean emptyCspu = UpstreamMark.isEmptyCSPU(git, cspu, version);
            System.out.println("Checking remote tags on " + upstreamRemote + "...");
            final Collection<Ref> remoteRefs = git.lsRemote().setTags(true).setRemote(upstreamRemote).call();
            boolean vmTagExistsRemotely = false;
            boolean jdkTagExistsRemotely = false;
            final String vmTagName = "vm-" + version;
            final String jdkTagName = "jdk-" + jdkVersion;
            for (Ref ref : remoteRefs) {
                if (ref.getName().equals("refs/tags/" + vmTagName)) {
                    vmTagExistsRemotely = true;
                }
                if (ref.getName().equals("refs/tags/" + jdkTagName)) {
                    jdkTagExistsRemotely = true;
                }
            }
            final boolean pushVm = needsPush(git, vmTagName, vmTagExistsRemotely);
            final boolean pushJdk = needsPush(git, jdkTagName, jdkTagExistsRemotely);
            if (pushVm || pushJdk) {
                if (!dryRun) {
                    if (pushVm) {
                        SuiteOpsUtils.pushRef(git, upstreamRemote, "refs/tags/" + vmTagName);
                    }
                    if (pushJdk) {
                        SuiteOpsUtils.pushRef(git, upstreamRemote, "refs/tags/" + jdkTagName);
                    }
                } else {
                    System.out.println("[DRY RUN] Would push tags to " + upstreamRemote + ".");
                }
            } else {
                System.out.println("Both tags already exist remotely. Skipping tag push.");
            }
            final GitHub github = SuiteOpsUtils.connectToGitHub();
            final GHRepository repo = github.getRepository(upstreamRepo);
            if (emptyCspu) {
                System.out.println("Empty CSPU detected. Skipping Milestone and Unmark suites PR creation.");
                return 0;
            }
            SuiteOpsUtils.dealWithMilestones(repo, version, nextVersion, dryRun);
            // Prep next
            final String branchName = "bump-version-" + nextVersion + "-" + System.currentTimeMillis();
            git.checkout().setCreateBranch(true).setName(branchName).setStartPoint(baseBranch).call();
            SuiteOpsUtils.modifySuitesReleaseState(repoDir, false, nextVersion);
            final String titleAndCommit = "Unmark suite files and bump version to " + nextVersion + " [skip ci]";
            if (!git.status().call().hasUncommittedChanges()) {
                System.out.println("WARN: No changes to commit. Suites already configured.");
            } else {
                git.commit().setAll(true).setMessage(titleAndCommit).setSign(true).call();
            }
            if (!dryRun) {
                SuiteOpsUtils.pushToFork(git, forkName, branchName);
                final String head = forkName.split("/")[0] + ":" + branchName;
                final GHPullRequest pr = repo.createPullRequest(titleAndCommit, head, baseBranch, titleAndCommit, true, false);
                if (!testRun) {
                    SuiteOpsUtils.setupPRReviewers(github, pr, true);
                }
                System.out.println("PR created: " + pr.getHtmlUrl());
            } else {
                System.out.println("[DRY RUN] Would push branch " + branchName + " and open PR.");
            }
        }
        return 0;
    }

    private boolean needsPush(Git git, String tag, boolean existsRemotely) throws GitAPIException {
        try {
            git.tag().setName(tag).setMessage(tag).setSigned(true).call();
            System.out.println("Created local tag: " + tag);
            return true;
        } catch (org.eclipse.jgit.api.errors.RefAlreadyExistsException e) {
            System.out.println("Tag " + tag + " already exists locally.");
            if (!existsRemotely) {
                System.out.println(" -> But it is missing remotely. Will push.");
                return true;
            }
        }
        return false;
    }
}

/**
 * STEP 3: DOWNSTREAM SYNC MARK
 * See README.md
 */
@Command(name = "downstream-sync-mark", description = "Merges upstream tag to downstream and opens Mark Release PR")
class DownstreamSyncMark implements Callable<Integer> {
    @Option(names = { "-d", "--dir" }, defaultValue = "./")
    File repoDir;
    @Option(names = { "-f", "--fork" }, required = true)
    String forkName;
    @Option(names = { "-b", "--base-branch" }, required = true)
    String baseBranch; // e.g. mandrel/23.1
    @Option(names = { "-r", "--repo" }, required = true)
    String downstreamRepo; // e.g. graalvm/mandrel
    @Option(names = { "-U", "--upstream-url" }, required = true)
    String upstreamUrl;
    @Option(names = { "-t", "--upstream-tag" }, required = true)
    String upstreamTag; // e.g. vm-23.1.11
    @Option(names = { "-s", "--suffix" }, defaultValue = "0")
    String suffix;
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;
    @Option(names = { "--test-run" })
    boolean testRun;
    @Option(names = { "--cspu" }, description = "Flag for CSPU release")
    boolean cspu;

    @Override
    public Integer call() throws Exception {
        SuiteOpsUtils.setup();
        final String branchName = "release-prep-" + System.currentTimeMillis();
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            boolean emptyCspu = false;
            if (cspu) {
                final String rawUpstreamVersion = SuiteOpsUtils.stripVersionPrefix(upstreamTag, "vm-");
                final String baseTagVersion = SuiteOpsUtils.decrementVersion(rawUpstreamVersion, cspu);
                final String baseTag = "vm-" + baseTagVersion; // Re-add vm- prefix for tag fetching
                git.fetch().setRemote(upstreamUrl).setRefSpecs(new RefSpec("+refs/tags/" + baseTag + ":refs/tags/" + baseTag)).call();
                final ObjectId baseId = git.getRepository().resolve("refs/tags/" + baseTag);
                git.fetch().setRemote(upstreamUrl).setRefSpecs(new RefSpec("+refs/tags/" + upstreamTag + ":refs/tags/" + upstreamTag)).call();
                final ObjectId tagId = git.getRepository().resolve("refs/tags/" + upstreamTag);
                emptyCspu = SuiteOpsUtils.isEmptyCSPU(git, cspu, baseId, tagId);
            }
            final String rawUpstreamVersion = SuiteOpsUtils.stripVersionPrefix(upstreamTag, "vm-");
            final String mandrelVersion = cspu ? rawUpstreamVersion : rawUpstreamVersion + "." + suffix;
            git.checkout().setCreateBranch(true).setName(branchName).setStartPoint(baseBranch).call();
            git.fetch().setRemote(upstreamUrl)
                    .setRefSpecs(new RefSpec("+refs/tags/*:refs/tags/*")).call();
            final ObjectId tagId = git.getRepository().resolve(upstreamTag);
            final MergeResult mergeResult = git.merge().include(upstreamTag, tagId).setCommit(false).call();
            final String prTitleVersion = mandrelVersion + (cspu ? "" : "-Final");
            if (!mergeResult.getMergeStatus().isSuccessful() && mergeResult.getMergeStatus() != MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                if (mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    for (String conflictingPath : mergeResult.getConflicts().keySet()) {
                        if (!conflictingPath.endsWith("suite.py")) {
                            throw new RuntimeException("Unexpected conflict: " + conflictingPath);
                        }
                        SuiteOpsUtils.resolveSuiteConflict(new File(repoDir, conflictingPath), mandrelVersion, true);
                        git.add().addFilepattern(conflictingPath).call();
                    }
                } else {
                    throw new RuntimeException("Merge failed: " + mergeResult.getMergeStatus());
                }
            }
            SuiteOpsUtils.modifySuitesReleaseState(repoDir, emptyCspu ? null : true, mandrelVersion);
            git.add().addFilepattern(".").call();
            final String titleAndCommit = emptyCspu ? "[Backport] Bump version for CSPU " + prTitleVersion + " [skip ci]" : "Mark suites for " + prTitleVersion + " release [skip ci]";
            if (!git.status().call().hasUncommittedChanges()) {
                System.out.println("WARN: No changes to commit. Suites already configured.");
            } else {
                git.commit().setAll(true).setMessage(titleAndCommit).setSign(true).call();
            }
            if (!dryRun) {
                final GitHub github = SuiteOpsUtils.connectToGitHub();
                SuiteOpsUtils.pushToFork(git, forkName, branchName);
                final String tagLink = upstreamUrl.replaceAll("\\.git$", "") + "/releases/tag/" + upstreamTag;
                final GHPullRequest pr = github.getRepository(downstreamRepo)
                        .createPullRequest(titleAndCommit, forkName.split("/")[0] + ":" + branchName, baseBranch, "Upstream tag: " + tagLink, true, false);
                if (!testRun) {
                    SuiteOpsUtils.setupPRReviewers(github, pr, false);
                }
                System.out.println("PR created: " + pr.getHtmlUrl());
            }
        }
        return 0;
    }
}

/**
 * STEP 4: DOWNSTREAM FINALIZE
 * See README.md
 */
@Command(name = "downstream-finalize", description = "Tags downstream and manages milestones")
class DownstreamFinalize implements Callable<Integer> {
    @Option(names = { "-d", "--dir" }, defaultValue = "./")
    File repoDir;
    @Option(names = { "-b", "--base-branch" }, required = true)
    String baseBranch; // e.g. mandrel/23.1
    @Option(names = { "-r", "--repo" }, required = true)
    String downstreamRepo; // e.g. graalvm/mandrel
    @Option(names = { "-v", "--version" }, required = true)
    String version; // e.g. mandrel-23.1.11.0-Final
    @Option(names = { "-u", "--upstream-remote" }, defaultValue = "origin")
    String upstreamRemote;
    @Option(names = { "-n", "--next-version" })
    String nextVersion;
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;
    @Option(names = { "--cspu" }, description = "Flag for CSPU release")
    boolean cspu;

    @Override
    public Integer call() throws Exception {
        SuiteOpsUtils.setup();
        if (nextVersion == null) {
            String cleanVersion = SuiteOpsUtils.stripVersionPrefix(version, "mandrel-");
            cleanVersion = SuiteOpsUtils.stripVersionSuffix(cleanVersion, "-Final");
            // Downstream uses increment-style CSPU versioning (25.0.4.0 → 25.0.4.1 → 25.0.4.2)
            final String incrementedVersion = SuiteOpsUtils.incrementVersionDownstream(cleanVersion, cspu);
            // Used in milestones names too, we want -Final for milestones names.
            nextVersion = incrementedVersion + "-Final";
            System.out.println("Auto-calculated next version: " + nextVersion);
        }
        final String currentMilestoneTitle = SuiteOpsUtils.stripVersionPrefix(version, "mandrel-");
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            git.pull().setRemote(upstreamRemote).setRemoteBranchName(baseBranch).call();
            System.out.println("Creating signed tag: " + version);
            try {
                git.tag().setName(version).setMessage(version).setSigned(true).call();
                if (!dryRun) {
                    SuiteOpsUtils.pushRef(git, upstreamRemote, "refs/tags/" + version);
                }
            } catch (org.eclipse.jgit.api.errors.RefAlreadyExistsException e) {
                System.out.println("Tag " + version + " already exists locally. Assuming tag is correct and proceeding.");
                if (!dryRun) {
                    SuiteOpsUtils.pushRef(git, upstreamRemote, "refs/tags/" + version);
                }
            }
            final GitHub github = SuiteOpsUtils.connectToGitHub();
            final GHRepository repo = github.getRepository(downstreamRepo);
            SuiteOpsUtils.dealWithMilestones(repo, currentMilestoneTitle, nextVersion, dryRun);
        }
        return 0;
    }
}

/**
 * STEP 5: PUBLISH RELEASE
 * See README.md
 */
@Command(name = "publish-release", description = "Downloads Jenkins artifacts and creates a draft GitHub release")
class PublishRelease implements Callable<Integer> {
    @Option(names = { "-r", "--repo" }, required = true, description = "Downstream repo (e.g., graalvm/mandrel)")
    String repoName;
    @Option(names = { "-v", "--version" }, required = true, description = "Release tag (e.g., mandrel-25.0.3.0-Final)")
    String version;
    @Option(names = { "-p",
            "--prev-version" }, required = true, description = "Previous release tag (e.g., mandrel-25.0.2.0-Final)")
    String prevVersion;
    @Option(names = { "-U",
            "--upstream-repo" }, required = true, description = "Upstream repo (e.g., graalvm/graalvm-community-jdk25u)")
    String upstreamRepo;
    @Option(names = { "-t", "--upstream-tag" }, required = true, description = "Upstream tag (e.g., vm-25.0.3)")
    String upstreamTag;
    @Option(names = { "-j", "--jdk-major" }, description = "JDK major version (e.g., 25). Inferred from upstream-repo if omitted.")
    String jdkMajorOpt;
    @Option(names = { "-q", "--quarkus-version" }, description = "Quarkus platform version (e.g., 3.27). Inferred from Mandrel version if omitted.")
    String quarkusVersionOpt;
    @Option(names = { "-J", "--jdk-version" }, description = "Exact OpenJDK version (e.g., 25.0.5+9-LTS). When provided, skips Jenkins download and uses local artifacts only.")
    String jdkVersionOpt;
    @Option(names = { "--linux-build" }, defaultValue = "-1")
    int linuxBuild;
    @Option(names = { "--windows-build" }, defaultValue = "-1")
    int windowsBuild;
    @Option(names = { "--macos-build" }, defaultValue = "-1")
    int macosBuild;
    @Option(names = { "-O", "--download-dir" }, defaultValue = "./artifacts")
    File downloadDir;
    @Option(names = { "-T",
            "--template" }, defaultValue = "release-template.md", description = "Path to the release template markdown file")
    File templateFile;
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;
    @Option(names = { "--cspu" }, description = "Flag for CSPU release")
    boolean cspu;

    @Override
    public Integer call() throws Exception {
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw new IOException("Failed to create download directory: " + downloadDir.getAbsolutePath());
        }
        if (!templateFile.exists() || !templateFile.isFile()) {
            throw new RuntimeException("Release template file not found at: " + templateFile.getAbsolutePath());
        }
        final String fullVersion = SuiteOpsUtils.stripVersionPrefix(version, "mandrel-");
        final String[] parts = fullVersion.split("\\.");
        final int major = Integer.parseInt(parts[0]);
        final int minor = Integer.parseInt(parts[1]);
        final String jobPrefix = "mandrel-" + major + "-" + minor;
        String jdkMajor = jdkMajorOpt;
        if (jdkMajor == null || jdkMajor.isBlank()) {
            final Matcher m = Pattern.compile("jdk(\\d+)u?").matcher(upstreamRepo);
            if (m.find()) {
                jdkMajor = m.group(1);
                System.out.println("Inferred JDK major version " + jdkMajor + " from upstream repo " + upstreamRepo);
            } else {
                throw new RuntimeException("ABORT: Could not infer JDK major version from upstream-repo '" + upstreamRepo + "'. Please provide --jdk-major explicitly.");
            }
        }
        // We directly print Q version in the release Markdown to prevent it from
        // pulling incompatible Q releases later in future.
        String quarkusVersion = quarkusVersionOpt;
        if (quarkusVersion == null || quarkusVersion.isBlank()) {
            quarkusVersion = MandrelConfig.QUARKUS_VERSIONS.entrySet().stream()
                    .filter(e -> fullVersion.startsWith(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("ABORT: Could not infer Quarkus version from Mandrel version '" + fullVersion + "'. Please provide -q or --quarkus-version explicitly."));
            System.out.println("Inferred Quarkus version " + quarkusVersion + " from Mandrel version " + fullVersion);
        }
        final List<String> targetUrls = new ArrayList<>();
        final Set<String> discoveredJdkVersions = new HashSet<>();
        final String exactJdkVersion;
        final List<File> downloadedFiles = new ArrayList<>();

        if (jdkVersionOpt != null && !jdkVersionOpt.isBlank()) {
            // Skip Jenkins download, use local artifacts only
            exactJdkVersion = jdkVersionOpt;
            System.out.println("Using provided OpenJDK version: " + exactJdkVersion);
            System.out.println("Skipping Jenkins download, collecting local artifacts from: " + downloadDir.getAbsolutePath());

            // Collect local artifact files (all platforms - build numbers are ignored)
            collectLocalArtifacts(downloadedFiles, jdkMajor, fullVersion, downloadDir);

            if (downloadedFiles.isEmpty()) {
                throw new RuntimeException("ABORT: No local artifacts found in " + downloadDir.getAbsolutePath() +
                    ". Expected files matching pattern: mandrel-java" + jdkMajor + "-{platform}-{arch}-" + fullVersion + ".*");
            }
            System.out.println("Found " + downloadedFiles.size() + " local artifact files to upload.");
        } else {
            // Original behavior: fetch from Jenkins
            System.out.println("Resolving Jenkins artifacts and fetching MANDREL.md...");
            resolvePlatform(targetUrls, discoveredJdkVersions, jobPrefix, linuxBuild, "linux", "el8", jdkMajor, fullVersion,
                    "amd64");
            resolvePlatform(targetUrls, discoveredJdkVersions, jobPrefix, linuxBuild, "linux", "el8_aarch64", jdkMajor, fullVersion,
                    "aarch64");
            resolvePlatform(targetUrls, discoveredJdkVersions, jobPrefix, windowsBuild, "windows", "w2k19", jdkMajor, fullVersion,
                    "amd64");
            resolvePlatform(targetUrls, discoveredJdkVersions, jobPrefix, macosBuild, "macos", "macos_aarch64", jdkMajor,
                    fullVersion, "aarch64");
            if (discoveredJdkVersions.isEmpty()) {
                throw new RuntimeException("ABORT: No OpenJDK version could be determined from MANDREL.md files.");
            }
            if (discoveredJdkVersions.size() > 1) {
                throw new RuntimeException(
                        "ABORT: Multiple mismatching OpenJDK versions detected across builds. " + discoveredJdkVersions);
            }
            exactJdkVersion = discoveredJdkVersions.iterator().next();
            System.out.println("Verified uniform OpenJDK version across all platforms: " + exactJdkVersion);
            System.out.println("Downloading " + targetUrls.size() + " artifact files...");
            for (String url : targetUrls) {
                downloadedFiles.add(SuiteOpsUtils.downloadIfNotExists(url, downloadDir));
            }
        }
        final GitHub github = SuiteOpsUtils.connectToGitHub();
        final GHRepository repo = github.getRepository(repoName);
        boolean emptyCspu = false;
        if (cspu) {
            try {
                final GHCompare compare = repo.getCompare(prevVersion, version);
                emptyCspu = true;
                // An empty CSPU has the only diff: suite.py and nothing else.
                for (GHCommit.File file : compare.getFiles()) {
                    if (!file.getFileName().endsWith("suite.py")) {
                        emptyCspu = false;
                        break;
                    }
                }
            } catch (Exception e) {
                System.out.println("WARN: Could not compare versions via GH API, assuming non-empty CSPU.");
            }
        }
        String cspuHeader = "";
        if (cspu) {
            cspuHeader = "This is a Critical Security Patch Update (CSPU).\n";
            if (emptyCspu) {
                String prevVersionClean = SuiteOpsUtils.stripVersionPrefix(prevVersion, "mandrel-");
                prevVersionClean = SuiteOpsUtils.stripVersionSuffix(prevVersionClean, "-Final");
                cspuHeader += "It brings updated JDK " + exactJdkVersion + ", but is otherwise identical to " + prevVersionClean + ".\n\n";
            } else {
                cspuHeader += "\n";
            }
        }
        String changelogSection;
        if (emptyCspu) {
            changelogSection = "### Changelog\n\nThis release has an empty changelog, as the only change is the updated OpenJDK.";
        } else {
            changelogSection = "### Changelog\n\nFor a complete list of changes please visit https://github.com/graalvm/mandrel/compare/" + prevVersion + "..." + version;
        }
        final String templateText = Files.readString(templateFile.toPath(), StandardCharsets.UTF_8);
        final String body = templateText
                .replace("{{CSPU_HEADER}}", cspuHeader)
                .replace("{{CHANGELOG_SECTION}}", changelogSection)
                .replace("{{FULL_VERSION}}", fullVersion)
                .replace("{{VERSION}}", version)
                .replace("{{PREV_VERSION}}", prevVersion)
                .replace("{{UPSTREAM_REPO}}", upstreamRepo)
                .replace("{{UPSTREAM_TAG}}", upstreamTag)
                .replace("{{JDK_VERSION}}", exactJdkVersion)
                .replace("{{JDK_MAJOR}}", jdkMajor)
                .replace("{{QUARKUS_VERSION}}", quarkusVersion);
        final String releaseTitle = cspu ? "Mandrel " + fullVersion + " CSPU" : "Mandrel " + fullVersion;
        if (dryRun) {
            System.out.println("\n[DRY RUN] Release Body Preview:");
            System.out.println("==================================================");
            System.out.println(body);
            System.out.println("==================================================");
            System.out.println(
                    "[DRY RUN] Would upload " + downloadedFiles.size() + " artifacts to a Draft release titled '" + releaseTitle + "'.");
            return 0;
        }
        System.out.println("Creating draft GitHub release for " + version + "...");
        final GHRelease release = repo.createRelease(version)
                .name(releaseTitle)
                .prerelease(!fullVersion.contains("Final"))
                .body(body)
                .draft(true)
                .create();
        for (File f : downloadedFiles) {
            final String mime = f.getName().endsWith("tar.gz") ? "application/gzip" :
                    f.getName().endsWith("zip") ? "application/zip" : "text/plain";
            System.out.println("Uploading asset: " + f.getName());
            release.uploadAsset(f, mime);
        }
        System.out.println("Draft release created successfully: " + release.getHtmlUrl());
        return 0;
    }

    private void collectLocalArtifacts(List<File> files, String jdkMajor, String fullVersion, File downloadDir) {
        // Collect all platform artifacts (build numbers are not needed when using local files)
        // Linux amd64 artifacts
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-linux-amd64-" + fullVersion + ".tar.gz");
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-linux-amd64-" + fullVersion + ".tar.gz.sha1");
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-linux-amd64-" + fullVersion + ".tar.gz.sha256");
        // Linux aarch64 artifacts
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-linux-aarch64-" + fullVersion + ".tar.gz");
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-linux-aarch64-" + fullVersion + ".tar.gz.sha1");
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-linux-aarch64-" + fullVersion + ".tar.gz.sha256");
        // Windows artifacts
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-windows-amd64-" + fullVersion + ".zip");
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-windows-amd64-" + fullVersion + ".zip.sha1");
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-windows-amd64-" + fullVersion + ".zip.sha256");
        // macOS artifacts
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-macos-aarch64-" + fullVersion + ".tar.gz");
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-macos-aarch64-" + fullVersion + ".tar.gz.sha1");
        addLocalArtifact(files, downloadDir, "mandrel-java" + jdkMajor + "-macos-aarch64-" + fullVersion + ".tar.gz.sha256");
    }

    private void addLocalArtifact(List<File> files, File downloadDir, String filename) {
        final File file = new File(downloadDir, filename);
        if (file.exists() && file.isFile()) {
            files.add(file);
            System.out.println("  Found: " + filename);
        } else {
            System.out.println("  WARN: Missing expected file: " + filename);
        }
    }

    private void resolvePlatform(List<String> urls, Set<String> jdkVersions, String jobPrefix, int buildNum, String platform,
            String label, String jdkMajor, String fullVersion, String archExt) throws IOException, InterruptedException {
        if (buildNum <= 0) {
            return;
        }
        final String baseUrl = "https://ci.modcluster.io/job/";
        final String baseArtifactDir = baseUrl + jobPrefix + "-" + platform + "-build-matrix/" + buildNum + "/JDK_RELEASE=ga,JDK_VERSION=" + jdkMajor + ",LABEL=" + label + "/artifact";
        final String mandrelMdUrl = baseArtifactDir + "/MANDREL.md";
        final String discoveredJdk = fetchJdkVersion(mandrelMdUrl);
        jdkVersions.add(discoveredJdk);
        final String ext = platform.equals("windows") ? ".zip" : ".tar.gz";
        final String filename = "mandrel-java" + jdkMajor + "-" + platform + "-" + archExt + "-" + fullVersion + ext;
        final String fileBase = baseArtifactDir + "/" + filename;
        urls.add(fileBase);
        urls.add(fileBase + ".sha1");
        urls.add(fileBase + ".sha256");
    }

    private String fetchJdkVersion(String urlStr) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlStr)).GET().build();
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            final HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Failed to fetch " + urlStr + " (HTTP " + response.statusCode() + ")");
            }
            try (Stream<String> lines = response.body()) {
                return lines.filter(line -> line.startsWith("OpenJDK used:"))
                        .map(line -> line.substring("OpenJDK used:".length()).trim())
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Could not find 'OpenJDK used:' in " + urlStr));
            }
        }
    }
}

/**
 * STEP 6: UPDATE QUARKUS IMAGES
 * See README.md
 */
@Command(name = "update-quarkus-images", description = "Updates mandrel.yaml in quarkus-images and opens PR")
class UpdateQuarkusImages implements Callable<Integer> {
    @Option(names = { "-M",
            "--mandrel-repo" }, defaultValue = "graalvm/mandrel", description = "The Mandrel repository (for fetching releases)")
    String mandrelRepoName;
    @Option(names = { "-d", "--dir" }, required = true, description = "Path to local quarkus-images clone")
    File repoDir;
    @Option(names = { "-m", "--month" }, required = true, description = "Month for PR title (e.g., January)")
    String month;
    @Option(names = { "-v",
            "--version" }, required = true, split = ",", description = "Release tags to update (e.g., mandrel-25.0.3.0-Final,mandrel-23.1.11.0-Final,graal-25.3.4.1)")
    List<String> versions;
    @Option(names = { "-p",
            "--prev-version" }, required = true, split = ",", description = "Previous release tags to replace (e.g., mandrel-25.0.2.0-Final,mandrel-23.1.10.0-Final,graal-25.2.4)")
    List<String> prevVersions;
    @Option(names = { "-O",
            "--download-dir" }, defaultValue = "./artifacts", description = "Directory containing the downloaded sha256 files")
    File downloadDir;
    @Option(names = { "-f", "--fork" }, required = true, description = "Your GitHub fork (e.g., Karm/quarkus-images)")
    String forkName;
    @Option(names = { "-r", "--upstream-repo" }, defaultValue = "quarkusio/quarkus-images", description = "Upstream repo")
    String upstreamRepo;
    @Option(names = { "-B", "--base-branch" }, defaultValue = "main", description = "Base branch in upstream")
    String baseBranch;
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;
    @Option(names = { "--test-run" })
    boolean testRun;
    @Option(names = { "--cspu" })
    boolean cspu;

    @Override
    public Integer call() throws Exception {
        SuiteOpsUtils.setup();
        // When we know for sure, we can enforce that CPU/CSPU happens only at certain hardcoded months.
        final Set<String> validMonths = Set.of("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December");
        if (!validMonths.contains(month)) {
            throw new RuntimeException(
                    "ABORT: Invalid month provided. Must be fully spelled out with capital first letter (e.g., January). You provided: " + month);
        }
        if (versions.size() != prevVersions.size()) {
            throw new RuntimeException(
                    "ABORT: The number of --version arguments must match the number of --prev-version arguments.");
        }
        try (Git git = Git.open(repoDir)) {
            // isClean is too harsh, jgit was picking e.g. IDEA untracked files that git had excluded globally...
            if (git.status().call().hasUncommittedChanges()) {
                throw new RuntimeException(
                        "ABORT: Local repository " + repoDir.getAbsolutePath() + " has uncommitted changes.");
            }
            git.checkout().setName(baseBranch).call();
            final String branchName = month + "-" + (cspu ? "CSPU" : "CPU") + "-" + System.currentTimeMillis();
            git.checkout().setCreateBranch(true).setName(branchName).setStartPoint(baseBranch).call();
            final GitHub github = SuiteOpsUtils.connectToGitHub();
            final GHRepository mandrelRepo = github.getRepository(mandrelRepoName);
            final File mandrelYamlFile = new File(repoDir, "quarkus-mandrel-builder-image/mandrel.yaml");
            if (!mandrelYamlFile.exists()) {
                throw new RuntimeException(
                        "ABORT: Could not find quarkus-mandrel-builder-image/mandrel.yaml in " + repoDir.getAbsolutePath());
            }
            final List<String> extractedJdkVersionsForTitle = new ArrayList<>();
            for (int j = 0; j < versions.size(); j++) {
                final String versionTag = versions.get(j);
                final String prevVersionTag = prevVersions.get(j);
                // There are a lot of hardcoded assumptions about what a graalvm/graalvm-ce-builds released tarball
                // look like. Inevitably fragile.
                if (versionTag.startsWith("graal-")) {
                    System.out.println("\nProcessing updates for GraalVM " + versionTag + " (replacing " + prevVersionTag + ")");
                    System.out.println("Querying GitHub API for GraalVM release " + versionTag + "...");
                    final GHRepository graalRepo = github.getRepository("graalvm/graalvm-ce-builds");
                    final GHRelease release = graalRepo.getReleaseByTagName(versionTag);
                    if (release == null) {
                        System.err.println("WARNING: Could not find GitHub release for tag " + versionTag);
                        continue;
                    }
                    // Extract JDK version supporting 5+ digits (e.g., 25.0.5.0.3)
                    final String newJdkVersion = SuiteOpsUtils.extractJdkVersionFromReleaseName(release.getName());
                    if (newJdkVersion == null) {
                        throw new RuntimeException("ABORT: Could not extract JDK version from GraalVM release name: " + release.getName());
                    }
                    extractedJdkVersionsForTitle.add(newJdkVersion);
                    System.out.println("Extracted JDK version: " + newJdkVersion);
                    String amdSha = null;
                    String armSha = null;
                    String extractedGraalvmVersion = null;
                    for (final GHAsset asset : release.getAssets()) {
                        final String name = asset.getName();
                        if (name.endsWith("linux-x64_bin.tar.gz.sha256")) {
                            if (!name.contains("-" + newJdkVersion + "_")) {
                                System.err.println("WARNING: GraalVM artifact name does not contain the expected JDK version '" + newJdkVersion + "': " + name);
                            }
                            final Matcher gvMatcher = Pattern.compile("jdk-([^-]+)-").matcher(name);
                            if (gvMatcher.find()) {
                                extractedGraalvmVersion = gvMatcher.group(1);
                            }
                            final File downloaded = SuiteOpsUtils.downloadIfNotExists(asset.getBrowserDownloadUrl(), downloadDir);
                            amdSha = Files.readString(downloaded.toPath()).trim().split("\\s+")[0];
                        } else if (name.endsWith("linux-aarch64_bin.tar.gz.sha256")) {
                            if (!name.contains("-" + newJdkVersion + "_")) {
                                System.err.println("WARNING: GraalVM artifact name does not contain the expected JDK version '" + newJdkVersion + "': " + name);
                            }
                            final File downloaded = SuiteOpsUtils.downloadIfNotExists(asset.getBrowserDownloadUrl(), downloadDir);
                            armSha = Files.readString(downloaded.toPath()).trim().split("\\s+")[0];
                        }
                    }
                    if (amdSha == null || armSha == null) {
                        throw new RuntimeException("ABORT: Could not find sha256 assets for linux-x64 or linux-aarch64 in GraalVM release.");
                    }
                    if (extractedGraalvmVersion == null) {
                        System.err.println("WARNING: Could not extract graalvm-version (e.g. 25i3) from artifact name.");
                    }
                    System.out.println("Loaded amd64 sha256: " + amdSha);
                    System.out.println("Loaded aarch64 sha256: " + armSha);
                    final String prevVersionTagClean = SuiteOpsUtils.stripVersionPrefix(prevVersionTag, "graal-");
                    final String versionTagClean = SuiteOpsUtils.stripVersionPrefix(versionTag, "graal-");
                    final List<File> targetYamlFiles = List.of(
                            new File(repoDir, "quarkus-native-s2i/graalvm.yaml"),
                            new File(repoDir, "quarkus-graalvm-builder-image/graalvm.yaml")
                    );
                    for (final File yamlFile : targetYamlFiles) {
                        if (!yamlFile.exists()) {
                            System.err.println("WARNING: Could not find " + yamlFile.getAbsolutePath());
                            continue;
                        }
                        final List<String> yLines = Files.readAllLines(yamlFile.toPath(), StandardCharsets.UTF_8);
                        int blockStartIndex = -1;
                        for (int i = 0; i < yLines.size(); i++) {
                            final String line = yLines.get(i);
                            if (line.contains("releases/tag/" + prevVersionTag)) {
                                yLines.set(i, line.replace(prevVersionTag, versionTag));
                            }
                            if (line.contains("- graalvm-version:")) {
                                blockStartIndex = i;
                            }
                            if (line.contains("graalvm-release-tag: " + prevVersionTag)) {
                                if (blockStartIndex != -1 && extractedGraalvmVersion != null) {
                                    final String gvLine = yLines.get(blockStartIndex);
                                    yLines.set(blockStartIndex, gvLine.replaceFirst("graalvm-version: .*", "graalvm-version: " + extractedGraalvmVersion));
                                }
                                yLines.set(i, line.replaceFirst("graalvm-release-tag: .*", "graalvm-release-tag: " + versionTag));
                                for (int k = i + 1; k < yLines.size(); k++) {
                                    final String innerLine = yLines.get(k);
                                    if (innerLine.contains("- graalvm-version:")) {
                                        break;
                                    }
                                    if (innerLine.contains("java-version:")) {
                                        yLines.set(k, innerLine.replaceFirst("java-version: .*", "java-version: " + newJdkVersion));
                                    } else if (innerLine.contains("tags:")) {
                                        yLines.set(k, innerLine.replace(prevVersionTagClean, versionTagClean));
                                    } else if (innerLine.contains("sha:")) {
                                        if (k - 1 >= 0 && yLines.get(k - 1).contains("arch: amd64") && amdSha != null) {
                                            yLines.set(k, innerLine.replaceFirst("sha: .*", "sha: " + amdSha));
                                        } else if (k - 1 >= 0 && yLines.get(k - 1).contains("arch: arm64") && armSha != null) {
                                            yLines.set(k, innerLine.replaceFirst("sha: .*", "sha: " + armSha));
                                        }
                                    }
                                }
                            }
                        }
                        Files.write(yamlFile.toPath(), yLines, StandardCharsets.UTF_8);
                        git.add().addFilepattern(repoDir.toURI().relativize(yamlFile.toURI()).getPath()).call();
                    }
                    System.out.println("GraalVM YAMLs updated successfully.");
                    continue;
                }
                // Mandrel Logic
                final List<String> lines = Files.readAllLines(mandrelYamlFile.toPath(), StandardCharsets.UTF_8);
                final String fullVersion = SuiteOpsUtils.stripVersionPrefix(versionTag, "mandrel-");
                final String prevFullVersion = SuiteOpsUtils.stripVersionPrefix(prevVersionTag, "mandrel-");
                final String releaseName = (cspu && !fullVersion.contains("-Final")) ? "Mandrel " + fullVersion + " CSPU" : "Mandrel " + fullVersion;
                System.out.println("\nProcessing updates for Mandrel " + versionTag + " (replacing " + prevVersionTag + ")");
                System.out.println("Querying GitHub API for release " + versionTag + "...");
                GHRelease release = mandrelRepo.getReleaseByTagName(versionTag);
                if (release == null) {
                    for (GHRelease r : mandrelRepo.listReleases()) {
                        if (releaseName.equals(r.getName())) {
                            release = r;
                            break;
                        }
                    }
                }
                if (release == null) {
                    System.err.println("WARNING: Could not find GitHub release for tag " + versionTag + ". " +
                            "It likely means it's still a DRAFT. Script can continue, but Quarkus Images CI would fail, " +
                            "so you should release the draft now.");
                } else {
                    // Extract JDK version supporting 5+ digits (e.g., 21.0.12, 25.0.5.0.3)
                    final String newJdkVersion = SuiteOpsUtils.extractJdkVersionFromReleaseName(release.getBody());
                    if (newJdkVersion == null) {
                        throw new RuntimeException("ABORT: Could not extract JDK version from release body of " + versionTag);
                    }
                    extractedJdkVersionsForTitle.add(newJdkVersion);
                    System.out.println("Extracted JDK version: " + newJdkVersion);
                    final String jdkMajor = newJdkVersion.split("\\.")[0];
                    final String amdSha = getSha(downloadDir, jdkMajor, fullVersion, "amd64");
                    final String armSha = getSha(downloadDir, jdkMajor, fullVersion, "aarch64");
                    System.out.println("Loaded amd64 sha256: " + amdSha);
                    System.out.println("Loaded aarch64 sha256: " + armSha);
                    // YAML
                    boolean inBlock = false;
                    for (int i = 0; i < lines.size(); i++) {
                        final String line = lines.get(i);
                        if (line.contains("releases/tag/" + prevVersionTag)) {
                            lines.set(i, line.replace(prevVersionTag, versionTag));
                        }
                        if (line.contains("mandrel-" + prevFullVersion)) {
                            lines.set(i, line.replace(prevFullVersion, fullVersion));
                        } else if (line.contains("graalvm-version: " + prevFullVersion)) {
                            lines.set(i, line.replace(prevFullVersion, fullVersion));
                            inBlock = true;
                        } else if (inBlock) {
                            if (line.contains("tags:")) {
                                lines.set(i, line.replaceAll("jdk-\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?", "jdk-" + newJdkVersion));
                            } else if (line.contains("sha:")) {
                                if (i + 1 < lines.size() && lines.get(i + 1).contains("arch: amd64")) {
                                    lines.set(i, line.replaceFirst("sha: [a-fA-F0-9]+", "sha: " + amdSha));
                                } else if (i + 1 < lines.size() && lines.get(i + 1).contains("arch: arm64")) {
                                    lines.set(i, line.replaceFirst("sha: [a-fA-F0-9]+", "sha: " + armSha));
                                    inBlock = false; //there are only two archs, end of block
                                }
                            }
                        }
                    }
                }
                Files.write(mandrelYamlFile.toPath(), lines, StandardCharsets.UTF_8);
                System.out.println("\nMandrel YAML updated successfully.");
                git.add().addFilepattern("quarkus-mandrel-builder-image/mandrel.yaml").call();
            }

            // build title
            final List<String> uniqueJdks = new ArrayList<>(new HashSet<>(extractedJdkVersionsForTitle));
            uniqueJdks.sort(Collections.reverseOrder());
            final String prTitle = month + " " + Year.now().getValue() + " " + (cspu ? "CSPU" : "CPU") + ", JDK " + String.join(", ", uniqueJdks);
            final String prBody = "SSIA";
            if (!git.status().call().hasUncommittedChanges()) {
                System.out.println("WARN: No changes to commit. YAMLs already updated.");
            } else {
                git.commit().setAll(true).setMessage(prTitle).setSign(true).call();
            }
            if (!dryRun) {
                final GHRepository targetRepo = github.getRepository(upstreamRepo);
                boolean prExists = false;
                for (GHPullRequest existingPr : targetRepo.getPullRequests(GHIssueState.OPEN)) {
                    if (existingPr.getHead().getRef().equals(branchName)) {
                        prExists = true;
                        System.out.println("WARN: PR already exists: " + existingPr.getHtmlUrl());
                        break;
                    }
                }
                if (!prExists) {
                    SuiteOpsUtils.pushToFork(git, forkName, branchName);
                    final String head = forkName.split("/")[0] + ":" + branchName;
                    System.out.println("Opening PR on " + upstreamRepo + "...");
                    final GHPullRequest pr = targetRepo.createPullRequest(prTitle, head, baseBranch, prBody, true, false);
                    if (!testRun) {
                        SuiteOpsUtils.setupPRReviewers(github, pr, true);
                    }
                    System.out.println("PR created: " + pr.getHtmlUrl());
                }
            } else {
                System.out.println("[DRY RUN] Would push branch and create PR with title: " + prTitle);
            }
        }
        return 0;
    }

    private String getSha(File dir, String jdkMajor, String fullVersion, String arch) throws Exception {
        final String filename = "mandrel-java" + jdkMajor + "-linux-" + arch + "-" + fullVersion + ".tar.gz.sha256";
        final File shaFile = new File(dir, filename);
        if (!shaFile.exists()) {
            throw new RuntimeException("ABORT: Expected SHA file missing: " + shaFile.getAbsolutePath());
        }
        return Files.readString(shaFile.toPath()).trim().split("\\s+")[0];
    }
}

/**
 * STEP 7 & 8: SYNC UPSTREAM
 * See README.md
 */
@Command(name = "sync-upstream", description = "Merges upstream branch into downstream and opens a Sync PR")
class SyncUpstream implements Callable<Integer> {
    @Option(names = { "-d", "--dir" }, defaultValue = "./")
    File repoDir;
    @Option(names = { "-f", "--fork" }, required = true)
    String forkName;
    @Option(names = { "-g", "--git-remote-name" }, defaultValue = "origin")
    String repoRemoteName;
    @Option(names = { "-b", "--base-branch" }, required = true)
    String baseBranch;
    @Option(names = { "-r", "--repo" }, required = true)
    String downstreamRepo;
    @Option(names = { "-U", "--upstream-url" }, required = true)
    String upstreamUrl;
    @Option(names = { "-B", "--upstream-branch" }, defaultValue = "master")
    String upstreamBranch;
    @Option(names = { "-n", "--next-version" }, description = "Downstream version to enforce in suite.py (e.g. 23.1.12.0)")
    String nextVersion;
    @Option(names = { "-S", "--since" }, description = "Commit SHA or tag to start PR parsing from (default: auto-calculated merge base)")
    String sinceCommit;
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;
    @Option(names = { "--test-run" })
    boolean testRun;

    @Override
    public Integer call() throws Exception {
        SuiteOpsUtils.setup();
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            git.pull().setRemote(repoRemoteName).setRemoteBranchName(baseBranch).call();
            final String date = java.time.LocalDate.now().toString();
            final String upstreamRepoName = upstreamUrl.substring(upstreamUrl.lastIndexOf('/') + 1).replace(".git", "");
            final String prTitle =
                    String.format("Merge upstream %s/%s into %s (%s)", upstreamRepoName, upstreamBranch, baseBranch, date);
            final String workBranch = "sync-upstream-" + System.currentTimeMillis();
            git.checkout().setCreateBranch(true).setName(workBranch).setStartPoint(baseBranch).call();
            System.out.println("Fetching " + upstreamBranch + " from " + upstreamUrl);
            git.fetch().setRemote(upstreamUrl)
                    .setRefSpecs(new RefSpec("+refs/heads/" + upstreamBranch + ":refs/remotes/TEMP_UPSTREAM")).call();
            final ObjectId fetchHead = git.getRepository().resolve("refs/remotes/TEMP_UPSTREAM");
            final ObjectId localHead = git.getRepository().resolve("HEAD");
            final ObjectId sinceId;
            if (sinceCommit != null) {
                if ((sinceId = git.getRepository().resolve(sinceCommit)) == null) {
                    throw new RuntimeException("ABORT: Could not resolve --since commit or tag: " + sinceCommit);
                }
                System.out.println("Using explicitly provided --since commit: " + sinceId.getName());
            } else {
                System.out.println("No --since provided. Calculating the merge base between downstream and upstream...");
                try (RevWalk walk = new RevWalk(git.getRepository())) {
                    final RevCommit headCommit = walk.parseCommit(localHead);
                    final RevCommit upstreamCommit = walk.parseCommit(fetchHead);
                    walk.setRevFilter(RevFilter.MERGE_BASE);
                    walk.markStart(headCommit);
                    walk.markStart(upstreamCommit);
                    final RevCommit mergeBase = walk.next();
                    if (mergeBase != null) {
                        sinceId = mergeBase.getId();
                        System.out.println("Calculated merge base: " + mergeBase.getName() + " (" + mergeBase.getShortMessage() + ")");
                        // check on downstream commits since the merge base
                        final Iterable<RevCommit> downstreamCommits = git.log().add(localHead).call();
                        for (RevCommit c : downstreamCommits) {
                            if (c.getId().equals(sinceId)) {
                                break;
                            }
                            final String msg = c.getFullMessage().toLowerCase();
                            if (msg.contains("squash merge") || msg.contains("squashed commit of")) {
                                throw new RuntimeException("ABORT: There seems to be squash commits in history (" + c.abbreviate(7).name() + "). Please provide concrete --since manually.");
                            }
                            if (msg.contains("cherry-pick") || msg.contains("cherry picked from")) {
                                throw new RuntimeException("ABORT: There seems to be cherry-picked commits in history (" + c.abbreviate(7).name() + "). Please provide concrete --since manually.");
                            }
                        }
                    } else {
                        throw new RuntimeException("ABORT: No common ancestor found between downstream and upstream. You must provide --since explicitly.");
                    }
                }
            }
            final StringBuilder prList = new StringBuilder();
            final Iterable<RevCommit> commits = git.log().addRange(sinceId, fetchHead).call();
            // matches both standard merges, e.g. "Merge pull request oracle#278" and squash merges e.g. "... (#278)"
            final Pattern prPattern = Pattern.compile("(?:Merge pull request .*?#|\\(#)(\\d+)");
            for (RevCommit c : commits) {
                final String shortMsg = c.getShortMessage();
                final Matcher m = prPattern.matcher(shortMsg);
                if (m.find()) {
                    prList.append("* ").append(upstreamUrl.replace(".git", "")).append("/pull/").append(m.group(1))
                            .append("\n");
                }
            }
            System.out.println("Merging upstream branch into current branch.");
            final String effectiveVersion = nextVersion != null ? nextVersion : SuiteOpsUtils.getCurrentVersion(repoDir);
            final MergeResult mergeResult = git.merge().include(fetchHead).setCommit(false).call();
            if (!mergeResult.getMergeStatus().isSuccessful() && mergeResult.getMergeStatus() != MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                if (mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    for (String conflictingPath : mergeResult.getConflicts().keySet()) {
                        if (!conflictingPath.endsWith("suite.py")) {
                            throw new RuntimeException(
                                    "Unexpected conflict: " + conflictingPath + ". Please resolve manually.");
                        }
                        SuiteOpsUtils.resolveSuiteConflict(new File(repoDir, conflictingPath), effectiveVersion, false);
                        git.add().addFilepattern(conflictingPath).call();
                    }
                } else {
                    throw new RuntimeException("Merge failed: " + mergeResult.getMergeStatus());
                }
            }
            if (nextVersion != null) {
                SuiteOpsUtils.modifySuitesReleaseState(repoDir, false, nextVersion);
                git.add().addFilepattern(".").call();
            }
            final String commitMessage = prTitle + "\n\nThis is a merge from upstream which includes the following upstream changes:\n" + prList;
            if (!git.status().call().hasUncommittedChanges()) {
                System.out.println("WARN: No changes to commit. Upstream sync already merged.");
            } else {
                git.commit().setAll(true).setMessage(commitMessage).setSign(true).call();
            }
            if (!dryRun) {
                final GitHub github = SuiteOpsUtils.connectToGitHub();
                final GHRepository repo = github.getRepository(downstreamRepo);
                boolean prExists = false;
                for (GHPullRequest existingPr : repo.getPullRequests(GHIssueState.OPEN)) {
                    if (existingPr.getHead().getRef().equals(workBranch)) {
                        prExists = true;
                        System.out.println("WARN: PR already exists: " + existingPr.getHtmlUrl());
                        break;
                    }
                }
                if (!prExists) {
                    SuiteOpsUtils.pushToFork(git, forkName, workBranch);
                    final String head = forkName.split("/")[0] + ":" + workBranch;
                    final GHPullRequest pr = repo.createPullRequest(prTitle, head, baseBranch, commitMessage, true, false);
                    final GHMilestone milestone = SuiteOpsUtils.inferOpenMilestone(repo, repoDir, nextVersion);
                    if (milestone != null) {
                        System.out.println("Assigning Sync PR to milestone: " + milestone.getTitle());
                        pr.setMilestone(milestone);
                    }
                    if (!testRun) {
                        SuiteOpsUtils.setupPRReviewers(github, pr, false);
                    }
                    System.out.println("PR created: " + pr.getHtmlUrl());
                }
            } else {
                System.out.println("[DRY RUN] Would push branch and create PR with title: " + prTitle);
            }
        }
        return 0;
    }
}
