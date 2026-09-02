//usr/bin/env jbang --ea "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.13.0.202109080827-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.pgm:5.13.0.202109080827-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.13.0.202109080827-r
//DEPS org.kohsuke:github-api:1.316

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.console.ConsoleCredentialsProvider;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHMilestone;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRef;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * See ../README.md for a comprehensive overview.
 * <p>
 * This script tests the main workflow of a standard release. It uses **dummy, fake** repositories with generated data, both
 * local and remote. It does not touch any real GraalVM or Mandrel repo in any way. If you spot any action that depends on or
 * touches a real GitHub repository, it is a horrible bug.
 */
class TestMandrelOps {

    // Fake repo configuration, intentionally hardcoded to avoid command line mistakes.
    static final String GH_ORG = "Karm";
    static final String GH_NAME = "Karm Michal Babacek";
    static final String GH_EMAIL = "karm@ibm.com";
    static final String GH_PGP = "D72501BA9A2A624B000F38858CDBCE4379381FC4";
    static final String UPSTREAM_REPO_21U = GH_ORG + "/test-fake-graalvm-community-jdk21u";
    static final String UPSTREAM_REPO_25U = GH_ORG + "/test-fake-graalvm-community-jdk25u";
    static final String DOWNSTREAM_REPO = GH_ORG + "/test-fake-mandrel";
    static final String PACKAGING_REPO = GH_ORG + "/test-fake-mandrel-packaging";
    static final String QUARKUS_REPO = GH_ORG + "/test-fake-quarkus-images";
    static final File WORK_DIR = new File("/tmp/mandrel-test-workspace");
    // Saves time white running TS many times.
    static final File ARTIFACTS_DIR = new File("/tmp/mandrel-test-workspace-artifacts");
    // Jenkins Build Artifact IDs for 23.1.12.0
    static final int LINUX_BUILD_23_1_12_0 = 346;
    static final int WINDOWS_BUILD_23_1_12_0 = 232;
    static final int MACOS_BUILD_23_1_12_0 = 270;
    // Jenkins Build Artifact IDs for 25.0.4.1
    static final int LINUX_BUILD_25_0_4_1 = 143;    // https://ci.modcluster.io/job/mandrel-25-0-linux-amd64-build-matrix/143/
    static final int WINDOWS_BUILD_25_0_4_1 = 105;  // https://ci.modcluster.io/job/mandrel-25-0-windows-amd64-build-matrix/105/
    static final int MACOS_BUILD_25_0_4_1 = 162;    // https://ci.modcluster.io/job/mandrel-25-0-macos-aarch64-build-matrix/162/

    static class PrInfo {
        final String sinceSha;
        final int prNumber;

        PrInfo(String sinceSha, int prNumber) {
            this.sinceSha = sinceSha;
            this.prNumber = prNumber;
        }
    }

