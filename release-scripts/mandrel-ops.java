//usr/bin/env jbang --ea "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.13.0.202109080827-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.pgm:5.13.0.202109080827-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.13.0.202109080827-r
//DEPS info.picocli:picocli:4.5.0
//DEPS org.kohsuke:github-api:1.316

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.console.ConsoleCredentialsProvider;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
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
import java.time.Year;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
        ConsoleCredentialsProvider.install();
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
    private static final Pattern VERSION_PATTERN = Pattern.compile("^([ \\t]{1,4})\"version\"(\\s*:\\s*)\"([^\"]+)\"(.*)$");
    private static final Pattern RELEASE_PATTERN = Pattern.compile("^([ \\t]{1,4})\"release\"(\\s*:\\s*)(True|False)(.*)$");

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
        for (String line : lines) {
            if (line.startsWith("<<<<<<< HEAD")) {
                inConflict = true;
                continue;
            }
            if (inConflict) {
                if (line.startsWith("=======")) {
                    continue;
                }
                if (line.startsWith(">>>>>>>")) {
                    inConflict = false;
                    resolved.add(
                            indent + "\"version\"" + vSep + "\"" + targetVersion + "\"" + (hasTrailingCommaVersion ? "," : ""));
                    resolved.add(indent + "\"release\"" + rSep + (targetRelease ? "True" : "False") + (hasTrailingCommaRelease
                            ? ","
                            : ""));
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

    static void modifySuitesReleaseState(File repoDir, boolean release, String targetVersion) {
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
                            if (!releaseUpdated) {
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

     static void dealWithMilestones( GHRepository repo, String version, String nextVersion, boolean dryRun) throws IOException {
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
             if (ms.getTitle().equals(nextVersion)) {
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
         if (!nextExists) {
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

    @Override
    public Integer call() throws Exception {
        ConsoleCredentialsProvider.install();
        final String branchName = "release-prep-" + System.currentTimeMillis();
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            git.checkout().setCreateBranch(true).setName(branchName).setStartPoint(baseBranch).call();
            SuiteOpsUtils.modifySuitesReleaseState(repoDir, true, version);
            final String titleAndCommit = "Mark suite files for " + version + " release [skip ci]";
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

    @Override
    public Integer call() throws Exception {
        ConsoleCredentialsProvider.install();
        if (nextVersion == null) {
            final String[] parts = version.split("\\.");
            parts[2] = String.valueOf(Integer.parseInt(parts[2]) + 1);
            nextVersion = String.join(".", parts);
            System.out.println("Auto-calculated next version: " + nextVersion);
        }
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            git.pull().setRemote(upstreamRemote).setRemoteBranchName(baseBranch).call();
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
            SuiteOpsUtils.dealWithMilestones(repo, version, nextVersion, dryRun);
            // Prep next
            final String branchName = "bump-version-" + nextVersion + "-" + System.currentTimeMillis();
            git.checkout().setCreateBranch(true).setName(branchName).setStartPoint(baseBranch).call();
            SuiteOpsUtils.modifySuitesReleaseState(repoDir, false, nextVersion);
            final String titleAndCommit = "Unmark suite files and bump version to " + nextVersion + " [skip ci]";
            git.commit().setAll(true).setMessage(titleAndCommit).setSign(true).call();
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

    @Override
    public Integer call() throws Exception {
        ConsoleCredentialsProvider.install();
        final String branchName = "release-prep-" + System.currentTimeMillis();
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            git.checkout().setCreateBranch(true).setName(branchName).setStartPoint(baseBranch).call();
            git.fetch().setRemote(upstreamUrl)
                    .setRefSpecs(new RefSpec("+refs/tags/*:refs/tags/*")).call();
            final ObjectId tagId = git.getRepository().resolve(upstreamTag);
            final MergeResult mergeResult = git.merge().include(upstreamTag, tagId).setCommit(false).call();
            final String rawUpstreamVersion = upstreamTag.replace("vm-", "");
            final String mandrelVersion = rawUpstreamVersion + "." + suffix;
            final String prTitleVersion = mandrelVersion + "-Final";
            if (!mergeResult.getMergeStatus().isSuccessful()) {
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
            SuiteOpsUtils.modifySuitesReleaseState(repoDir, true, mandrelVersion);
            git.add().addFilepattern(".").call();
            final String titleAndCommit = "Mark suites for " + prTitleVersion + " release [skip ci]";
            git.commit().setAll(true).setMessage(titleAndCommit).setSign(true).call();
            if (!dryRun) {
                SuiteOpsUtils.pushToFork(git, forkName, branchName);
                final GitHub github = SuiteOpsUtils.connectToGitHub();
                final GHRepository repo = github.getRepository(downstreamRepo);
                final String head = forkName.split("/")[0] + ":" + branchName;
                final String tagLink = upstreamUrl.replaceAll("\\.git$", "") + "/releases/tag/" + upstreamTag;
                final GHPullRequest pr =
                        repo.createPullRequest(titleAndCommit, head, baseBranch, "Upstream tag: " + tagLink, true, false);
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

    @Override
    public Integer call() throws Exception {
        ConsoleCredentialsProvider.install();
        if (nextVersion == null) {
            final String cleanVersion = version.replace("mandrel-", "").replace("-Final", "");
            final String[] parts = cleanVersion.split("\\.");
            parts[2] = String.valueOf(Integer.parseInt(parts[2]) + 1);
            if (parts.length >= 4) {
                parts[3] = "0";
            }
            nextVersion = String.join(".", parts);
            System.out.println("Auto-calculated next version: " + nextVersion);
        }
        final String currentMilestoneTitle = version.replace("mandrel-", "");
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            git.pull().setRemote(upstreamRemote).setRemoteBranchName(baseBranch).call();
            System.out.println("Creating signed tag: " + version);
            git.tag().setName(version).setMessage(version).setSigned(true).call();
            if (!dryRun) {
                SuiteOpsUtils.pushRef(git, upstreamRemote, "refs/tags/" + version);
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
    @Option(names = { "-j", "--jdk-version" }, required = true, description = "JDK version string (e.g., 25.0.2+10-LTS)")
    String jdkVersion;
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

    @Override
    public Integer call() throws Exception {
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw new IOException("Failed to create download directory: " + downloadDir.getAbsolutePath());
        }
        if (!templateFile.exists() || !templateFile.isFile()) {
            throw new RuntimeException("Release template file not found at: " + templateFile.getAbsolutePath());
        }
        final String fullVersion = version.replace("mandrel-", "");
        final String[] parts = fullVersion.split("\\.");
        final int major = Integer.parseInt(parts[0]);
        final int minor = Integer.parseInt(parts[1]);
        final String jobPrefix = "mandrel-" + major + "-" + minor;
        final String jdkMajor = jdkVersion.split("[\\.\\+]")[0];
        final List<String> targetUrls = new ArrayList<>();
        final Set<String> discoveredJdkVersions = new HashSet<>();
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
        final String exactJdkVersion = discoveredJdkVersions.iterator().next();
        System.out.println("Verified uniform OpenJDK version across all platforms: " + exactJdkVersion);
        final List<File> downloadedFiles = new ArrayList<>();
        System.out.println("Downloading " + targetUrls.size() + " artifact files...");
        for (String url : targetUrls) {
            downloadedFiles.add(downloadIfNotExists(url, downloadDir));
        }
        final String templateText = Files.readString(templateFile.toPath(), StandardCharsets.UTF_8);
        final String body = templateText
                .replace("{{FULL_VERSION}}", fullVersion)
                .replace("{{VERSION}}", version)
                .replace("{{PREV_VERSION}}", prevVersion)
                .replace("{{UPSTREAM_REPO}}", upstreamRepo)
                .replace("{{UPSTREAM_TAG}}", upstreamTag)
                .replace("{{JDK_VERSION}}", exactJdkVersion)
                .replace("{{JDK_MAJOR}}", jdkMajor);
        final GitHub github = SuiteOpsUtils.connectToGitHub();
        final GHRepository repo = github.getRepository(repoName);
        if (dryRun) {
            System.out.println("\n[DRY RUN] Release Body Preview:");
            System.out.println("==================================================");
            System.out.println(body);
            System.out.println("==================================================");
            System.out.println(
                    "[DRY RUN] Would upload " + downloadedFiles.size() + " artifacts to a Draft release titled 'Mandrel " + fullVersion + "'.");
            return 0;
        }
        System.out.println("Creating draft GitHub release for " + version + "...");
        final GHRelease release = repo.createRelease(version)
                .name("Mandrel " + fullVersion)
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

    private File downloadIfNotExists(String urlStr, File dir) throws Exception {
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
            "--version" }, required = true, split = ",", description = "Release tags to update (e.g., mandrel-25.0.3.0-Final,mandrel-23.1.11.0-Final)")
    List<String> versions;
    @Option(names = { "-p",
            "--prev-version" }, required = true, split = ",", description = "Previous release tags to replace (e.g., mandrel-25.0.2.0-Final,mandrel-23.1.10.0-Final)")
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

    @Override
    public Integer call() throws Exception {
        ConsoleCredentialsProvider.install();
        // ...perhaps many more months :)
        final Set<String> validMonths = Set.of("January", "April", "July", "October");
        if (!validMonths.contains(month)) {
            throw new RuntimeException(
                    "ABORT: Invalid month provided. Must be fully spelled out with capital first letter (e.g., January). You provided: " + month);
        }
        if (versions.size() != prevVersions.size()) {
            throw new RuntimeException(
                    "ABORT: The number of --version arguments must match the number of --prev-version arguments.");
        }
        try (Git git = Git.open(repoDir)) {
            if (!git.status().call().isClean()) {
                throw new RuntimeException(
                        "ABORT: Local repository " + repoDir.getAbsolutePath() + " has uncommitted changes.");
            }
            final String branchName = month + "-CPU-" + UUID.randomUUID().toString().substring(0, 6);
            System.out.println("Creating new branch: " + branchName);
            git.checkout().setName(baseBranch).call();
            git.checkout().setCreateBranch(true).setName(branchName).setStartPoint(baseBranch).call();
            final GitHub github = SuiteOpsUtils.connectToGitHub();
            final GHRepository mandrelRepo = github.getRepository(mandrelRepoName);
            final File yamlFile = new File(repoDir, "quarkus-mandrel-builder-image/mandrel.yaml");
            if (!yamlFile.exists()) {
                throw new RuntimeException(
                        "ABORT: Could not find quarkus-mandrel-builder-image/mandrel.yaml in " + repoDir.getAbsolutePath());
            }
            final List<String> lines = Files.readAllLines(yamlFile.toPath(), StandardCharsets.UTF_8);
            final List<String> extractedJdkVersionsForTitle = new ArrayList<>();
            for (int j = 0; j < versions.size(); j++) {
                final String versionTag = versions.get(j);
                final String prevVersionTag = prevVersions.get(j);
                final String fullVersion = versionTag.replace("mandrel-", "");
                final String prevFullVersion = prevVersionTag.replace("mandrel-", "");
                final String releaseName = "Mandrel " + fullVersion;
                System.out.println("\nProcessing updates for " + versionTag + " (replacing " + prevVersionTag + ")");
                System.out.println("Querying GitHub API for release " + versionTag + "...");
                GHRelease release = null;
                for (GHRelease r : mandrelRepo.listReleases()) {
                    if (versionTag.equals(r.getTagName())) {
                        release = r;
                        break;
                    }
                }
                if (release == null) {
                    System.err.println("WARNING: Could not find GitHub release for tag " + versionTag + ". " +
                            "It likely means it's still a DRAFT. Script can continue, but Quarkus Images CI would fail, " +
                            "so you should release the draft now.");
                }
                for (GHRelease r : mandrelRepo.listReleases()) {
                    if (releaseName.equals(r.getName())) {
                        release = r;
                        break;
                    }
                }
                if (release == null) {
                    throw new RuntimeException(
                            "ABORT: Could not find GitHub release for tag " + versionTag + " or name " + releaseName);
                }
                final Matcher m = Pattern.compile("OpenJDK used: (\\d+\\.\\d+\\.\\d+)").matcher(release.getBody());
                if (!m.find()) {
                    throw new RuntimeException("ABORT: Could not infer JDK version from release body of " + versionTag);
                }
                final String newJdkVersion = m.group(1);
                extractedJdkVersionsForTitle.add(newJdkVersion);
                System.out.println("Inferred new JDK version: " + newJdkVersion);
                final String jdkMajor = newJdkVersion.split("\\.")[0];
                final String amdSha = getSha(downloadDir, jdkMajor, fullVersion, "amd64");
                final String armSha = getSha(downloadDir, jdkMajor, fullVersion, "aarch64");
                System.out.println("Loaded amd64 sha256: " + amdSha);
                System.out.println("Loaded aarch64 sha256: " + armSha);
                // YAML
                boolean inBlock = false;
                for (int i = 0; i < lines.size(); i++) {
                    final String line = lines.get(i);
                    if (line.contains("mandrel-" + prevFullVersion)) {
                        lines.set(i, line.replace(prevFullVersion, fullVersion));
                    } else if (line.contains("graalvm-version: " + prevFullVersion)) {
                        lines.set(i, line.replace(prevFullVersion, fullVersion));
                        inBlock = true;
                    } else if (inBlock) {
                        if (line.contains("tags:")) {
                            lines.set(i, line.replaceAll("jdk-\\d+\\.\\d+\\.\\d+", "jdk-" + newJdkVersion));
                        } else if (line.contains("sha:")) {
                            if (i + 1 < lines.size() && lines.get(i + 1).contains("arch: amd64")) {
                                lines.set(i, line.replaceFirst("sha: [a-fA-F0-9]+", "sha: " + amdSha));
                            } else if (i + 1 < lines.size() && lines.get(i + 1).contains("arch: arm64")) {
                                lines.set(i, line.replaceFirst("sha: [a-fA-F0-9]+", "sha: " + armSha));
                                inBlock = false; //tehre are only two archs, end of block
                            }
                        }
                    }
                }
            }
            Files.write(yamlFile.toPath(), lines, StandardCharsets.UTF_8);
            System.out.println("\nYAML updated successfully.");
            git.add().addFilepattern("quarkus-mandrel-builder-image/mandrel.yaml").call();
            // build title
            extractedJdkVersionsForTitle.sort(Collections.reverseOrder());
            final String prTitle = month + " " + Year.now().getValue() + " CPU, JDK " + String.join(", ",
                    extractedJdkVersionsForTitle);
            final String prBody = "SSIA";
            System.out.println("Committing changes: " + prTitle);
            git.commit().setAll(true).setMessage(prTitle).setSign(true).call();
            if (!dryRun) {
                SuiteOpsUtils.pushToFork(git, forkName, branchName);
                final GHRepository targetRepo = github.getRepository(upstreamRepo);
                final String head = forkName.split("/")[0] + ":" + branchName;
                System.out.println("Opening PR on " + upstreamRepo + "...");
                final GHPullRequest pr = targetRepo.createPullRequest(prTitle, head, baseBranch, prBody, true, false);
                if (!testRun) {
                    SuiteOpsUtils.setupPRReviewers(github, pr, true);
                }
                System.out.println("PR created: " + pr.getHtmlUrl());
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
        final String content = Files.readString(shaFile.toPath()).trim();
        return content.split("\\s+")[0];
    }
}

/**
 * STEP 7: SYNC UPSTREAM
 * See README.md
 */
@Command(name = "sync-upstream", description = "Merges upstream branch into downstream and opens a Sync PR")
class SyncUpstream implements Callable<Integer> {
    @Option(names = { "-d", "--dir" }, defaultValue = "./")
    File repoDir;
    @Option(names = { "-f", "--fork" }, required = true)
    String forkName;
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
    @Option(names = { "-S", "--since" }, description = "Commit SHA or tag to start PR parsing from (default: downstream HEAD)")
    String sinceCommit;
    @Option(names = { "-D", "--dry-run" })
    boolean dryRun;
    @Option(names = { "--test-run" })
    boolean testRun;

    @Override
    public Integer call() throws Exception {
        ConsoleCredentialsProvider.install();
        try (Git git = Git.open(repoDir)) {
            git.checkout().setName(baseBranch).call();
            git.pull().setRemote("origin").setRemoteBranchName(baseBranch).call();
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
            final ObjectId sinceId = sinceCommit != null ? git.getRepository().resolve(sinceCommit) : git.getRepository().resolve("HEAD");
            if (sinceId == null && sinceCommit != null) {
                throw new RuntimeException("ABORT: Could not resolve --since commit or tag: " + sinceCommit);
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
            final MergeResult mergeResult = git.merge().include(fetchHead).setCommit(false).call();
            if (!mergeResult.getMergeStatus().isSuccessful()) {
                if (mergeResult.getMergeStatus() == MergeResult.MergeStatus.CONFLICTING) {
                    for (String conflictingPath : mergeResult.getConflicts().keySet()) {
                        if (!conflictingPath.endsWith("suite.py")) {
                            throw new RuntimeException(
                                    "Unexpected conflict: " + conflictingPath + ". Please resolve manually.");
                        }
                        SuiteOpsUtils.resolveSuiteConflict(new File(repoDir, conflictingPath), nextVersion, false);
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
            git.commit().setAll(true).setMessage(commitMessage).setSign(true).call();
            if (!dryRun) {
                SuiteOpsUtils.pushToFork(git, forkName, workBranch);
                final String head = forkName.split("/")[0] + ":" + workBranch;
                final GitHub github = SuiteOpsUtils.connectToGitHub();
                final GHRepository repo = github.getRepository(downstreamRepo);
                final GHPullRequest pr = repo.createPullRequest(prTitle, head, baseBranch, commitMessage, true, false);
                if (!testRun) {
                    SuiteOpsUtils.setupPRReviewers(github, pr, false);
                }
                System.out.println("PR created: " + pr.getHtmlUrl());
            } else {
                System.out.println("[DRY RUN] Would push branch and create PR with title: " + prTitle);
            }
        }
        return 0;
    }
}
