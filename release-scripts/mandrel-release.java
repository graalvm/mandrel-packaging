//usr/bin/env jbang --ea "$0" "$@" ; exit $?
//JAVA 17+
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.13.0.202109080827-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.pgm:5.13.0.202109080827-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.13.0.202109080827-r
//DEPS info.picocli:picocli:4.5.0
//DEPS org.kohsuke:github-api:1.316

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.console.ConsoleCredentialsProvider;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHMilestone;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTag;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.PagedIterable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "mandrel-release", mixinStandardHelpOptions = true,
    subcommands = {Prepare.class, Release.class},
    description = "Script automating part of the Mandrel release process")
class MandrelRelease
{
    public static void main(String... args)
    {
        int exitCode = new CommandLine(new MandrelRelease()).execute(args);
        System.exit(exitCode);
    }
}

@Command()
class ReusableOptions
{
    @CommandLine.Option(names = {"-m", "--mandrel-repo"},
        description = "The path to the mandrel repository",
        defaultValue = "./")
    String mandrelRepo;

    @CommandLine.Option(names = {"-s", "--suffix"},
        description = "The release suffix, e.g, Final, Alpha2, Beta1, etc. (default: \"${DEFAULT-VALUE}\")",
        defaultValue = "Final")
    String suffix;

    @CommandLine.Option(names = {"-f", "--fork-name"},
        description = "The repository name of the github fork to push the changes to (default: \"${DEFAULT-VALUE}\")",
        defaultValue = "zakkak/mandrel")
    String forkName;

    @CommandLine.Option(names = {"-S", "--sign-commits"},
        description = "Sign commits")
    boolean signCommits;

    @CommandLine.Option(names = {"-D", "--dry-run"},
        description = "Perform a dry run (no remote pushes and PRs)")
    boolean dryRun;

    @CommandLine.Option(names = {"--verbose"},
        description = "Prints verbose debug info")
    boolean verbose;

    void checkOptions()
    {
        if (!suffix.equals("Final") && !Pattern.compile("^(Alpha|Beta)\\d*$").matcher(suffix).find())
        {
            Log.error("Invalid version suffix : " + suffix);
        }

        if (!Path.of(mandrelRepo).toFile().exists())
        {
            Log.error("Path " + mandrelRepo + " does not exist");
        }
    }
}

class MandrelVersion implements Comparable<MandrelVersion>
{
    final static String MANDREL_VERSION_REGEX = "(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)(-(Final|(Alpha|Beta)\\d*))?";

    int major;
    int minor;
    int micro;
    int pico;
    String suffix;

    public MandrelVersion(MandrelVersion mandrelVersion)
    {
        this.major = mandrelVersion.major;
        this.minor = mandrelVersion.minor;
        this.micro = mandrelVersion.micro;
        this.pico = mandrelVersion.pico;
        this.suffix = mandrelVersion.suffix;
    }

    public MandrelVersion(String version)
    {
        final Pattern versionPattern = Pattern.compile(MandrelVersion.MANDREL_VERSION_REGEX);
        final Matcher versionMatcher = versionPattern.matcher(version);
        boolean found = versionMatcher.find();
        if (!found)
        {
            Log.error("Wrong version format! " + version + " does not match pattern: " + MandrelVersion.MANDREL_VERSION_REGEX);
        }
        major = Integer.parseInt(versionMatcher.group(1));
        minor = Integer.parseInt(versionMatcher.group(2));
        micro = Integer.parseInt(versionMatcher.group(3));
        pico = Integer.parseInt(versionMatcher.group(4));
        suffix = versionMatcher.group(6);
    }