    public static void main(String[] args) throws Exception {
        ConsoleCredentialsProvider.install();
        // perhaps just mine jgit quirk if TS dies midflight
        clearStaleGlobalGitLocks();
        System.out.println("Starting Integration Test for Mandrel Release Ops...");
        final GitHub github = GitHubBuilder.fromPropertyFile().build();
        setupFakeRepos(github);
        final String upstreamBase21u = getBaseBranch(new File(WORK_DIR, "test-fake-graalvm-community-jdk21u"));
        final String quarkusBase = getBaseBranch(new File(WORK_DIR, "test-fake-quarkus-images"));
        final String realTemplatePath = new File("release-template.md").getAbsolutePath();

        // Flow:
        // Upstream: 23.1.11 previous -> 23.1.12 current release -> 23.1.13 next
        // Downstream: 23.1.12.0 -> 23.1.13.0
        // Quarkus images: 23.1.11.0-Final -> 23.1.12.0-Final

        //  STANDARD FLOW TESTS (23.1.11.0 -> 23.1.12.0)
        System.out.println("\n[TEST] Executing Step 1: upstream-mark");
        runOps("upstream-mark",
                "--dir", new File(WORK_DIR, "test-fake-graalvm-community-jdk21u").getAbsolutePath(),
                "--fork", UPSTREAM_REPO_21U,
                "--repo", UPSTREAM_REPO_21U,
                "--base-branch", upstreamBase21u,
                "--version", "23.1.12",
                "--test-run");
        mergeLatestPR(github, UPSTREAM_REPO_21U, upstreamBase21u);
        verifySuitePy(new File(WORK_DIR, "test-fake-graalvm-community-jdk21u"), "23.1.12", true);

        // STEP 2: Upstream Finalize
        System.out.println("\n[TEST] Executing Step 2: upstream-finalize");
        runOps("upstream-finalize",
                "--dir", new File(WORK_DIR, "test-fake-graalvm-community-jdk21u").getAbsolutePath(),
                "--fork", UPSTREAM_REPO_21U,
                "--repo", UPSTREAM_REPO_21U,
                "--base-branch", upstreamBase21u,
                "--version", "23.1.12",
                "--jdk-version", "21.0.12",
                "--upstream-remote", "origin",
                "--test-run");
        mergeLatestPR(github, UPSTREAM_REPO_21U, upstreamBase21u);
        verifySuitePy(new File(WORK_DIR, "test-fake-graalvm-community-jdk21u"), "23.1.13", false);
        verifyTagExists(github, UPSTREAM_REPO_21U, "vm-23.1.12");
        verifyTagExists(github, UPSTREAM_REPO_21U, "jdk-21.0.12");

        // STEP 3: Downstream Sync Mark
        System.out.println("\n[TEST] Executing Step 3: downstream-sync-mark");
        runOps("downstream-sync-mark",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO_21U + ".git",
                "--upstream-tag", "vm-23.1.12",
                "--test-run");
        mergeLatestPR(github, DOWNSTREAM_REPO, "mandrel/23.1");
        verifySuitePy(new File(WORK_DIR, "test-fake-mandrel"), "23.1.12.0", true);
        verifyWasmSuitePy(new File(WORK_DIR, "test-fake-mandrel"), "23.1.12.0");

        // STEP 4: Downstream Finalize
        System.out.println("\n[TEST] Executing Step 4: downstream-finalize");
        runOps("downstream-finalize",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--version", "mandrel-23.1.12.0-Final",
                "--upstream-remote", "origin");
        verifyTagExists(github, DOWNSTREAM_REPO, "mandrel-23.1.12.0-Final");
        verifyMilestoneClosed(github, DOWNSTREAM_REPO, "23.1.12.0-Final");

        // STEP 5: Publish Release
        System.out.println("\n[TEST] Executing Step 5: publish-release");
        runOps("publish-release",
                "--repo", DOWNSTREAM_REPO,
                "--version", "mandrel-23.1.12.0-Final",
                "--prev-version", "mandrel-23.1.11.0-Final",
                "--upstream-repo", UPSTREAM_REPO_21U,
                "--upstream-tag", "vm-23.1.12",
                "--linux-build", String.valueOf(LINUX_BUILD_23_1_12_0),
                "--windows-build", String.valueOf(WINDOWS_BUILD_23_1_12_0),
                "--macos-build", String.valueOf(MACOS_BUILD_23_1_12_0),
                "--download-dir", ARTIFACTS_DIR.getAbsolutePath(),
                "-T", realTemplatePath);

        System.out.println("\n[TEST] Publishing the draft release so Step 6 can find it");
        publishDraftRelease(github, DOWNSTREAM_REPO, "Mandrel 23.1.12.0-Final");

        // STEP 6: Update Quarkus Images
        System.out.println("\n[TEST] Executing Step 6: update-quarkus-images");
        runOps("update-quarkus-images",
                "--dir", new File(WORK_DIR, "test-fake-quarkus-images").getAbsolutePath(),
                "--month", "April",
                "--version", "mandrel-23.1.12.0-Final,graal-25.3.4.1",
                "--prev-version", "mandrel-23.1.11.0-Final,graal-25.2.4",
                "--download-dir", ARTIFACTS_DIR.getAbsolutePath(),
                "--fork", QUARKUS_REPO,
                "--upstream-repo", QUARKUS_REPO,
                "--base-branch", quarkusBase,
                "--mandrel-repo", DOWNSTREAM_REPO,
                "--test-run");
        mergeLatestPR(github, QUARKUS_REPO, quarkusBase);
        verifyQuarkusYaml(new File(WORK_DIR, "test-fake-quarkus-images"), "23.1.12.0-Final");
        verifyGraalvmYaml(new File(WORK_DIR, "test-fake-quarkus-images"), "graal-25.3.4.1", "25.0.4.1");

        // STEP 7: Sync Upstream (Post-Release)
        System.out.println("\n[TEST] Executing Step 7: sync-upstream");
        runOps("sync-upstream",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO_21U + ".git",
                "--upstream-branch", upstreamBase21u,
                "--next-version", "23.1.13.0",
                "--test-run");

        final GHRepository downstreamGhRepo = github.getRepository(DOWNSTREAM_REPO);
        final GHPullRequest syncPr = downstreamGhRepo.getPullRequests(GHIssueState.OPEN).getFirst();
        if (!syncPr.getBody().contains("/pull/")) {
            throw new RuntimeException(
                    "Verification failed: Sync PR body does not contain upstream PR links! Body:\n" + syncPr.getBody());
        }
        System.out.println("   [OK] Verified Sync PR body contains upstream PR links.");
        mergeLatestPR(github, DOWNSTREAM_REPO, "mandrel/23.1");
        verifySuitePy(new File(WORK_DIR, "test-fake-mandrel"), "23.1.13.0", false);
        verifyWasmSuitePy(new File(WORK_DIR, "test-fake-mandrel"), "23.1.13.0");

        // STEP 8: Second Sync Upstream (Verifying explicit --since)
        System.out.println("\n[TEST] Simulating further upstream development (feature-A)...");
        final PrInfo featureA = createAndMergeMultiCommitUpstreamPR(github, UPSTREAM_REPO_21U, upstreamBase21u, "feature-A");

        System.out.println("\n[TEST] Executing Step 8: sync-upstream (Second sync with explicit --since)");
        runOps("sync-upstream",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO_21U + ".git",
                "--upstream-branch", upstreamBase21u,
                "--since", featureA.sinceSha,
                "--test-run");

        final GHPullRequest syncPr2 = downstreamGhRepo.getPullRequests(GHIssueState.OPEN).getFirst();
        if (!syncPr2.getBody().contains("/pull/" + featureA.prNumber)) {
            throw new RuntimeException("Verification failed: Second Sync PR does not contain the new feature PR link.");
        }
        // it shouldn't contain the PR link from Step 1 or Step 2
        if (syncPr2.getBody().contains("/pull/1\n") || syncPr2.getBody().contains("/pull/2\n")) {
            throw new RuntimeException(
                    "Verification failed: Second Sync PR contains old PR links from previous syncs. Body:\n" + syncPr2.getBody());
        }
        System.out.println("   [OK] Verified Second Sync PR body correctly respects the explicit --since bound.");
        mergeLatestPR(github, DOWNSTREAM_REPO, "mandrel/23.1");

        // STEP 9: Third Sync Upstream (Verifying auto-calculated merge base)
        System.out.println("\n[TEST] Simulating further upstream development for auto-since (feature-B)...");
        final PrInfo featureB = createAndMergeMultiCommitUpstreamPR(github, UPSTREAM_REPO_21U, upstreamBase21u, "feature-B");

        System.out.println("\n[TEST] Executing Step 9: sync-upstream (Third sync without --since)");
        runOps("sync-upstream",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO_21U + ".git",
                "--upstream-branch", upstreamBase21u,
                "--test-run");

        final GHPullRequest syncPr3 = downstreamGhRepo.getPullRequests(GHIssueState.OPEN).getFirst();
        if (!syncPr3.getBody().contains("/pull/" + featureB.prNumber)) {
            throw new RuntimeException("Verification failed: Third Sync PR does not contain the feature-B PR link.");
        }
        if (syncPr3.getBody().contains("/pull/" + featureA.prNumber)) {
            throw new RuntimeException(
                    "Verification failed: Third Sync PR contains old feature-A PR links from the previous sync. Body:\n" + syncPr3.getBody());
        }
        System.out.println("   [OK] Verified Third Sync PR body correctly auto-calculated the merge-base bound.");
        mergeLatestPR(github, DOWNSTREAM_REPO, "mandrel/23.1");

        // STEP 10: Cherry-pick auto-detection failure test
        System.out.println("\n[TEST] Executing Step 10: Testing Cherry-pick bail out");
        try (Git git = Git.open(new File(WORK_DIR, "test-fake-mandrel"))) {
            git.checkout().setName("mandrel/23.1").call(); // Guarantee we are on the base branch!
            Files.writeString(new File(WORK_DIR, "test-fake-mandrel/cherry-pick.txt").toPath(), "cherry", StandardCharsets.UTF_8);
            git.add().addFilepattern("cherry-pick.txt").call();
            git.commit().setSign(false).setMessage("Fix some issue\n\n(cherry picked from commit 12345678)").call();
        }
        runOpsExpectingFailure("There seems to be cherry-picked commits in history",
                "sync-upstream",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO_21U + ".git",
                "--upstream-branch", upstreamBase21u,
                "--test-run");
        try (Git git = Git.open(new File(WORK_DIR, "test-fake-mandrel"))) {
            git.checkout().setName("mandrel/23.1").call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call();
        }

        // STEP 11: Squash auto-detection failure test
        System.out.println("\n[TEST] Executing Step 11: Testing Squash bail out");
        try (Git git = Git.open(new File(WORK_DIR, "test-fake-mandrel"))) {
            git.checkout().setName("mandrel/23.1").call(); // Guarantee we are on the base branch!
            Files.writeString(new File(WORK_DIR, "test-fake-mandrel/squash.txt").toPath(), "squash", StandardCharsets.UTF_8);
            git.add().addFilepattern("squash.txt").call();
            git.commit().setSign(false).setMessage("Squash merge PR #123\n\nSquashed commit of the following:").call();
        }
        runOpsExpectingFailure("There seems to be squash commits in history",
                "sync-upstream",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO_21U + ".git",
                "--upstream-branch", upstreamBase21u,
                "--test-run");
        try (Git git = Git.open(new File(WORK_DIR, "test-fake-mandrel"))) {
            git.checkout().setName("mandrel/23.1").call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call();
        }

        //  CSPU EMPTY FLOW TESTS (25.0.4.0 -> 25.0.4.1)
        // ..."empty" as in no GraalVM patches in this CSPU, just the OpenJDK rebuild
        System.out.println("\n[TEST] Executing CSPU Step 1: upstream-mark (Empty Diff)");
        runOps("upstream-mark",
                "--cspu",
                "--dir", new File(WORK_DIR, "test-fake-graalvm-community-jdk25u").getAbsolutePath(),
                "--fork", UPSTREAM_REPO_25U,
                "--repo", UPSTREAM_REPO_25U,
                "--base-branch", "cspu-25.0.4",
                "--version", "25.0.4.1",
                "--test-run");
        mergeLatestPR(github, UPSTREAM_REPO_25U, "cspu-25.0.4");
        verifySuitePy(new File(WORK_DIR, "test-fake-graalvm-community-jdk25u"), "25.0.4.1", true);

        System.out.println("\n[TEST] Executing CSPU Step 2: upstream-finalize (Empty Diff)");
        runOps("upstream-finalize",
                "--cspu",
                "--dir", new File(WORK_DIR, "test-fake-graalvm-community-jdk25u").getAbsolutePath(),
                "--fork", UPSTREAM_REPO_25U,
                "--repo", UPSTREAM_REPO_25U,
                "--base-branch", "cspu-25.0.4",
                "--version", "25.0.4.1",
                "--jdk-version", "25.0.4.1",
                "--upstream-remote", "origin",
                "--test-run");
        if (!github.getRepository(UPSTREAM_REPO_25U).getPullRequests(GHIssueState.OPEN).isEmpty()) {
            throw new RuntimeException("Empty CSPU shouldn't open an unmark PR.");
        }
        verifyTagExists(github, UPSTREAM_REPO_25U, "vm-25.0.4.1");

        System.out.println("\n[TEST] Executing CSPU Step 3: downstream-sync-mark (Empty Diff)");
        runOps("downstream-sync-mark",
                "--cspu",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/25.0-cspu-25.0.4",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO_25U + ".git",
                "--upstream-tag", "vm-25.0.4.1",
                "--test-run");
        mergeLatestPR(github, DOWNSTREAM_REPO, "mandrel/25.0-cspu-25.0.4");
        verifySuitePy(new File(WORK_DIR, "test-fake-mandrel"), "25.0.4.1", true);

        System.out.println("\n[TEST] Executing CSPU Step 4: downstream-finalize (Empty Diff)");
        runOps("downstream-finalize",
                "--cspu",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/25.0-cspu-25.0.4",
                "--version", "mandrel-25.0.4.1-Final",
                "--upstream-remote", "origin");
        verifyTagExists(github, DOWNSTREAM_REPO, "mandrel-25.0.4.1-Final");

        System.out.println("\n[TEST] Executing CSPU Step 5: publish-release (Empty Diff)");
        runOps("publish-release",
                "--cspu",
                "--repo", DOWNSTREAM_REPO,
                "--version", "mandrel-25.0.4.1-Final",
                "--prev-version", "mandrel-25.0.4.0-Final",
                "--upstream-repo", UPSTREAM_REPO_25U,
                "--upstream-tag", "vm-25.0.4.1",
                "--linux-build", String.valueOf(LINUX_BUILD_25_0_4_1),
                "--windows-build", String.valueOf(WINDOWS_BUILD_25_0_4_1),
                "--macos-build", String.valueOf(MACOS_BUILD_25_0_4_1),
                "--download-dir", ARTIFACTS_DIR.getAbsolutePath(),
                "-T", realTemplatePath);

        System.out.println("\n[TEST] Publishing the draft release so Step 6 can find it");
        publishDraftRelease(github, DOWNSTREAM_REPO, "Mandrel 25.0.4.1-Final CSPU");

        System.out.println("\n[TEST] Executing CSPU Step 6: update-quarkus-images (Empty Diff)");
        runOps("update-quarkus-images",
                "--cspu",
                "--dir", new File(WORK_DIR, "test-fake-quarkus-images").getAbsolutePath(),
                "--month", "April",
                "--version", "mandrel-25.0.4.1-Final,graal-25.3.4.1",
                "--prev-version", "mandrel-25.0.4.0-Final,graal-25.2.4",
                "--download-dir", ARTIFACTS_DIR.getAbsolutePath(),
                "--fork", QUARKUS_REPO,
                "--upstream-repo", QUARKUS_REPO,
                "--base-branch", quarkusBase,
                "--mandrel-repo", DOWNSTREAM_REPO,
                "--test-run");
        mergeLatestPR(github, QUARKUS_REPO, quarkusBase);
        verifyQuarkusYaml(new File(WORK_DIR, "test-fake-quarkus-images"), "25.0.4.1-Final");
        verifyGraalvmYaml(new File(WORK_DIR, "test-fake-quarkus-images"), "graal-25.3.4.1", "25.0.4.1");

        final GHRelease cspuRel = github.getRepository(DOWNSTREAM_REPO).getReleaseByTagName("mandrel-25.0.4.1-Final");
        if (!cspuRel.getBody().contains("This is a Critical Security Patch Update (CSPU).")) {
            throw new RuntimeException("CSPU flag did not inject CSPU_HEADER properly.");
        }
        if (!cspuRel.getBody().contains("otherwise identical to 25.0.4.0")) {
            throw new RuntimeException("Empty CSPU diff detector failed to output empty diff sentence.");
        }
        if (!cspuRel.getBody().contains("empty changelog")) {
            throw new RuntimeException("Empty CSPU diff detector failed to output empty changelog section.");
        }
        System.out.println("   [OK] Verified CSPU Release body text formatting.");

        //  NON-EMPTY CSPU FLOW TESTS
        // i.e. There are some patches for this CSPU besides just OpenJDK rebuild.
        System.out.println("\n[TEST] Preparing isolated branches for CSPU 25.0.4.2 (Non-Empty)...");
        prepareCspuBranches("test-fake-graalvm-community-jdk25u", "vm-25.0.4.1", "cspu-25.0.4.1");
        prepareCspuBranches("test-fake-mandrel", "mandrel-25.0.4.1-Final", "mandrel/25.0-cspu-25.0.4.1");

        System.out.println("\n[TEST] Injecting code changes to trigger non-empty CSPU logic...");
        try (Git git = Git.open(new File(WORK_DIR, "test-fake-graalvm-community-jdk25u"))) {
            git.checkout().setName("cspu-25.0.4.1").call();
            Files.writeString(new File(WORK_DIR, "test-fake-graalvm-community-jdk25u/cve-fix.txt").toPath(), "Very secret CVE Fixed", StandardCharsets.UTF_8);
            git.add().addFilepattern("cve-fix.txt").call();
            git.commit().setSign(false).setMessage("Fix major CVE").call();
            git.push().setForce(true).setRemote("origin").add("cspu-25.0.4.1").call();
        }

        System.out.println("\n[TEST] Executing CSPU Step 1: upstream-mark (Non-Empty)");
        runOps("upstream-mark",
                "--cspu",
                "--dir", new File(WORK_DIR, "test-fake-graalvm-community-jdk25u").getAbsolutePath(),
                "--fork", UPSTREAM_REPO_25U,
                "--repo", UPSTREAM_REPO_25U,
                "--base-branch", "cspu-25.0.4.1",
                "--version", "25.0.4.2",
                "--test-run");
        final GHPullRequest markPr = github.getRepository(UPSTREAM_REPO_25U).getPullRequests(GHIssueState.OPEN).getFirst();
        if (markPr.getTitle().startsWith("Bump version")) {
            throw new RuntimeException("Non-Empty CSPU falsely triggered the Empty PR title flow.");
        }
        System.out.println("   [OK] Verified Non-Empty CSPU PR uses traditional title.");
        mergeLatestPR(github, UPSTREAM_REPO_25U, "cspu-25.0.4.1");

        System.out.println("\n[TEST] Executing Auxiliary Step: tag-mandrel");
        runOps("tag-mandrel",
                "--dir", new File(WORK_DIR, "test-fake-mandrel-packaging").getAbsolutePath(),
                "--branch", "23.1",
                "--version", "mandrel-23.1.12.0-Final",
                "-u", "origin");
        verifyTagExists(github, PACKAGING_REPO, "mandrel-23.1.12.0-Final");

        System.out.println("\n[TEST] End-to-end workflow completed successfully.");
        System.out.println("\nLocal repositories have been left in " + WORK_DIR.getAbsolutePath());
    }

