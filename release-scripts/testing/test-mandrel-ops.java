//usr/bin/env jbang --ea "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.13.0.202109080827-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.pgm:5.13.0.202109080827-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.13.0.202109080827-r
//DEPS org.kohsuke:github-api:1.316

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.console.ConsoleCredentialsProvider;
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
 *
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
    static final String UPSTREAM_REPO = GH_ORG + "/test-fake-graalvm-community-jdk21u";
    static final String DOWNSTREAM_REPO = GH_ORG + "/test-fake-mandrel";
    static final String PACKAGING_REPO = GH_ORG + "/test-fake-mandrel-packaging";
    static final String QUARKUS_REPO = GH_ORG + "/test-fake-quarkus-images";

    static final File WORK_DIR = new File("/tmp/mandrel-test-workspace");

    static class PrInfo {
        String sinceSha;
        int prNumber;

        PrInfo(String sinceSha, int prNumber) {
            this.sinceSha = sinceSha;
            this.prNumber = prNumber;
        }
    }

    public static void main(String[] args) throws Exception {
        ConsoleCredentialsProvider.install();
        System.out.println("Starting Integration Test for Mandrel Release Ops...");
        final GitHub github = GitHubBuilder.fromPropertyFile().build();

        setupFakeRepos(github);

        final String upstreamBase = getBaseBranch(new File(WORK_DIR, "test-fake-graalvm-community-jdk21u"));
        final String quarkusBase = getBaseBranch(new File(WORK_DIR, "test-fake-quarkus-images"));

        // Flow:
        // Upstream: 23.1.10 previous -> 23.1.11 current release -> 23.1.12 next
        // Downstream: 23.1.11.0 -> 23.1.12.0
        // Quarkus images: 23.1.10.0-Final -> 23.1.11.0-Final

        // STEP 1: Upstream Mark
        System.out.println("\n[TEST] Executing Step 1: upstream-mark");
        runOps("upstream-mark",
                "--dir", new File(WORK_DIR, "test-fake-graalvm-community-jdk21u").getAbsolutePath(),
                "--fork", UPSTREAM_REPO,
                "--repo", UPSTREAM_REPO,
                "--base-branch", upstreamBase,
                "--version", "23.1.11",
                "--test-run");
        mergeLatestPR(github, UPSTREAM_REPO, upstreamBase);
        verifySuitePy(new File(WORK_DIR, "test-fake-graalvm-community-jdk21u"), "23.1.11", true);

        // STEP 2: Upstream Finalize
        System.out.println("\n[TEST] Executing Step 2: upstream-finalize");
        runOps("upstream-finalize",
                "--dir", new File(WORK_DIR, "test-fake-graalvm-community-jdk21u").getAbsolutePath(),
                "--fork", UPSTREAM_REPO,
                "--repo", UPSTREAM_REPO,
                "--base-branch", upstreamBase,
                "--version", "23.1.11",
                "--jdk-version", "21.0.11",
                "--upstream-remote", "origin",
                "--test-run");
        mergeLatestPR(github, UPSTREAM_REPO, upstreamBase);
        verifySuitePy(new File(WORK_DIR, "test-fake-graalvm-community-jdk21u"), "23.1.12", false);
        verifyTagExists(github, UPSTREAM_REPO, "vm-23.1.11");
        verifyTagExists(github, UPSTREAM_REPO, "jdk-21.0.11");

        // STEP 3: Downstream Sync Mark
        System.out.println("\n[TEST] Executing Step 3: downstream-sync-mark");
        runOps("downstream-sync-mark",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO + ".git",
                "--upstream-tag", "vm-23.1.11",
                "--test-run");
        mergeLatestPR(github, DOWNSTREAM_REPO, "mandrel/23.1");
        verifySuitePy(new File(WORK_DIR, "test-fake-mandrel"), "23.1.11.0", true);
        verifyWasmSuitePy(new File(WORK_DIR, "test-fake-mandrel"), "23.1.11.0");

        // STEP 4: Downstream Finalize
        System.out.println("\n[TEST] Executing Step 4: downstream-finalize");
        runOps("downstream-finalize",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--version", "mandrel-23.1.11.0-Final",
                "--upstream-remote", "origin");
        verifyTagExists(github, DOWNSTREAM_REPO, "mandrel-23.1.11.0-Final");

        // STEP 5: Publish Release
        System.out.println("\n[TEST] Executing Step 5: publish-release");
        final File artifactsDir = new File(WORK_DIR, "artifacts");
        runOps("publish-release",
                "--repo", DOWNSTREAM_REPO,
                "--version", "mandrel-23.1.11.0-Final",
                "--prev-version", "mandrel-23.1.10.0-Final",
                "--upstream-repo", UPSTREAM_REPO,
                "--upstream-tag", "vm-23.1.11",
                "--jdk-version", "21.0.11",
                "--linux-build", "331",
                "--windows-build", "216",
                "--macos-build", "237",
                "--download-dir", artifactsDir.getAbsolutePath(),
                "-T", new File(WORK_DIR, "release-template.md").getAbsolutePath());

        System.out.println("\n[TEST] Publishing the draft release so Step 6 can find it");
        publishDraftRelease(github, DOWNSTREAM_REPO, "Mandrel 23.1.11.0-Final");

        // STEP 6: Update Quarkus Images
        System.out.println("\n[TEST] Executing Step 6: update-quarkus-images");
        runOps("update-quarkus-images",
                "--dir", new File(WORK_DIR, "test-fake-quarkus-images").getAbsolutePath(),
                "--month", "April",
                "--version", "mandrel-23.1.11.0-Final",
                "--prev-version", "mandrel-23.1.10.0-Final",
                "--download-dir", artifactsDir.getAbsolutePath(),
                "--fork", QUARKUS_REPO,
                "--upstream-repo", QUARKUS_REPO,
                "--base-branch", quarkusBase,
                "--mandrel-repo", DOWNSTREAM_REPO,
                "--test-run");
        mergeLatestPR(github, QUARKUS_REPO, quarkusBase);
        verifyQuarkusYaml(new File(WORK_DIR, "test-fake-quarkus-images"), "23.1.11.0-Final");

        // STEP 7: Sync Upstream (Post-Release)
        System.out.println("\n[TEST] Executing Step 7: sync-upstream");
        runOps("sync-upstream",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO + ".git",
                "--upstream-branch", upstreamBase,
                "--next-version", "23.1.12.0",
                "--test-run");

        final GHRepository downstreamGhRepo = github.getRepository(DOWNSTREAM_REPO);
        final GHPullRequest syncPr = downstreamGhRepo.getPullRequests(GHIssueState.OPEN).getFirst();
        if (!syncPr.getBody().contains("/pull/")) {
            throw new RuntimeException(
                    "Verification failed: Sync PR body does not contain upstream PR links! Body:\n" + syncPr.getBody());
        }
        System.out.println("   [OK] Verified Sync PR body contains upstream PR links.");
        mergeLatestPR(github, DOWNSTREAM_REPO, "mandrel/23.1");
        verifySuitePy(new File(WORK_DIR, "test-fake-mandrel"), "23.1.12.0", false);
        verifyWasmSuitePy(new File(WORK_DIR, "test-fake-mandrel"), "23.1.12.0");

        // STEP 8: Second Sync Upstream (Verifying explicit --since)
        System.out.println("\n[TEST] Simulating further upstream development (feature-A)...");
        final PrInfo featureA = createAndMergeMultiCommitUpstreamPR(github, UPSTREAM_REPO, upstreamBase, "feature-A");

        System.out.println("\n[TEST] Executing Step 8: sync-upstream (Second sync with explicit --since)");
        runOps("sync-upstream",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO + ".git",
                "--upstream-branch", upstreamBase,
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
        final PrInfo featureB = createAndMergeMultiCommitUpstreamPR(github, UPSTREAM_REPO, upstreamBase, "feature-B");

        System.out.println("\n[TEST] Executing Step 9: sync-upstream (Third sync without --since)");
        runOps("sync-upstream",
                "--dir", new File(WORK_DIR, "test-fake-mandrel").getAbsolutePath(),
                "--fork", DOWNSTREAM_REPO,
                "--repo", DOWNSTREAM_REPO,
                "--base-branch", "mandrel/23.1",
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO + ".git",
                "--upstream-branch", upstreamBase,
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
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO + ".git",
                "--upstream-branch", upstreamBase,
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
                "--upstream-url", "https://github.com/" + UPSTREAM_REPO + ".git",
                "--upstream-branch", upstreamBase,
                "--test-run");
        try (Git git = Git.open(new File(WORK_DIR, "test-fake-mandrel"))) {
            git.checkout().setName("mandrel/23.1").call();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call();
        }

        // AUX STEP: Tag Mandrel Packaging
        System.out.println("\n[TEST] Executing Auxiliary Step: tag-mandrel");
        runOps("tag-mandrel",
                "--dir", new File(WORK_DIR, "test-fake-mandrel-packaging").getAbsolutePath(),
                "--branch", "23.1",
                "--version", "mandrel-23.1.11.0-Final",
                "-u", "origin");
        verifyTagExists(github, PACKAGING_REPO, "mandrel-23.1.11.0-Final");

        System.out.println("\n[TEST] End-to-end workflow completed successfully.");
        System.out.println("\nLocal repositories have been left in " + WORK_DIR.getAbsolutePath());
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
            git.push().setRemote("origin").add(workBranch).call();
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
        setupUpstream(github);
        setupDownstream(github);
        setupPackaging(github);
        setupQuarkus(github);
        Files.writeString(new File(WORK_DIR, "release-template.md").toPath(),
                "Mandrel {{FULL_VERSION}}\nOpenJDK used: {{JDK_VERSION}}\n", StandardCharsets.UTF_8);
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

    private static void setupUpstream(GitHub github) throws Exception {
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
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"23.1.10\",\n  \"release\" : True,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"23.1.10\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Release 23.1.10").call();
            git.tag().setName("vm-23.1.10").setSigned(false).setForceUpdate(true).call();
            git.tag().setName("jdk-21.0.10").setSigned(false).setForceUpdate(true).call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"23.1.11\",\n  \"release\" : False,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"23.1.11\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Bump version to 23.1.11").call();
            git.tag().setName("initial-pristine-state").setSigned(false).setForceUpdate(true).call();
            git.remoteAdd().setName("origin").setUri(new URIish("git@github.com:" + GH_ORG + "/" + repoName + ".git")).call();
            git.push().setForce(true).setPushAll().setPushTags().call();
            repo.createMilestone("23.1.11", "Dummy milestone");
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
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"23.1.10.0\",\n  \"release\" : True,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"23.1.10.0\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Release 23.1.10.0").call();
            git.tag().setName("mandrel-23.1.10.0-Final").setSigned(false).setForceUpdate(true).call();
            Files.writeString(suite.toPath(), "suite = {\n  \"version\" : \"23.1.11.0\",\n  \"release\" : False,\n}", StandardCharsets.UTF_8);
            Files.writeString(wasmSuite.toPath(), "suite = {\n  \"version\" : \"23.1.11.0\",\n}", StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("Bump to 23.1.11.0").call();
            git.tag().setName("initial-pristine-state").setSigned(false).setForceUpdate(true).call();
            git.remoteAdd().setName("origin").setUri(new URIish("git@github.com:" + GH_ORG + "/" + repoName + ".git")).call();
            git.push().setForce(true).setPushAll().setPushTags().call();
            repo.createMilestone("23.1.11.0-Final", "Dummy milestone");
            repo.createRelease("mandrel-23.1.10.0-Final").name("Mandrel 23.1.10.0").body("OpenJDK used: 21.0.10\n").create();
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
                      - graalvm-version: 23.1.10.0-Final
                        java-version: 21
                        tags: 23.1-java21, jdk-21.0.10
                        variants:
                          - sha: fakesha12345
                            arch: amd64
                          - sha: fakesha67890
                            arch: arm64
                    """;
            Files.writeString(yaml.toPath(), yamlContent, StandardCharsets.UTF_8);
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
        System.out.println("   [OK] Verified mandrel.yaml contains " + expectedVersionTag);
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