    /**
     * Returns current version of substratevm
     *
     * @return
     */
    static MandrelVersion ofRepository(String mandrelRepo)
    {
        final Path substrateSuite = Path.of(mandrelRepo, "substratevm", "mx.substratevm", "suite.py");
        try
        {
            final List<String> lines = Files.readAllLines(substrateSuite);
            final Pattern versionPattern = Pattern.compile("\"version\" : \"([\\d.]+)\"");
            for (String line : lines)
            {
                final Matcher versionMatcher = versionPattern.matcher(line);
                if (versionMatcher.find())
                {
                    final String version = versionMatcher.group(1);
                    return new MandrelVersion(version);
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
        return null;
    }

    /**
     * Calculates the new version by bumping pico in major.minor.micro.pico
     *
     * @return The new version
     */
    MandrelVersion getNewVersion()
    {
        final MandrelVersion mandrelVersion = new MandrelVersion(this);
        mandrelVersion.pico++;
        return mandrelVersion;
    }

    String majorMinor()
    {
        return major + "." + minor;
    }

    String majorMinorMicro()
    {
        return major + "." + minor + "." + micro;
    }

    String majorMinorMicroPico()
    {
        return major + "." + minor + "." + micro + "." + pico;
    }

    boolean isFinal()
    {
        return suffix.equals("Final");
    }

    @Override
    public String toString()
    {
        String version = majorMinorMicroPico();
        if (suffix != null)
        {
            version += "-" + suffix;
        }
        return version;
    }

    @Override
    public int compareTo(MandrelVersion o)
    {
        assert suffix.equals(o.suffix);
        if (major > o.major)
        {
            return 1;
        }
        else if (major == o.major)
        {
            if (minor > o.minor)
            {
                return 1;
            }
            else if (minor == o.minor)
            {
                if (micro > o.micro)
                {
                    return 1;
                }
                else if (micro == o.micro)
                {
                    if (pico > o.pico)
                    {
                        return 1;
                    }
                    else if (pico == o.pico)
                    {
                        return 0;
                    }
                }
            }
        }
        return -1;
    }
}

class GitHubOps
{
    private static final int ARTIFACTS_PER_JDK_VERSION = 3;
    private final MandrelVersion version;
    private final boolean dryRun;
    private final String downloadDir;
    private final boolean verbose;

    GitHubOps(MandrelVersion version, boolean dryRun, String downloadDir, boolean verbose)
    {
        this.version = version;
        this.dryRun = dryRun;
        this.downloadDir = downloadDir;
        this.verbose = verbose;
    }

    void createGHRelease(Set<String> jdkVersionsUsed, boolean linuxUpload, boolean windowsUpload, boolean macUpload)
    {
        final GitHub github = connectToGitHub();
        try
        {
            final GHRepository repository = github.getRepository(GitOps.REPOSITORY_NAME);
            Log.debug("Getting all open milestones", verbose);
            final PagedIterable<GHMilestone> ghMilestones = repository.listMilestones(GHIssueState.OPEN);
            final String finalVersion = version.majorMinorMicroPico() + "-Final";

            List<GHMilestone> milestonesList = ghMilestones.toList();
            Log.debug("Got " + milestonesList.size() + " milestones", verbose);
            GHMilestone milestone = null;

            for (GHMilestone i : milestonesList)
            {
                if (i.getTitle().equals(finalVersion))
                {
                    milestone = i;
                    break;
                }
            }

            if (milestone == null)
            {
                Log.error("No milestone titled " + version.majorMinorMicroPico() + "-Final! Can't produce changelog without it!");
            }
            else
            {
                Log.debug("Found milestone " + milestone.getNumber(), verbose);
                if (version.isFinal() && milestone.getOpenIssues() != 0)
                {
                    Log.error("There are still open issues in milestone " + milestone.getTitle() + ". Please take care of them and try again.");
                }
            }

            final List<GHTag> tags = repository.listTags().toList();

            // Ensure that the tag exists
            final String tag = "mandrel-" + version;
            if (tags.stream().noneMatch(x -> x.getName().equals(tag)))
            {
                Log.error("Please create tag " + tag + " and try again");
            }

            /*
             * There used to be more JDK versions per 1 Mandrel release, e.g. Mandrel 21.3 could work with both
             * Java 11 and 17. We aim at aligning releases with Java releases now, and we could simplify this code as
             * soon as we completely get rid of all Mandrel 22.3.
             * Mandrel 25 for JDK 25 will conclude the renaming.
             */
            final List<String> jdkVersions = jdkVersionsUsed.stream().filter(v -> v.length() > 2).sorted().toList();
            final String changelog = createChangelog(repository, milestone, tags, jdkVersions.size() == 1 ? jdkVersions.get(0) : null);
            // e.g. deals with "23+37" too. Things like 24-beta+16-ea should not happen in a release script.
            final String jdkVersionForExampleURLs = jdkVersions.get(0).split("[+.]")[0].trim();

            if (dryRun)
            {
                Log.warn("Skipping release due to --dry-run");
                Log.info("Release body would look like");
                System.out.println(releaseMainBody(changelog, jdkVersionsUsed, jdkVersionForExampleURLs));

                assets(version.toString(), jdkVersionsUsed, linuxUpload, windowsUpload, macUpload).forEach(f -> Log.info("Would upload " + f.getName()));
                return;
            }

            manageMilestones(repository, milestonesList, milestone);

            final GHRelease ghRelease = repository.createRelease(tag)
                .name("Mandrel " + version)
                .prerelease(!version.isFinal())
                .body(releaseMainBody(changelog, jdkVersionsUsed, jdkVersionForExampleURLs))
                .draft(true)
                .create();

            uploadAssets(version.toString(), ghRelease, jdkVersionsUsed, linuxUpload, windowsUpload, macUpload);
            Log.info("Created new draft release: " + ghRelease.getHtmlUrl());
            Log.info("Please review and publish!");
        }
        catch (IOException e)
        {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
    }

    private List<File> assets(String fullVersion, Set<String> jdkVersionsUsed, boolean linuxUpload, boolean windowsUpload, boolean macUpload)
    {
        if (jdkVersionsUsed.isEmpty())
        {
            return Collections.emptyList();
        }

        final List<File> assets = new ArrayList<>(jdkVersionsUsed.size() * ARTIFACTS_PER_JDK_VERSION);

        jdkVersionsUsed.forEach(jdkVersion ->
        {
            final String jdkMajor = jdkVersion.split("[+.]")[0];
            if (jdkMajor.isBlank())
            {
                Log.error("JDK version must not be empty. We need it for `mandrel-javaMAJOR...' artifacts names.");
            }
            if (linuxUpload)
            {
                assets.add(new File(downloadDir, "mandrel-java" + jdkMajor + "-linux-amd64-" + fullVersion + ".tar.gz"));
                assets.add(new File(downloadDir, "mandrel-java" + jdkMajor + "-linux-aarch64-" + fullVersion + ".tar.gz"));
            }
            if (windowsUpload)
            {
                assets.add(new File(downloadDir, "mandrel-java" + jdkMajor + "-windows-amd64-" + fullVersion + ".zip"));
            }
            if (macUpload)
            {
                assets.add(new File(downloadDir, "mandrel-java" + jdkMajor + "-macos-aarch64-" + fullVersion + ".tar.gz"));
            }
        });

        return assets;
    }

    private void uploadAssets(String fullVersion, GHRelease ghRelease, Set<String> jdkVersionsUsed, boolean linuxUpload, boolean windowsUpload, boolean macUpload) throws IOException
    {
        final List<File> assets = assets(fullVersion, jdkVersionsUsed, linuxUpload, windowsUpload, macUpload);

        for (File f : assets)
        {
            if (!f.exists())
            {
                Log.warn("Archive \"" + f.getName() + "\" was not found. Skipping asset upload.");
                Log.warn("Please upload assets manually.");
                return;
            }
        }

        for (File a : assets)
        {
            final File[] files = new File[]{
                a,
                new File(a.getParent(), a.getName() + ".sha1"),
                new File(a.getParent(), a.getName() + ".sha256")
            };
            for (File f : files)
            {
                Log.info("Uploading " + f.getName());
                if (f.getName().endsWith("tar.gz"))
                {
                    ghRelease.uploadAsset(f, "application/gzip");
                }
                else if (f.getName().endsWith("zip"))
                {
                    ghRelease.uploadAsset(f, "application/zip");
                }
                else
                {
                    ghRelease.uploadAsset(f, "text/plain");
                }
                Log.info("Uploaded " + f.getName());
            }
        }
    }

    private String releaseMainBody(String changelog, Set<String> jdkVersionsUsed, String jdkMajorVersionExample)
    {
        final int jdkMajorVersion = Integer.parseInt(jdkMajorVersionExample);
        final String codeWithQuarkusURL;
        if (jdkMajorVersion > 17)
        {
            codeWithQuarkusURL = "https://code.quarkus.io/d?e=rest&cn=code.quarkus.io";
        }
        else
        {
            codeWithQuarkusURL = "https://code.quarkus.io/d?j=17&e=resteasy-reactive&S=io.quarkus.platform%3A3.2&cn=code.quarkus.io";
        }


        return "# Mandrel\n" +
            "\n" +
            "Mandrel " + version + " is a downstream distribution of the GraalVM community edition.\n" +
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
            "Mandrel is also built slightly differently to GraalVM, using the standard OpenJDK project release of jdk " + String.join(" and ", jdkVersionsUsed) + ".\n" +
            "This means it does not profit from a few small enhancements that Oracle have added to the version of OpenJDK used to build their own GraalVM downloads.\n" +
            "Most of these enhancements are to the JVMCI module that allows the Graal compiler to be run inside OpenJDK.\n" +
            "The others are small cosmetic changes to behaviour.\n" +
            "These enhancements may in some cases cause minor differences in the progress of native image generation.\n" +
            "They should not cause the resulting images themselves to execute in a noticeably different manner.\n" +
            "\n" +
            "### Prerequisites\n" +
            "\n" +
            "Mandrel's `native-image` depends on the following packages:\n" +
            "* freetype-devel\n" +
            "* gcc\n" +
            "* glibc-devel\n" +
            "* libstdc++-static\n" +
            "* zlib-devel\n" +
            "\n" +
            "On Fedora/CentOS/RHEL they can be installed with:\n" +
            "```bash\n" +
            "dnf install glibc-devel zlib-devel gcc freetype-devel libstdc++-static\n" +
            "```\n" +
            "\n" +
            "**Note**: The package might be called `glibc-static` or `libstdc++-devel` instead of `libstdc++-static` depending on your system.\n" +
            "If the system is missing stdc++, `gcc-c++` package is needed too.\n" +
            "\n" +
            "On Ubuntu-like systems with:\n" +
            "```bash\n" +
            "apt install g++ zlib1g-dev libfreetype6-dev\n" +
            "```\n" +
            "\n" +
            "## Quick start Linux/MacOS\n" +
            "Mac users: \n" +
            "  * Use artifact mandrel-java" + jdkMajorVersionExample + "-macos-aarch64-" + version + ".tar.gz\n" +
            "  * Use JAVA_HOME=\"$( pwd )/mandrel-java" + jdkMajorVersionExample + "-" + version + "/Contents/Home\"\n" +
            "  * Use `xattr -c -r ./path/to/mandrel` to prevent quarantine\n" +
            "\n" +
            "```\n" +
            "$ curl -O -J -L \"https://github.com/graalvm/mandrel/releases/download/mandrel-" + version + "/mandrel-java" + jdkMajorVersionExample + "-linux-amd64-" + version + ".tar.gz\"\n" +
            "$ tar -xf mandrel-java" + jdkMajorVersionExample + "-linux-amd64-" + version + ".tar.gz\n" +
            "$ export JAVA_HOME=\"$( pwd )/mandrel-java" + jdkMajorVersionExample + "-" + version + "\"\n" +
            "$ export GRAALVM_HOME=\"${JAVA_HOME}\"\n" +
            "$ export PATH=\"${JAVA_HOME}/bin:${PATH}\"\n" +
            "$ curl -O -J \"" + codeWithQuarkusURL + "\"\n" +
            "$ unzip code-with-quarkus.zip\n" +
            "$ cd code-with-quarkus/\n" +
            "$ ./mvnw package -Pnative\n" +
            "$ ./target/code-with-quarkus-1.0.0-SNAPSHOT-runner\n" +
            "```\n" +
            "\n" +
            "## Quick start Windows\n" +
            "Note that `vcvars64` command is usually located in your VS installation and you should add it to your PATH, " +
            "\ne.g. `C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\VC\\Auxiliary\\Build`.\n" +
            "\n" +
            "```\n" +
            "powershell -c \"Start-BitsTransfer -Source 'https://github.com/graalvm/mandrel/releases/download/mandrel-" + version + "/mandrel-java" + jdkMajorVersionExample + "-windows-amd64-" + version + ".zip'\"\n" +
            "powershell -c \"Expand-Archive -Path mandrel-java" + jdkMajorVersionExample + "-windows-amd64-" + version + ".zip -DestinationPath . -Force\"\n" +
            "SET JAVA_HOME=%CD%\\mandrel-java" + jdkMajorVersionExample + "-" + version + "\n" +
            "SET GRAALVM_HOME=%JAVA_HOME%\n" +
            "SET PATH=%JAVA_HOME%\\bin;%PATH%\n" +
            "vcvars64\n" +
            "powershell -Command \"Invoke-WebRequest -Uri '" + codeWithQuarkusURL + "' -OutFile 'code-with-quarkus.zip'\"\n" +
            "powershell -c \"Expand-Archive -Path code-with-quarkus.zip -DestinationPath . -Force\"\n" +
            "cd code-with-quarkus\n" +
            "mvnw package -Pnative\n" +
            "target\\code-with-quarkus-1.0.0-SNAPSHOT-runner\n" +
            "```\n" +
            "\n" +
            "### Quarkus builder image\n" +
            "\n" +
            "The Quarkus builder image for this release is still being prepared, please try again later.\n" +
            "<!--\n" +
            "Mandrel Quarkus builder image can be used to build a Quarkus native Linux executable right away without any GRAALVM_HOME setup.\n" +
            "\n" +
            "```bash\n" +
            "curl -O -J " + codeWithQuarkusURL + "\n" +
            "unzip code-with-quarkus.zip\n" +
            "cd code-with-quarkus\n" +
            "./mvnw package -Pnative -Dquarkus.native.container-build=true \\\n" +
            "    -Dquarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-" + jdkMajorVersionExample + " \\\n" +
            "./target/code-with-quarkus-1.0.0-SNAPSHOT-runner\n" +
            "```\n" +
            "\n" +
            "One can use the builder image on Windows with e.g. Podman Desktop, see [Podman For Windows](https://quarkus.io/blog/podman-for-windows/).\n" +
            "\n" +
            "```batchfile\n" +
            "powershell -c \"Invoke-WebRequest -OutFile quarkus.zip -Uri " + codeWithQuarkusURL + "\"\n" +
            "powershell -c \"Expand-Archive -Path quarkus.zip -DestinationPath . -Force\n" +
            "cd code-with-quarkus\n" +
            "mvnw package -Pnative -Dquarkus.native.container-build=true -Dquarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-" + jdkMajorVersionExample + "\n" +
            "podman build -f src/main/docker/Dockerfile.native -t my-quarkus-mandrel-app .\n" +
            "podman run -i --rm -p 8080:8080 my-quarkus-mandrel-app\n" +
            "```\n" +
            "-->\n" +
            changelog +
            "\n---\n" +
            "Mandrel " + version + "\n" +
            "OpenJDK" + (jdkVersionsUsed.size() > 1 ? "s" : "") + " used: " + String.join(", ", jdkVersionsUsed) + "\n";
    }

    private String createChangelog(GHRepository repository, GHMilestone milestone, List<GHTag> tags, String jdkVersionUsed) throws IOException
    {
        assert milestone != null;
        Log.info("Getting merged PRs for " + milestone.getTitle() + " (" + milestone.getNumber() + ")");
        final Stream<GHPullRequest> mergedPRsInMilestone = repository.getPullRequests(GHIssueState.CLOSED).stream()
            .filter(pr -> includeInChangelog(pr, milestone));
        final Map<Integer, List<GHPullRequest>> collect = mergedPRsInMilestone.collect(Collectors.groupingBy(this::getGroup));
        StringBuilder changelogBuilder = new StringBuilder("\n### Changelog\n\n");
        final String latestReleasedTag = getLatestReleasedTag(tags, jdkVersionUsed);
        final List<GHPullRequest> noteworthyPRs = collect.get(0);
        if (noteworthyPRs != null && noteworthyPRs.size() != 0)
        {
            noteworthyPRs.forEach(pr ->
                changelogBuilder.append(" * #").append(pr.getNumber()).append(" - ").append(pr.getTitle()).append("\n"));
        }
        final List<GHPullRequest> backportPRs = collect.get(1);
        if (backportPRs != null && backportPRs.size() != 0)
        {
            changelogBuilder.append("\n#### Backports\n\n");
            backportPRs.forEach(pr ->
                changelogBuilder.append(" * #").append(pr.getNumber()).append(" - ").append(pr.getTitle()).append("\n"));
        }
        if (latestReleasedTag == null)
        {
            changelogBuilder.append("\n<!--\nFor a complete list of changes please visit https://github.com/" + GitOps.REPOSITORY_NAME + "/compare/")
                .append("TODO_REPLACE_WITH_UPSTREAM_TAG").append("...mandrel-").append(version).append("\n-->\n");
        }
        else
        {
            changelogBuilder.append("\nFor a complete list of changes please visit https://github.com/" + GitOps.REPOSITORY_NAME + "/compare/")
                .append(latestReleasedTag).append("...mandrel-").append(version).append("\n");
        }
        return changelogBuilder.toString();
    }

    /**
     * Calculates the latest final version by checking major.minor.micro.pico
     *
     * @param tags
     * @return The latest final version
     */
    private String getLatestReleasedTag(List<GHTag> tags, String jdkVersionUsed)
    {
        // Figuring out the last released Tag for pre-releases is not trivial so
        // just return null and let the user manually update the release notes
        if (!version.isFinal())
        {
            return null;
        }
        final String tagPrefix = "mandrel-";
        List<MandrelVersion> finalVersions = tags.stream()
            .filter(x -> x.getName().startsWith(tagPrefix + version.majorMinorMicro()) && x.getName().endsWith("Final"))
            .map(x -> new MandrelVersion(x.getName().substring(tagPrefix.length())))
            .sorted(Comparator.reverseOrder()).toList();
        for (MandrelVersion mandrelVersion : finalVersions)
        {
            System.out.println(mandrelVersion);
        }
        assert !finalVersions.isEmpty() :
            "Tag for " + version + " is missing, please make sure the tag has been pushed before releasing.";
        assert version.compareTo(finalVersions.get(0)) == 0 :
            "Latest tag (" + finalVersions.get(0) + ") does not match the version of the current branch (" + version + "). " +
                "Please make sure you are on the correct branch and that you have created a tag for the release.";
        if (finalVersions.size() == 1)
        {
            // There is no Mandrel release before that major.minor.micro, return upstream graal tag instead
            final String upstreamTag;
            if (version.major >= 24 && jdkVersionUsed != null)
            {
                // e.g. OpenJDK used: 23+37 is actually tagged as jdk-23.0.0
                final String v = jdkVersionUsed.split("[+]")[0];
                upstreamTag = "jdk-" + (v.contains(".") ? v : v + ".0.0");
            }
            else
            {
                upstreamTag = "vm-" + version.majorMinorMicro();
            }
            if (tags.stream().noneMatch(x -> x.getName().equals(upstreamTag)))
            {
                Log.warn("Upstream tag " + upstreamTag + " not found in " + GitOps.REPOSITORY_NAME + " please add the upstream tag manually in the release text.");
                return null;
            }
            return upstreamTag;
        }
        return tagPrefix + finalVersions.get(1).toString();
    }

    private void manageMilestones(GHRepository repository, List<GHMilestone> ghMilestones, GHMilestone milestone) throws IOException
    {
        assert !dryRun : "Milestones should not be touched in dry runs";
        if (!version.isFinal())
        {
            return;
        }
        if (milestone != null)
        {
            milestone.close();
            Log.info("Closed milestone " + milestone.getTitle() + " (" + milestone.getNumber() + ")");
        }
        MandrelVersion newVersion = version.getNewVersion();
        GHMilestone newMilestone = ghMilestones.stream().filter(m -> m.getTitle().equals(newVersion.toString())).findAny().orElse(null);
        if (newMilestone == null)
        {
            newMilestone = repository.createMilestone(newVersion.toString(), "");
            Log.info("Created milestone " + newMilestone.getTitle() + " (" + newMilestone.getNumber() + ")");
        }
    }

    private boolean includeInChangelog(GHPullRequest pr, GHMilestone milestone)
    {
        try
        {
            GHMilestone prMilestone = pr.getMilestone();
            if (prMilestone != null && prMilestone.getNumber() == milestone.getNumber())
            {
                Log.debug("Checking if PR #" + pr.getNumber() + " is merged", verbose);
                // isMerged polls github, so make sure we run it only on the PRs with the milestone we are interested in
                return pr.isMerged();
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return false;
    }

    private Integer getGroup(GHPullRequest pr)
    {
        try
        {
            if (pr.getLabels().stream().anyMatch(l -> l.getName().equals("release/noteworthy-feature")))
            {
                return 0;
            }
            if (pr.getLabels().stream().anyMatch(l -> l.getName().equals("backport")))
            {
                return 1;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
        return 2;
    }

    private static GitHub connectToGitHub()
    {
        GitHub github = null;
        try
        {
            github = GitHubBuilder.fromPropertyFile().build();
        }
        catch (IOException e)
        {
            try
            {
                github = GitHubBuilder.fromEnvironment().build();
            }
            catch (IOException ioException)
            {
                ioException.printStackTrace();
                Log.error(ioException.getMessage());
            }
        }
        return github;
    }

}

class GitOps
{
    public static final String GITFORGE_URL = "git@github.com:";
    public static final String REPOSITORY_NAME = "graalvm/mandrel";
    private static final String REMOTE_NAME = "mandrel-release-fork";
    private final MandrelVersion version;
    private final String mandrelRepo;
    private final String forkName;
    private final String baseBranch;
    private final String releaseBranch;
    private final String developBranch;
    private final boolean signCommits;
    private final boolean dryRun;
    private final boolean release;

    public GitOps(MandrelVersion version, String mandrelRepo, String forkName, boolean signCommits, boolean dryRun, boolean release)
    {
        this.version = version;
        this.mandrelRepo = mandrelRepo;
        this.forkName = forkName;
        this.baseBranch = "mandrel/" + version.majorMinor();
        this.releaseBranch = "release/mandrel-" + version;
        this.developBranch = "develop/mandrel-" + version.getNewVersion();
        this.signCommits = signCommits;
        this.dryRun = dryRun;
        this.release = release;
    }

    static GitHub connectToGitHub()
    {
        GitHub github = null;
        try
        {
            github = GitHubBuilder.fromPropertyFile().build();
        }
        catch (IOException e)
        {
            try
            {
                github = GitHubBuilder.fromEnvironment().build();
            }
            catch (IOException ioException)
            {
                ioException.printStackTrace();
                Log.error(ioException.getMessage());
            }
        }
        return github;
    }

    void checkAndPrepareRepository()
    {
        try (Git git = Git.open(new File(mandrelRepo)))
        {
            if (!git.getRepository().getBranch().equals(baseBranch))
            {
                Log.error("Please checkout " + baseBranch + " and try again!");
            }
            final Status status = git.status().call();
            if (status.hasUncommittedChanges() || !status.getChanged().isEmpty())
            {
                Log.error("Status of branch " + baseBranch + " is not clean, aborting!");
            }

            maybeCreateRemote(git, forkName);
            String targetBranch = release ? developBranch : releaseBranch;
            try
            {
                git.checkout().setCreateBranch(true).setName(targetBranch).setStartPoint(baseBranch).call();
                Log.info("Created new branch " + targetBranch + " based on " + baseBranch);
            }
            catch (RefAlreadyExistsException e)
            {
                Log.warn(e.getMessage());
                gitCheckout(git, targetBranch);
                git.reset().setRef(baseBranch).setMode(ResetCommand.ResetType.HARD).call();
                Log.warn(targetBranch + " reset (hard) to " + baseBranch);
            }
        }
        catch (IOException | GitAPIException | URISyntaxException e)
        {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
    }

    private static void gitCheckout(Git git, String baseBranch) throws GitAPIException
    {
        git.checkout().setName(baseBranch).call();
        Log.info("Checked out " + baseBranch);
    }

    private static void maybeCreateRemote(Git git, String forkName) throws URISyntaxException, GitAPIException
    {
        final String forkURL = GITFORGE_URL + forkName;
        final URIish forkURI = new URIish(forkURL);
        RemoteConfig remote = getRemoteConfig(git);
        if (remote != null && remote.getURIs().stream().noneMatch(forkURI::equals))
        {
            Log.error("Remote " + REMOTE_NAME + " already exists and does not point to " + forkURL +
                "\nPlease remove with `git remote remove " + REMOTE_NAME + "` and try again.");
        }
        git.remoteAdd().setName(REMOTE_NAME).setUri(forkURI).call();
        remote = getRemoteConfig(git);
        if (remote != null && remote.getURIs().stream().anyMatch(forkURI::equals))
        {
            Log.info("Git remote " + REMOTE_NAME + " points to " + forkURL);
        }
        else
        {
            Log.error("Failed to add remote " + REMOTE_NAME + " pointing to " + forkURL);
        }
    }

    private static RemoteConfig getRemoteConfig(Git git) throws GitAPIException
    {
        final List<RemoteConfig> remotes = git.remoteList().call();
        for (RemoteConfig remoteConfig : remotes)
        {
            if (remoteConfig.getName().equals(REMOTE_NAME))
            {
                return remoteConfig;
            }
        }
        return null;
    }

    String commitAndPushChanges()
    {
        try (Git git = Git.open(new File(mandrelRepo)))
        {
            ConsoleCredentialsProvider.install(); // Needed for gpg signing
            final String message;
            if (release)
            {
                message = "Unmark suites and bump version to " + version.getNewVersion().majorMinorMicroPico() + " [skip ci]";
            }
            else
            {
                message = "Mark suites for " + version + " release [skip ci]";
            }
            final RevCommit commit = git.commit().setAll(true).setMessage(message).setSign(signCommits).call();
            final String author = commit.getAuthorIdent().getEmailAddress();
            Log.info("Changes commited");
            git.push().setForce(true).setRemote(REMOTE_NAME).setDryRun(dryRun).call();
            if (dryRun)
            {
                Log.warn("Changes not pushed to remote due to --dry-run being present");
            }
            else
            {
                Log.info("Changes pushed to remote " + REMOTE_NAME);
            }
            gitCheckout(git, baseBranch);
            return author;
        }
        catch (IOException | GitAPIException e)
        {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
        return null;
    }

    void openPR(String authorEmail)
    {
        GitHub github = GitOps.connectToGitHub();
        try
        {
            final GHRepository repository = github.getRepository(REPOSITORY_NAME);
            if (dryRun)
            {
                Log.warn("Pull request creation skipped due to --dry-run being present");
                return;
            }

            final String title;
            final String body;
            String head = "";
            if (!forkName.equals(REPOSITORY_NAME))
            {
                head = forkName.split("/")[0] + ":";
            }
            if (release)
            {
                title = "Unmark suites and bump version to " + version.getNewVersion().majorMinorMicroPico();
                head += developBranch;
                body = "This PR was automatically generated by `mandrel-release.java` of graalvm/mandrel-packaging!";
            }
            else
            {
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
            if (!authorEmail.contains("galder"))
            {
                reviewers.add(galderz);
            }
            else
            {
                pullRequest.addAssignees(Collections.singletonList(galderz));
            }
            if (!authorEmail.contains("sgehwolf") && !authorEmail.contains("jerboaa"))
            {
                reviewers.add(jerboaa);
            }
            else
            {
                pullRequest.addAssignees(Collections.singletonList(jerboaa));
            }
            if (!authorEmail.contains("karm"))
            {
                reviewers.add(karm);
            }
            else
            {
                pullRequest.addAssignees(Collections.singletonList(karm));
            }
            if (!authorEmail.contains("zakkak"))
            {
                reviewers.add(zakkak);
            }
            else
            {
                pullRequest.addAssignees(Collections.singletonList(zakkak));
            }
            pullRequest.requestReviewers(reviewers);

            Log.info("Pull request " + pullRequest.getHtmlUrl() + " created");
        }
        catch (IOException e)
        {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
    }
}

class MxSuiteUtils
{
    /**
     * Visit all suite.py files and change the "release" and "version" fields
     */
    static void updateSuites(String mandrelRepo, Consumer<File> updater)
    {
        File cwd = new File(mandrelRepo);
        Stream.of(Objects.requireNonNull(cwd.listFiles()))
            .filter(File::isDirectory)
            .flatMap(path -> Stream.of(Objects.requireNonNull(path.listFiles())).filter(child -> child.getName().startsWith("mx.")))
            .map(path -> new File(path, "suite.py"))
            .filter(File::exists)
            .forEach(updater);
        Log.info("Updated suites");
    }

    /**
     * Visit {@code suite} file and change the "release" value to {@code asRelease}
     *
     * @param suite
     */
    static void markSuiteAsRelease(File suite)
    {
        try
        {
            Log.info("Marking " + suite.getPath());
            List<String> lines = Files.readAllLines(suite.toPath());
            final String pattern = "(.*\"release\" : )False(.*)";
            final Pattern releasePattern = Pattern.compile(pattern);
            for (int i = 0; i < lines.size(); i++)
            {
                final Matcher releaseMatcher = releasePattern.matcher(lines.get(i));
                if (releaseMatcher.find())
                {
                    String newLine = releaseMatcher.group(1) + "True" + releaseMatcher.group(2);
                    lines.set(i, newLine);
                    break;
                }
            }
            Files.write(suite.toPath(), lines, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
    }

    static void updateReleaseAndBumpVersionInSuite(MandrelVersion version, File suite)
    {
        try
        {
            Log.info("Updating " + suite.getPath());
            List<String> lines = Files.readAllLines(suite.toPath());
            final Pattern releasePattern = Pattern.compile("(.*\"release\" : )True(.*)");
            final Pattern versionPattern = Pattern.compile("(.*\"version\" : \")" + version.majorMinorMicroPico() + "(\".*)");
            for (int i = 0; i < lines.size(); i++)
            {
                final Matcher releaseMatcher = releasePattern.matcher(lines.get(i));
                if (releaseMatcher.find())
                {
                    final String newLine = releaseMatcher.group(1) + "False" + releaseMatcher.group(2);
                    lines.set(i, newLine);
                }
                final Matcher versionMatcher = versionPattern.matcher(lines.get(i));
                if (versionMatcher.find())
                {
                    final String newLine = versionMatcher.group(1) + version.getNewVersion().majorMinorMicroPico() + versionMatcher.group(2);
                    lines.set(i, newLine);
                }
            }
            Files.write(suite.toPath(), lines, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            Log.error(e.getMessage());
        }
    }

}

class Log
{
    static void debug(String message, boolean verbose)
    {
        if (verbose)
        {
            System.err.println(Ansi.AUTO.string("[@|bold,green DEBUG|@] ") + message);
        }
    }

    static void info(String message)
    {
        System.err.println(Ansi.AUTO.string("[@|bold INFO|@] ") + message);
    }

    static void warn(String message)
    {
        System.err.println(Ansi.AUTO.string("[@|bold,yellow WARN|@] ") + message);
    }

    static void error(String message)
    {
        System.err.println(Ansi.AUTO.string("[@|bold,red ERROR|@] ") + message);
        System.exit(1);
    }
}

@Command(name = "prepare", description = "Prepare repository for release.")
class Prepare extends ReusableOptions implements Callable<Integer>
{
    public Integer call()
    {
        checkOptions();
        // Prepare version
        MandrelVersion version = MandrelVersion.ofRepository(mandrelRepo);
        assert version != null;
        Log.info("Current version is " + version);
        version.suffix = suffix; // TODO: if Alpha/Beta autobump suffix number?

        if (!version.isFinal())
        {
            return 0;
        }

        Log.info("New version will be " + version.getNewVersion().majorMinorMicroPico());

        GitOps gitOps = new GitOps(version, mandrelRepo, forkName, signCommits, dryRun, false);
        gitOps.checkAndPrepareRepository();
        MxSuiteUtils.updateSuites(mandrelRepo, MxSuiteUtils::markSuiteAsRelease);
        final String authorEmail = gitOps.commitAndPushChanges();
        gitOps.openPR(authorEmail);
        return 0;
    }
}

@Command(name = "release", description = "Release a new version.")
class Release extends ReusableOptions implements Callable<Integer>
{
    public static final int UNDEFINED = -1;

    @CommandLine.Option(names = {"-d", "--download"}, description = "Download built artifacts")
    boolean download;
    @CommandLine.Option(names = {"-O", "--download-dir"}, description = "Directory for artifacts download and upload", defaultValue = "./artifacts")
    String downloadDir;
    @CommandLine.Option(names = {"--linux-job-build-number"}, description = "The build number of the complete, tested matrix job run.", defaultValue = "" + UNDEFINED)
    int linuxBuildNumber;
    @CommandLine.Option(names = {"--windows-job-build-number"}, description = "The build number of the complete, tested matrix job run.", defaultValue = "" + UNDEFINED)
    int windowsBuildNumber;
    @CommandLine.Option(names = {"--macos-job-build-number"}, description = "The build number of the complete, tested matrix job run.", defaultValue = "" + UNDEFINED)
    int macosBuildNumber;

    @Override
    public Integer call() throws IOException
    {
        checkOptions();
        // Prepare version
        MandrelVersion version = MandrelVersion.ofRepository(mandrelRepo);
        assert version != null;
        Log.info("Current version is " + version);
        version.suffix = suffix; // TODO: if Alpha/Beta autobump suffix number?

        final Set<String> jdkVersionsUsed = maybeDownloadBuildsAndGetJdkVersions(version);
        GitHubOps gitHubOps = new GitHubOps(version, dryRun, downloadDir, verbose);
        gitHubOps.createGHRelease(jdkVersionsUsed, linuxBuildNumber != UNDEFINED, windowsBuildNumber != UNDEFINED, macosBuildNumber != UNDEFINED);
        if (!version.isFinal())
        {
            return 0;
        }

        Log.info("New version will be " + version.getNewVersion().majorMinorMicroPico());

        GitOps gitOps = new GitOps(version, mandrelRepo, forkName, signCommits, dryRun, true);
        gitOps.checkAndPrepareRepository();
        MxSuiteUtils.updateSuites(mandrelRepo, suite -> MxSuiteUtils.updateReleaseAndBumpVersionInSuite(version, suite));
        final String authorEmail = gitOps.commitAndPushChanges();
        gitOps.openPR(authorEmail);
        return 0;
    }


    private Set<String> maybeDownloadBuildsAndGetJdkVersions(MandrelVersion version) throws IOException
    {
        final Set<String> jdkVersionsUsed = new HashSet<>();
        if (download)
        {
            if (windowsBuildNumber != UNDEFINED || linuxBuildNumber != UNDEFINED || macosBuildNumber != UNDEFINED)
            {
                jdkVersionsUsed.addAll(downloadAssets(version));
                jdkVersionsUsed.stream().filter(String::isBlank).findAny()
                    .ifPresent(s -> Log.warn("One of the Jenkins job Artifacts sets has an empty JDK version " +
                        "in its MANDREL.md, e.g. [1]. It could be a benign parsing error, but you must download the artifact " +
                        "and check manually. [1] https://ci.modcluster.io/view/Mandrel/job/mandrel-24-1-macos-build-matrix/37/JDK_RELEASE=ga,JDK_VERSION=23,LABEL=macos_aarch64/artifact/MANDREL.md"));
            }
            else
            {
                Log.error("At least one of --windows-job-build-number, --linux-job-build-number or --macos-job-build-number must be specified. Terminating.");
            }
            if (version.major == 21 && jdkVersionsUsed.size() != 2)
            {
                Log.warn("There are supposed to be 2 distinct JDK versions used, one for JDK 17 and one for JDK 11. " +
                    "This is unexpected: " + String.join(",", jdkVersionsUsed));
            }
            else if ((version.major == 22 || (version.major == 23 && version.minor == 0)) && jdkVersionsUsed.size() != 1)
            {
                Log.warn("There was supposed to be just one JDK 17 version used. " +
                    "This is unexpected: " + String.join(",", jdkVersionsUsed));
            }
            else if (version.major == 23 && version.minor == 1 && jdkVersionsUsed.size() != 1)
            {
                Log.warn("There was supposed to be just one JDK 21 version used. " +
                    "This is unexpected: " + String.join(",", jdkVersionsUsed));
            }
            else if (version.major == 24 && version.minor == 0 && jdkVersionsUsed.size() != 1)
            {
                Log.warn("There was supposed to be just one JDK 22 version used. " +
                    "This is unexpected: " + String.join(",", jdkVersionsUsed));
            }
            else if (version.major == 24 && version.minor == 1 && jdkVersionsUsed.size() != 1)
            {
                Log.warn("There was supposed to be just one JDK 23 version used. " +
                    "This is unexpected: " + String.join(",", jdkVersionsUsed));
            }
        }
        if (jdkVersionsUsed.isEmpty())
        {
            jdkVersionsUsed.add(System.getProperty("java.runtime.version"));
        }
        return jdkVersionsUsed;
    }

    private void downloadFile(String sourceURL) throws IOException
    {
        final URL url = new URL(sourceURL);
        final Path destPath = Paths.get(downloadDir, url.getPath().substring(url.getPath().lastIndexOf('/') + 1));
        try (final InputStream inputStream = url.openStream())
        {
            Log.info("Downloading " + destPath.getFileName() + "...");
            Files.copy(inputStream, destPath, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            Log.error("Failed to download " + destPath.getFileName() + ", Error: " + e.getMessage());
        }
    }

    private String parseSmallRemoteTextFile(String sourceURL, Pattern pattern, String groupName)
    {
        try (final Scanner scanner = new Scanner(new URL(sourceURL).openConnection().getInputStream(), StandardCharsets.UTF_8))
        {
            scanner.useDelimiter("\\A");
            if (scanner.hasNext())
            {
                final String c = scanner.next();
                Log.debug("URL: " + sourceURL + " file contents: " + c, verbose);
                final Matcher m = pattern.matcher(c);
                if (m.matches())
                {
                    return m.group(groupName).trim();
                }
                else
                {
                    Log.warn("No match for pattern " + pattern + " found on " + sourceURL + ". This is likely an error.");
                }
            }
        }
        catch (IOException e)
        {
            Log.error("Failed to download " + sourceURL + ". Terminating." + e.getMessage());
        }
        return null;
    }

    /**
     * This method is hardwired to the Jenkins instance.
     *
     * @return Set of OpenJDKs used to do the upstream Mandrel builds
     */
    private Set<String> downloadAssets(MandrelVersion mandrelVersion) throws IOException
    {
        final Pattern jdkVersionPattern = Pattern.compile(".*OpenJDK *used: *(?<jdk>.*)", Pattern.DOTALL);
        final int[] jdkMajorVersions;
        if (mandrelVersion.major == 21)
        {
            jdkMajorVersions = new int[]{11, 17};
        }
        else if (mandrelVersion.major == 22 || (mandrelVersion.major == 23 && mandrelVersion.minor == 0))
        {
            jdkMajorVersions = new int[]{17};
        }
        else if (mandrelVersion.major == 23)
        {
            jdkMajorVersions = new int[]{21};
        }
        else if (mandrelVersion.major == 24 && mandrelVersion.minor == 0)
        {
            jdkMajorVersions = new int[]{22};
        }
        else if (mandrelVersion.major == 24 && mandrelVersion.minor == 1)
        {
            jdkMajorVersions = new int[]{23};
        }
        else
        {
            jdkMajorVersions = new int[]{24};
        }

        final String jenkinsURL = "https://ci.modcluster.io";

        final File df = new File(downloadDir);
        if (df.exists())
        {
            Arrays.stream(Objects.requireNonNull(df.listFiles())).forEach(File::delete);
        }
        else
        {
            df.mkdir();
        }

        final Set<String> jdkVersionsUsed = new HashSet<>(2);

        if (linuxBuildNumber != UNDEFINED)
        {
            final String[] linuxArchLabels = new String[]{"el8_aarch64", "el8"};
            final String linuxJobUrl = jenkinsURL + "/job/mandrel-" + mandrelVersion.major + "-" + mandrelVersion.minor + "-linux-build-matrix";
            for (int jdkMajorVersion : jdkMajorVersions)
            {
                for (String linuxArchLabel : linuxArchLabels)
                {
                    final String matrixJobCoordinates = linuxJobUrl + "/" + linuxBuildNumber + "/JDK_RELEASE=ga,JDK_VERSION=" + jdkMajorVersion + ",LABEL=" + linuxArchLabel + "/artifact";
                    final String tarURL = matrixJobCoordinates + "/mandrel-java" + jdkMajorVersion + "-linux-" + (linuxArchLabel.contains("aarch64") ? "aarch64" : "amd64") + "-" + mandrelVersion + ".tar.gz";
                    downloadFile(tarURL);
                    downloadFile(tarURL + ".sha1");
                    downloadFile(tarURL + ".sha256");
                    final String jdkv = parseSmallRemoteTextFile(matrixJobCoordinates + "/MANDREL.md", jdkVersionPattern, "jdk");
                    if (jdkv != null)
                    {
                        jdkVersionsUsed.add(jdkv);
                    }
                }
            }
        }
        if (windowsBuildNumber != UNDEFINED)
        {
            final String windowsArchLabel = "w2k19";
            final String windowsJobUrl = jenkinsURL + "/job/mandrel-" + mandrelVersion.major + "-" + mandrelVersion.minor + "-windows-build-matrix";
            for (int jdkMajorVersion : jdkMajorVersions)
            {
                final String matrixJobCoordinates = windowsJobUrl + "/" + windowsBuildNumber + "/JDK_RELEASE=ga,JDK_VERSION=" + jdkMajorVersion + ",LABEL=" + windowsArchLabel + "/artifact";
                final String zipURL = matrixJobCoordinates + "/mandrel-java" + jdkMajorVersion + "-windows-amd64-" + mandrelVersion + ".zip";
                downloadFile(zipURL);
                downloadFile(zipURL + ".sha1");
                downloadFile(zipURL + ".sha256");
                final String jdkv = parseSmallRemoteTextFile(matrixJobCoordinates + "/MANDREL.md", jdkVersionPattern, "jdk");
                if (jdkv != null)
                {
                    jdkVersionsUsed.add(jdkv);
                }
            }
        }
        if (macosBuildNumber != UNDEFINED)
        {
            final String macosArchLabel = "macos_aarch64";
            final String macosJobUrl = jenkinsURL + "/job/mandrel-" + mandrelVersion.major + "-" + mandrelVersion.minor + "-macos-build-matrix";
            for (int jdkMajorVersion : jdkMajorVersions)
            {
                final String matrixJobCoordinates = macosJobUrl + "/" + macosBuildNumber + "/JDK_RELEASE=ga,JDK_VERSION=" + jdkMajorVersion + ",LABEL=" + macosArchLabel + "/artifact";
                final String tarURL = matrixJobCoordinates + "/mandrel-java" + jdkMajorVersion + "-macos-aarch64-" + mandrelVersion + ".tar.gz";
                downloadFile(tarURL);
                downloadFile(tarURL + ".sha1");
                downloadFile(tarURL + ".sha256");
                final String jdkv = parseSmallRemoteTextFile(matrixJobCoordinates + "/MANDREL.md", jdkVersionPattern, "jdk");
                if (jdkv != null)
                {
                    jdkVersionsUsed.add(jdkv);
                }
            }
        }
        return jdkVersionsUsed;
    }
}