    private static void clearStaleGlobalGitLocks() throws IOException {
        final String home = System.getProperty("user.home");
        if (home == null) {
            return;
        }
        Files.deleteIfExists(Path.of(home, ".config/jgit/config.lock"));
        Files.deleteIfExists(Path.of(home, ".gitconfig.lock"));
    }

    private static void prepareCspuBranches(String repoName, String tag, String branch) throws Exception {
        try (Git git = Git.open(new File(WORK_DIR, repoName))) {
            final ObjectId commitId = git.getRepository().resolve("refs/tags/" + tag + "^{commit}");
            git.checkout().setCreateBranch(true).setName(branch).setStartPoint(commitId.getName()).call();
            git.push().setForce(true).setRemote("origin").add(branch).call();
        }
    }

    /**
     * Addresses the issue when milestones were silently left open.
     */
    private static void verifyMilestoneClosed(GitHub github, String repoName, String milestoneTitle) throws Exception {
        final GHRepository repo = github.getRepository(repoName);
        for (GHMilestone ms : repo.listMilestones(GHIssueState.OPEN)) {
            if (ms.getTitle().equals(milestoneTitle)) {
                throw new RuntimeException("Milestone " + milestoneTitle + " was not closed properly.");
            }
        }
        System.out.println("   [OK] Verified milestone " + milestoneTitle + " is completely closed.");
    }

    private static PrInfo createAndMergeMultiCommitUpstreamPR(GitHub github, String repoName, String baseBranch, String branchPrefix) throws Exception {
        final File localClone = new File(WORK_DIR, repoName.split("/")[1]);
        try (Git git = Git.open(localClone)) {
            git.checkout().setName(baseBranch).call();
            git.pull().setRemote("origin").call();
            // record the SHA right before our new PR to use as the --since marker
            final String sinceSha = git.getRepository().resolve("HEAD").getName();
            final String workBranch = branchPrefix + "-" + System.currentTimeMillis();
            git.checkout().setCreateBranch(true).setName(workBranch).setStartPoint(baseBranch).call();
            Files.writeString(new File(localClone, workBranch + "-1.txt").toPath(), "Content 1", StandardCharsets.UTF_8);
            git.add().addFilepattern(workBranch + "-1.txt").call();
            git.commit().setSign(false).setMessage("Add " + workBranch + " commit 1").call();
            Files.writeString(new File(localClone, workBranch + "-2.txt").toPath(), "Content 2", StandardCharsets.UTF_8);
            git.add().addFilepattern(workBranch + "-2.txt").call();
            git.commit().setSign(false).setMessage("Add " + workBranch + " commit 2").call();
            // Due to cspu we have more branches now.
            git.push().setForce(true).setRemote("origin").add(workBranch).call();
            final GHRepository repo = github.getRepository(repoName);
            final GHPullRequest pr = repo.createPullRequest("Add multi-commit feature " + branchPrefix, workBranch, baseBranch, "Feature body", true, false);
            pr.merge("Merge pull request #" + pr.getNumber() + " from " + GH_ORG + "/" + workBranch + "\n\nAdd multi-commit feature " + branchPrefix);
            return new PrInfo(sinceSha, pr.getNumber());
        }
    }

    private static void setupFakeRepos(GitHub github) throws Exception {
        System.out.println("\n[SETUP] Wiping local workspace and forcing pristine remote state...");
        if (WORK_DIR.exists()) {
            deleteRecursively(WORK_DIR.toPath());
        }
        if (!WORK_DIR.mkdirs()) {
            throw new RuntimeException("Failed to create work dir");
        }
        if (!ARTIFACTS_DIR.exists() && !ARTIFACTS_DIR.mkdirs()) {
            throw new RuntimeException("Failed to create artifacts dir");
        }
        setupUpstream21(github);
        setupUpstream25(github);
        setupDownstream(github);
        setupPackaging(github);
        setupQuarkus(github);
        System.out.println("[SETUP] Fake repositories completely generated with credible baseline history.");
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    System.err.println("ERROR: Could not delete " + p);
                }
            });
        }
    }

    private static void configureGitUser(Git git) throws Exception {
        final StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", GH_NAME);
        config.setString("user", null, "email", GH_EMAIL);
        config.setString("user", null, "signingkey", GH_PGP);
        config.setString("gpg", null, "format", "openpgp");
        config.save();
    }

    private static GHRepository getOrCreateAndCleanRepo(GitHub github, String name, String baseBranch) throws Exception {
        final GHRepository repo;
        try {
            repo = github.getRepository(GH_ORG + "/" + name);
            System.out.println("   Found existing remote repository: " + name + ". Scrubbing it...");
        } catch (GHFileNotFoundException e) {
            System.out.println("   Creating remote repository: " + name);
            return github.createRepository(name).description("Fake repo for Mandrel testing").private_(false).create();
        }
        try {
            for (GHPullRequest pr : repo.getPullRequests(GHIssueState.OPEN)) {
                pr.close();
            }
        } catch (Exception ignored) {
            //no-op
        }
        try {
            for (GHRelease rel : repo.listReleases()) {
                rel.delete();
            }
        } catch (Exception ignored) {
            //no-op
        }
        try {
            for (GHMilestone ms : repo.listMilestones(GHIssueState.OPEN)) {
                ms.delete();
            }
        } catch (Exception ignored) {
            //no-op
        }
        try {
            for (GHMilestone ms : repo.listMilestones(GHIssueState.CLOSED)) {
                ms.delete();
            }
        } catch (Exception ignored) {
            //no-op
        }
        try {
            for (GHRef ref : repo.getRefs("tags")) {
                ref.delete();
            }
        } catch (Exception ignored) {
            //no-op
        }
        try {
            for (GHRef ref : repo.getRefs("heads")) {
                final String branchName = ref.getRef().replace("refs/heads/", "");
                if (!branchName.equals(baseBranch) && !branchName.equals("main") && !branchName.equals("master")) {
                    ref.delete();
                }
            }
        } catch (Exception ignored) {
            //no-op
        }
        return repo;
    }

    private static void setupUpstream21(GitHub github) throws Exception {
        final String repoName = "test-fake-graalvm-community-jdk21u";
        final GHRepository repo = getOrCreateAndCleanRepo(github, repoName, "master");
        final File local = new File(WORK_DIR, repoName);
        try (Git git = Git.init().setDirectory(local).setInitialBranch("master").call()) {
            configureGitUser(git);
            final File suiteDir = new File(local, "compiler/mx.compiler");
            suiteDir.mkdirs();
            final File suite = new File(suiteDir, "suite.py");
            final File wasmDir = new File(local, "wasm/mx.wasm");
            wasmDir.mkdirs();
            final File wasmSuite = new File(wasmDir, "suite.py");
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"23.1.11\",\n  \"release\" : True,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"23.1.11\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Release 23.1.11").call();
            git.tag().setName("vm-23.1.11").setSigned(false).setForceUpdate(true).call();
            git.tag().setName("jdk-21.0.11").setSigned(false).setForceUpdate(true).call();
            git.tag().setName("initial-pristine-state").setSigned(false).setForceUpdate(true).call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"23.1.12\",\n  \"release\" : False,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"23.1.12\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Bump version to 23.1.12").call();
            git.remoteAdd().setName("origin").setUri(new URIish("git@github.com:" + GH_ORG + "/" + repoName + ".git")).call();
            git.push().setForce(true).setPushAll().setPushTags().call();
            repo.createMilestone("23.1.12", "Dummy milestone");
        }
    }

    private static void setupUpstream25(GitHub github) throws Exception {
        final String repoName = "test-fake-graalvm-community-jdk25u";
        final GHRepository repo = getOrCreateAndCleanRepo(github, repoName, "master");
        final File local = new File(WORK_DIR, repoName);
        try (Git git = Git.init().setDirectory(local).setInitialBranch("master").call()) {
            configureGitUser(git);
            final File suiteDir = new File(local, "compiler/mx.compiler");
            suiteDir.mkdirs();
            final File suite = new File(suiteDir, "suite.py");
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"25.0.4\",\n  \"release\" : True,\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Release 25.0.4").call();
            git.tag().setName("vm-25.0.4").setSigned(false).setForceUpdate(true).call();
            git.tag().setName("jdk-25.0.4").setSigned(false).setForceUpdate(true).call();
            git.tag().setName("initial-pristine-state").setSigned(false).setForceUpdate(true).call();
            git.checkout().setCreateBranch(true).setName("cspu-25.0.4").setStartPoint("HEAD").call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"25.0.4.1\",\n  \"release\" : True,\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Bump version for CSPU 25.0.4.1 [skip ci]").call();
            git.checkout().setName("master").call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"25.0.5\",\n  \"release\" : False,\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Bump version to 25.0.5").call();
            git.remoteAdd().setName("origin").setUri(new URIish("git@github.com:" + GH_ORG + "/" + repoName + ".git")).call();
            git.push().setForce(true).setPushAll().setPushTags().call();
            repo.createMilestone("25.0.5", "Dummy milestone");
        }
    }

    private static void setupDownstream(GitHub github) throws Exception {
        final String repoName = "test-fake-mandrel";
        final GHRepository repo = getOrCreateAndCleanRepo(github, repoName, "mandrel/23.1");
        final File local = new File(WORK_DIR, repoName);
        try (Git git = Git.init().setDirectory(local).setInitialBranch("mandrel/23.1").call()) {
            configureGitUser(git);
            final File suiteDir = new File(local, "compiler/mx.compiler");
            suiteDir.mkdirs();
            final File suite = new File(suiteDir, "suite.py");
            final File wasmDir = new File(local, "wasm/mx.wasm");
            wasmDir.mkdirs();
            final File wasmSuite = new File(wasmDir, "suite.py");
            // 23.1 Branch
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"23.1.11.0\",\n  \"release\" : True,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"23.1.11.0\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Release 23.1.11.0").call();
            git.tag().setName("mandrel-23.1.11.0-Final").setSigned(false).setForceUpdate(true).call();
            git.tag().setName("initial-pristine-state").setSigned(false).setForceUpdate(true).call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"23.1.12.0\",\n  \"release\" : False,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"23.1.12.0\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Bump to 23.1.12.0").call();
            repo.createMilestone("23.1.12.0-Final", "Dummy milestone");
            repo.createRelease("mandrel-23.1.11.0-Final").name("Mandrel 23.1.11.0").body("OpenJDK used: 21.0.11\n").create();
            // 25.0 Branch
            git.checkout().setCreateBranch(true).setName("mandrel/25.0").setStartPoint("HEAD").call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"25.0.4.0\",\n  \"release\" : True,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"25.0.4.0\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Release 25.0.4.0").call();
            git.tag().setName("mandrel-25.0.4.0-Final").setSigned(false).setForceUpdate(true).call();
            git.checkout().setCreateBranch(true).setName("mandrel/25.0-cspu-25.0.4").setStartPoint("HEAD").call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"25.0.4.1\",\n  \"release\" : True,\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("[Backport] Bump version for CSPU 25.0.4.1 [skip ci]").call();
            git.checkout().setName("mandrel/25.0").call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"25.0.5.0\",\n  \"release\" : False,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"25.0.5.0\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Bump to 25.0.5.0").call();
            repo.createMilestone("25.0.5.0-Final", "Dummy milestone");
            repo.createRelease("mandrel-25.0.4.0-Final").name("Mandrel 25.0.4.0").body("OpenJDK used: 25.0.4\n").create();
            // Check out 23.1 so the overarching test execution cleanly evaluates 23.1.12 flow first
            git.checkout().setName("mandrel/23.1").call();
            git.remoteAdd().setName("origin").setUri(new URIish("git@github.com:" + GH_ORG + "/" + repoName + ".git")).call();
            git.push().setForce(true).setPushAll().setPushTags().call();
        }
    }

    private static void setupPackaging(GitHub github) throws Exception {
        final String repoName = "test-fake-mandrel-packaging";
        getOrCreateAndCleanRepo(github, repoName, "23.1");
        final File local = new File(WORK_DIR, repoName);
        try (Git git = Git.init().setDirectory(local).setInitialBranch("23.1").call()) {
            configureGitUser(git);
            final File dummyFile = new File(local, "README.md");
            Files.writeString(dummyFile.toPath(), "# Fake Mandrel Packaging\n", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Initial packaging state").call();
            git.tag().setName("initial-pristine-state").setSigned(false).setForceUpdate(true).call();
            git.remoteAdd().setName("origin").setUri(new URIish("git@github.com:" + GH_ORG + "/" + repoName + ".git")).call();
            git.push().setForce(true).setPushAll().setPushTags().call();
        }
    }

    private static void setupQuarkus(GitHub github) throws Exception {
        final String repoName = "test-fake-quarkus-images";
        getOrCreateAndCleanRepo(github, repoName, "main");
        final File local = new File(WORK_DIR, repoName);
        try (Git git = Git.init().setDirectory(local).setInitialBranch("main").call()) {
            configureGitUser(git);
            final File yamlDir = new File(local, "quarkus-mandrel-builder-image");
            yamlDir.mkdirs();
            final File yaml = new File(yamlDir, "mandrel.yaml");
            final String yamlContent = """
                    images:
                      # https://github.com/graalvm/mandrel/releases/tag/mandrel-23.1.11.0-Final
                      - graalvm-version: 23.1.11.0-Final
                        java-version: 21
                        tags: 23.1-java21, jdk-21.0.11
                        variants:
                          - sha: fakesha12345
                            arch: amd64
                          - sha: fakesha67890
                            arch: arm64
                      # https://github.com/graalvm/mandrel/releases/tag/mandrel-25.0.4.0-Final
                      - graalvm-version: 25.0.4.0-Final
                        java-version: 25
                        tags: 25.0-java25, jdk-25.0.4
                        variants:
                          - sha: fakesha12345
                            arch: amd64
                          - sha: fakesha67890
                            arch: arm64
                    """;
            Files.writeString(yaml.toPath(), yamlContent, StandardCharsets.UTF_8);
            final String graalYamlContent = """
                    images:
                      # https://github.com/graalvm/graalvm-ce-builds/releases/tag/graal-25.2.4
                      - graalvm-version: 25i2
                        graalvm-release-tag: graal-25.2.4
                        java-version: 25.0.4
                        tags: jdk-25, 25.2.4-jdk-25
                        variants:
                          - arch: amd64
                            sha: 3f4a89de8eaa96f2ed677f09957c7e872cd8467aad3537f8b5394c1b8c4b942e
                          - arch: arm64
                            sha: 22286f7ecd21b9aedb3226b9bf797469e1bd3eefc491e12ef3dd49b452d230b7
                    """;
            final File s2iDir = new File(local, "quarkus-native-s2i");
            s2iDir.mkdirs();
            Files.writeString(new File(s2iDir, "graalvm.yaml").toPath(), graalYamlContent, StandardCharsets.UTF_8);
            final File gbiDir = new File(local, "quarkus-graalvm-builder-image");
            gbiDir.mkdirs();
            Files.writeString(new File(gbiDir, "graalvm.yaml").toPath(), graalYamlContent, StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Initial quarkus state").call();
            git.tag().setName("initial-pristine-state").setSigned(false).setForceUpdate(true).call();
            git.remoteAdd().setName("origin").setUri(new URIish("git@github.com:" + GH_ORG + "/" + repoName + ".git")).call();
            git.push().setForce(true).setPushAll().setPushTags().call();
        }
    }

    private static void verifySuitePy(File repoDir, String expectedVersion, boolean expectedRelease) throws Exception {
        final File suitePy = new File(repoDir, "compiler/mx.compiler/suite.py");
        final String content = Files.readString(suitePy.toPath());
        if (!content.contains("\"version\" : \"" + expectedVersion + "\"")) {
            throw new RuntimeException("Verification failed: Expected version " + expectedVersion + " in suite.py");
        }
        final String releaseStr = expectedRelease ? "True" : "False";
        if (!content.contains("\"release\" : " + releaseStr)) {
            throw new RuntimeException("Verification failed: Expected release:" + releaseStr + " in suite.py");
        }
        System.out.println("   [OK] Verified suite.py (version=" + expectedVersion + ", release=" + releaseStr + ")");
    }

    private static void verifyTagExists(GitHub github, String repoName, String tagName) throws Exception {
        final GHRepository repo = github.getRepository(repoName);
        for (int i = 0; i < 5; i++) {
            for (final GHRef ref : repo.getRefs("tags")) {
                if (ref.getRef().endsWith("refs/tags/" + tagName)) {
                    System.out.println("   [OK] Verified tag exists remotely: " + tagName);
                    return;
                }
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("Verification failed: Could not find tag " + tagName + " in " + repoName);
    }

    private static void verifyQuarkusYaml(File repoDir, String expectedVersionTag) throws Exception {
        final File yamlFile = new File(repoDir, "quarkus-mandrel-builder-image/mandrel.yaml");
        final String content = Files.readString(yamlFile.toPath());
        if (!content.contains(expectedVersionTag)) {
            throw new RuntimeException("Verification failed: Expected " + expectedVersionTag + " in mandrel.yaml");
        }
        if (!content.contains("releases/tag/mandrel-" + expectedVersionTag)) {
            throw new RuntimeException("Verification failed: Expected URL to mandrel-" + expectedVersionTag + " in " + yamlFile.getAbsolutePath());
        }
        System.out.println("   [OK] Verified mandrel.yaml contains " + expectedVersionTag + " and its URL");
    }

    private static void verifyGraalvmYaml(File repoDir, String expectedReleaseTag, String expectedJavaVersion) throws Exception {
        for (String dir : List.of("quarkus-native-s2i", "quarkus-graalvm-builder-image")) {
            final File yamlFile = new File(repoDir, dir + "/graalvm.yaml");
            final String content = Files.readString(yamlFile.toPath());
            if (!content.contains("graalvm-release-tag: " + expectedReleaseTag)) {
                throw new RuntimeException("Verification failed: Expected " + expectedReleaseTag + " in " + dir + "/graalvm.yaml");
            }
            if (!content.contains("releases/tag/" + expectedReleaseTag)) {
                throw new RuntimeException("Verification failed: Expected URL to " + expectedReleaseTag + " in " + dir + "/graalvm.yaml");
            }
            if (!content.contains("java-version: " + expectedJavaVersion)) {
                throw new RuntimeException("Verification failed: Expected java-version: " + expectedJavaVersion + " in " + dir + "/graalvm.yaml");
            }
            System.out.println("   [OK] Verified " + dir + "/graalvm.yaml contains " + expectedReleaseTag + " and its URL");
        }
    }

    private static void publishDraftRelease(GitHub github, String repoName, String releaseName) throws Exception {
        final GHRepository repo = github.getRepository(repoName);
        boolean found = false;
        for (GHRelease release : repo.listReleases()) {
            if (release.isDraft() && release.getName().equals(releaseName)) {
                release.update().draft(false).update();
                System.out.println("   [OK] Successfully published draft release: " + releaseName);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new RuntimeException("Could not find draft release named: " + releaseName);
        }
    }

    private static String getBaseBranch(File localClone) throws Exception {
        try (Git git = Git.open(localClone)) {
            if (git.getRepository().exactRef("refs/heads/master") != null) {
                return "master";
            }
            if (git.getRepository().exactRef("refs/heads/main") != null) {
                return "main";
            }
            return "master";
        }
    }

    private static void mergeLatestPR(GitHub github, String repoName, String branchName) throws Exception {
        final GHRepository repo = github.getRepository(repoName);
        final List<GHPullRequest> prs = repo.getPullRequests(GHIssueState.OPEN);
        if (prs.isEmpty()) {
            throw new RuntimeException("No open PRs found in " + repoName + " to merge.");
        }
        final GHPullRequest pr = prs.getFirst();
        System.out.println("Merging PR: " + pr.getTitle());
        // formatted like a GitHub merge to test the regex
        pr.merge("Merge pull request #" + pr.getNumber() + " from " + GH_ORG + "/test-branch\n\n" + pr.getTitle());
        final File localClone = new File(WORK_DIR, repoName.split("/")[1]);
        try (Git git = Git.open(localClone)) {
            // ensure we are on the base branch before pulling
            git.checkout().setName(branchName).call();
            git.pull().setRemote("origin").call();
        }
    }

    private static void runOps(String... args) throws Exception {
        final ProcessBuilder pb = new ProcessBuilder();
        final List<String> command = new ArrayList<>();
        command.add("jbang");
        command.add("mandrel-ops.java");
        command.addAll(List.of(args));
        pb.command(command);
        pb.inheritIO();
        final Process p = pb.start();
        if (p.waitFor() != 0) {
            throw new RuntimeException("mandrel-ops " + args[0] + " failed.");
        }
    }

    private static void runOpsExpectingFailure(String expectedErrorStr, String... args) throws Exception {
        final ProcessBuilder pb = new ProcessBuilder();
        final List<String> command = new ArrayList<>();
        command.add("jbang");
        command.add("mandrel-ops.java");
        command.addAll(List.of(args));
        pb.command(command);
        final File errLog = File.createTempFile("err", ".log");
        pb.redirectError(errLog);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        final Process p = pb.start();
        if (p.waitFor() == 0) {
            throw new RuntimeException("Expected mandrel-ops to fail, but it succeeded!");
        }
        final String errContent = Files.readString(errLog.toPath());
        if (!errContent.contains(expectedErrorStr)) {
            throw new RuntimeException("Expected error containing '" + expectedErrorStr + "' but got:\n" + errContent);
        }
        System.out.println("   [OK] Script correctly bailed out with expected error.");
        errLog.delete();
    }

    private static void verifyWasmSuitePy(File repoDir, String expectedVersion) throws Exception {
        final File suitePy = new File(repoDir, "wasm/mx.wasm/suite.py");
        if (!suitePy.exists()) {
            return;
        }
        final String content = Files.readString(suitePy.toPath());
        if (!content.contains("\"version\" : \"" + expectedVersion + "\"")) {
            throw new RuntimeException("Verification failed: Expected version " + expectedVersion + " in wasm suite.py");
        }
        if (content.contains("\"release\"")) {
            throw new RuntimeException("Verification failed: wasm suite.py should NOT contain release attribute.");
        }
        System.out.println("   [OK] Verified wasm suite.py (version=" + expectedVersion + ", no release attribute)");
    }
}
