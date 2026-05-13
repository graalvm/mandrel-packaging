# Mandrel Release Operations (`mandrel-ops.java`)

This script orchestrates the cascading release workflow from the upstream `graalvm-community` repositories down to `mandrel`, and manages publishing releases to `mandrel` repository and to `Quarkus images` repository.

**Before you start:**
 * Make sure local repositories are clean and checked out to the proper branches, e.g. `master` for GraalVM Community repos, `mandrel/25.0` for Mandrel repo, `25.0` for Mandrel packaging repo and `main` for Quarkus Images repo.
 * Make sure each repo has your proper `git config user.signingkey`, email, name set.
 * Make sure the `graalvm-community` repos, where the process begins, are in the code freeze period, talk to your peers upstream, make sure people expect you to do this now :)
 * If in doubt, use `-D` (dry run) on any step to validate locally before affecting GitHub.

## Overview
1. **Initiate Upstream Release:** Run `upstream-mark`, see [Step 1](#step-1), to set `release: True` on the GraalVM Community repository. Wait for the generated PR to be reviewed and merged.

1. **Finalize Upstream:** Run `upstream-finalize`, see [Step 2](#step-2), to tag the upstream release, bump the version for the next cycle, and open the "Unmark suites" PR. As a matter of convention, the "Unmark suites" PR will be reviewed and merged **after** at least one vendor successfully builds and publishes a release.

1. **Sync Downstream:** Run `downstream-sync-mark`, see [Step 3](#step-3) to fetch the new upstream tag into the Mandrel repository, resolve version suffix conflicts, and open the Mark Release PR. Wait for this PR to be merged.

1. **Finalize Downstream:** Run `downstream-finalize`, see [Step 4](#step-4), to tag the Mandrel release and manage downstream milestones.

1. **Tag the packaging repo:** Run `tag-mandrel`, see [Aux Step](#aux-step), to tag the mandrel-packaging repo used to build Mandrel. You can do this yourself, no much to it. This script automates it though.

1. **Build:** At this point, you can use the Mandrel Jenkins to build and test the artifacts using the Mandrel and Mandrel Packaging tags and note the job run IDs for Windows, Linux and MacOS.

1. **Publish Artifacts:** Run `publish-release`, see [Step 5](#step-5) to download artifacts, validate OpenJDK versions, and create a draft GitHub Release.

1. **Verify and publish:** Read the draft release and click on **Publish** when the time is right.

1. **Update Quarkus Images:** Run `update-quarkus-images`, see [Step 6](#step-6) to update tags and SHA256 sums in the Quarkus images repository. A PR is opened for you.

1. **Sync Upstream:** Run `sync-upstream`, see [Step 7](#step-7). Use this right after a release to merge the upstream `master` into the downstream branch as it autoresolves `suite.py` conflicts **OR** use it routinely midcycle with the `--since` flag to pull in general upstream development without bloating the PR body with old links.

---

## <a id="step-1"></a>Step 1: Upstream Mark Release (`upstream-mark`)

Marks the upstream suites for release (`release: True`). Creates a branch and opens a "Mark suite files for release" PR on the `graalvm-community` repo.

**Example for 21u (23.1.11):**
```bash
./mandrel-ops.java upstream-mark \
  --dir /home/karm/workspaceRH/graal/21/graalvm-community-jdk21u \
  --fork Karm/graalvm-community-jdk21u \
  --repo graalvm/graalvm-community-jdk21u \
  --base-branch master \
  --version 23.1.11
```
*Wait for this PR to be reviewed and merged upstream before proceeding.*

---

## <a id="step-2"></a>Step 2: Upstream Finalize (`upstream-finalize`)

Once the Step 1 PR is merged, this command tags the commit, pushes the tags, advances milestones, unmarks suites (`release: False`), bumps the version, and opens the "Unmark suite files" PR upstream.

**Example for 21u (23.1.10 previous → 23.1.11 release → 23.1.12 next):**
```bash
./mandrel-ops.java upstream-finalize \
  --dir /home/karm/workspaceRH/graal/21/graalvm-community-jdk21u \
  --fork Karm/graalvm-community-jdk21u \
  --repo graalvm/graalvm-community-jdk21u \
  --base-branch master \
  --version 23.1.11 \
  --jdk-version 21.0.11 \
  --upstream-remote upstream
```
*Don't wait for this PR to be merged upstream. You may proceed to Step 3 simultaneously. As a matter of convention, we merge that PR when at least one vendor had a successful release.*

---

## <a id="step-3"></a>Step 3: Downstream Sync Mark (`downstream-sync-mark`)

Fetches the newly created upstream tag into the downstream `mandrel` repo. Automatically handles the merge and `suite.py` conflict resolution appending the 4th digit suffix, and opens the Mark Release PR on Mandrel.

**Example for 23.1:**
```bash
./mandrel-ops.java downstream-sync-mark \
  --dir /home/karm/tmp/mandrel_23.1 \
  --fork Karm/graal \
  --repo graalvm/mandrel \
  --base-branch mandrel/23.1 \
  --upstream-url https://github.com/graalvm/graalvm-community-jdk21u.git \
  --upstream-tag vm-23.1.11 \
  --suffix 0
```
*Wait for this PR to be reviewed and merged downstream before proceeding.*

---

## <a id="step-4"></a>Step 4: Downstream Finalize (`downstream-finalize`)

Completes the cycle for the `mandrel` repo. Tags the release and manages downstream milestones.

**Example for 23.1:**
```bash
./mandrel-ops.java downstream-finalize \
  --dir /home/karm/tmp/mandrel_23.1 \
  --repo graalvm/mandrel \
  --base-branch mandrel/23.1 \
  --version mandrel-23.1.11.0-Final \
  --upstream-remote origin
```

---

## <a id="step-5"></a>Step 5: Publish Draft Release (`publish-release`)

Downloads the release artifacts from Jenkins skipping already downloaded files, validates the OpenJDK version from downloaded `MANDREL.md`, and creates a draft release on GitHub. It relies on the presence of `release-template.md` in the execution directory (can be overridden via `-T /path/to/template.md`). Make sure you use the correct Jenkins build numbers, those that passed QA.

**Example for 25.0:**
```bash
./mandrel-ops.java publish-release \
  --repo graalvm/mandrel \
  --version mandrel-25.0.3.0-Final \
  --prev-version mandrel-25.0.2.0-Final \
  --upstream-repo graalvm/graalvm-community-jdk25u \
  --upstream-tag vm-25.0.3 \
  --linux-build 115 \
  --windows-build 74 \
  --macos-build 117 \
  --download-dir ./artifacts
```
*Review the draft release on GitHub and publish it when ready.*

---

## <a id="step-6"></a>Step 6: Update Quarkus Images (`update-quarkus-images`)

This step needs the GitHub Mandrel Release to be alrady Released, not a Draft. It updates `mandrel.yaml` in the `quarkus-images` repository with the new tags, versions, and sha256 checksums from the completed releases. Opens a PR upstream.

**Example for a dual release (25.0 & 23.1):**
```bash
./mandrel-ops.java update-quarkus-images \
  --dir /home/karm/workspaceRH/quarkus-images \
  --month April \
  --version mandrel-25.0.3.0-Final,mandrel-23.1.11.0-Final \
  --prev-version mandrel-25.0.2.0-Final,mandrel-23.1.10.0-Final \
  --download-dir ./artifacts \
  --fork Karm/quarkus-images \
  --upstream-repo quarkusio/quarkus-images
```
*Note: This reads the `.sha256` files created locally during [Step 5](#step-5), and infers JDK versions from the published Mandrel release via the GitHub API.*

---

## <a id="step-7"></a>Step 7: Sync Upstream (`sync-upstream`)

Sync-upstream is usable also outside release cycle. It merges the upstream branch e.g. `master` into the downstream branch and generates a standard sync PR populated with a list of the merged upstream PRs.

**Usage A: Post-release Sync**
Once the upstream `Unmark suites` PR is merged and the Mandrel release is fully published, run this to bring downstream up to speed. It autoresolves `suite.py` conflicts. Passing `--next-version` tells the script exactly what version to enforce in `suite.py`.

**Example (Post-Release):**
```bash
./mandrel-ops.java sync-upstream \
  --dir /home/karm/tmp/mandrel_23.1 \
  --fork Karm/graal \
  --repo graalvm/mandrel \
  --base-branch mandrel/23.1 \
  --next-version 23.1.12.0 \
  --upstream-url https://github.com/graalvm/graalvm-community-jdk21u.git \
  --upstream-branch master
```

**Usage B: Routine midcycle Sync**
You can use this command anytime during the development cycle to sync upstream changes. Use the `--since` (`-S`) flag and pass the last synced commit SHA or tag e.g., `vm-23.1.11` to prevent JGit from populating the generated PR body with old PR links you already merged last time. You can omit `--next-version` if you do not need to enforce `suite.py` overrides.

**Example (Routine Sync):**
```bash
./mandrel-ops.java sync-upstream \
  --dir /home/karm/tmp/mandrel_23.1 \
  --fork Karm/graal \
  --repo graalvm/mandrel \
  --base-branch mandrel/23.1 \
  --upstream-url https://github.com/graalvm/graalvm-community-jdk21u.git \
  --upstream-branch master \
  --since vm-23.1.11
```

---

## <a id="step-aux"></a>Aux Step: Tag Packaging Repo (`tag-mandrel`)

The `mandrel-packaging` repo does not use milestones or `suite.py` files. It only requires a direct tag sync.

**Example for Packaging 23.1:**
```bash
./mandrel-ops.java tag-mandrel \
  --dir /home/karm/workspaceRH/mandrel-packaging \
  --branch 23.1 \
  --version mandrel-23.1.11.0-Final \
  -u upstream
```

# Testing

It is rather cumbersome to test this properly. It requires signed tags, i.e. gpg infra, pushing to GitHub repositories, having local repositories, downloadoing and uploading artifacts etc. The way it is written now, you need to babysit the script and type in your gpg key passphrase each time jgit prompts you. 


The test script, [test-mandrel-ops.java](./testing/test-mandrel-ops.java) that calls [mandrel-ops.java](./mandrel-ops.java), creates fake **private** repositories with dummy content on your GitHub account and on your local disk. It has hardcoded 'Karm' account username in it out of paranoia; you change that to your own desired org or user. It requires a GH *Pro* plan due to the usage of private repos. The reason for private repos is merely to keep the testing mess hidden. It can work with public repos too if necessary. 


It runs stage by stage and really verifies that the script works. From signed tags to milestones opening and closing, including suite.py `release: true/false` conflicts resolutions, downloading real artifacts from Jenkins, publishing a GitHub Release, uploading those artifatcs to it etc. The only corner it cuts is assigning Reviewers. That is skipped for test runs.

It is not exhaustive. The main goal of the testing infra is to be able to locally verify that a change to the release script [mandrel-ops.java](./mandrel-ops.java) didn't outright blow everything up to smithereens. 

## Example of a test flow

```
$ ./testing/test-mandrel-ops.java
[jbang] Building jar for test-mandrel-ops.java...
Starting Integration Test for Mandrel Release Ops...

[SETUP] Wiping local workspace and forcing pristine remote state...
   Found existing remote repository: test-fake-graalvm-community-jdk21u. Scrubbing it...
   Found existing remote repository: test-fake-mandrel. Scrubbing it...
   Found existing remote repository: test-fake-mandrel-packaging. Scrubbing it...
   Found existing remote repository: test-fake-quarkus-images. Scrubbing it...
[SETUP] Fake repositories completely generated with credible baseline history.

[TEST] Executing Step 1: upstream-mark
[jbang] Building jar for mandrel-ops.java...
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 

Pushing branch to fork git@github.com:Karm/test-fake-graalvm-community-jdk21u.git
PR created: https://github.com/Karm/test-fake-graalvm-community-jdk21u/pull/21
Merging PR: Mark suite files for 23.1.11 release [skip ci]
   [OK] Verified suite.py (version=23.1.11, release=True)

[TEST] Executing Step 2: upstream-finalize
Auto-calculated next version: 23.1.12

Checking remote tags on origin...
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Created local tag: vm-23.1.11
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Created local tag: jdk-21.0.11
Pushing refs/tags/vm-23.1.11 to origin
   [OK] Pushed refs/tags/vm-23.1.11 (OK)
Pushing refs/tags/jdk-21.0.11 to origin
   [OK] Pushed refs/tags/jdk-21.0.11 (OK)
Closed open milestone: 23.1.11
Created milestone: 23.1.12
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-graalvm-community-jdk21u.git
PR created: https://github.com/Karm/test-fake-graalvm-community-jdk21u/pull/22
Merging PR: Unmark suite files and bump version to 23.1.12 [skip ci]
   [OK] Verified suite.py (version=23.1.12, release=False)
   [OK] Verified tag exists remotely: vm-23.1.11
   [OK] Verified tag exists remotely: jdk-21.0.11

[TEST] Executing Step 3: downstream-sync-mark

Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/compiler/mx.compiler/suite.py
Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/wasm/mx.wasm/suite.py
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-mandrel.git
PR created: https://github.com/Karm/test-fake-mandrel/pull/18
Merging PR: Mark suites for 23.1.11.0-Final release [skip ci]
   [OK] Verified suite.py (version=23.1.11.0, release=True)
   [OK] Verified wasm suite.py (version=23.1.11.0, no release attribute)

[TEST] Executing Step 4: downstream-finalize
Auto-calculated next version: 23.1.12.0

Creating signed tag: mandrel-23.1.11.0-Final
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing refs/tags/mandrel-23.1.11.0-Final to origin
   [OK] Pushed refs/tags/mandrel-23.1.11.0-Final (OK)
Closed open milestone: 23.1.11.0-Final
Created milestone: 23.1.12.0
   [OK] Verified tag exists remotely: mandrel-23.1.11.0-Final

[TEST] Executing Step 5: publish-release
Resolving Jenkins artifacts and fetching MANDREL.md...
Verified uniform OpenJDK version across all platforms: 21.0.11+10-LTS
Downloading 12 artifact files...
File mandrel-java21-linux-amd64-23.1.11.0-Final.tar.gz already exists locally. Skipping download.
File mandrel-java21-linux-amd64-23.1.11.0-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java21-linux-amd64-23.1.11.0-Final.tar.gz.sha256 already exists locally. Skipping download.
File mandrel-java21-linux-aarch64-23.1.11.0-Final.tar.gz already exists locally. Skipping download.
File mandrel-java21-linux-aarch64-23.1.11.0-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java21-linux-aarch64-23.1.11.0-Final.tar.gz.sha256 already exists locally. Skipping download.
File mandrel-java21-windows-amd64-23.1.11.0-Final.zip already exists locally. Skipping download.
File mandrel-java21-windows-amd64-23.1.11.0-Final.zip.sha1 already exists locally. Skipping download.
File mandrel-java21-windows-amd64-23.1.11.0-Final.zip.sha256 already exists locally. Skipping download.
File mandrel-java21-macos-aarch64-23.1.11.0-Final.tar.gz already exists locally. Skipping download.
File mandrel-java21-macos-aarch64-23.1.11.0-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java21-macos-aarch64-23.1.11.0-Final.tar.gz.sha256 already exists locally. Skipping download.
Creating draft GitHub release for mandrel-23.1.11.0-Final...
Uploading asset: mandrel-java21-linux-amd64-23.1.11.0-Final.tar.gz
Uploading asset: mandrel-java21-linux-amd64-23.1.11.0-Final.tar.gz.sha1
Uploading asset: mandrel-java21-linux-amd64-23.1.11.0-Final.tar.gz.sha256
Uploading asset: mandrel-java21-linux-aarch64-23.1.11.0-Final.tar.gz
Uploading asset: mandrel-java21-linux-aarch64-23.1.11.0-Final.tar.gz.sha1
Uploading asset: mandrel-java21-linux-aarch64-23.1.11.0-Final.tar.gz.sha256
Uploading asset: mandrel-java21-windows-amd64-23.1.11.0-Final.zip
Uploading asset: mandrel-java21-windows-amd64-23.1.11.0-Final.zip.sha1
Uploading asset: mandrel-java21-windows-amd64-23.1.11.0-Final.zip.sha256
Uploading asset: mandrel-java21-macos-aarch64-23.1.11.0-Final.tar.gz
Uploading asset: mandrel-java21-macos-aarch64-23.1.11.0-Final.tar.gz.sha1
Uploading asset: mandrel-java21-macos-aarch64-23.1.11.0-Final.tar.gz.sha256
Draft release created successfully: https://github.com/Karm/test-fake-mandrel/releases/tag/untagged-80d5423fe207d47bfb56

[TEST] Publishing the draft release so Step 6 can find it
   [OK] Successfully published draft release: Mandrel 23.1.11.0-Final

[TEST] Executing Step 6: update-quarkus-images
Creating new branch: April-CPU-459cfd
Processing updates for mandrel-23.1.11.0-Final (replacing mandrel-23.1.10.0-Final)
Querying GitHub API for release mandrel-23.1.11.0-Final...
Inferred new JDK version: 21.0.11
Loaded amd64 sha256: 11c27dd5b16b5154336418d63c0bc90781ccf1c7e0ad454126ada2325bd320ca
Loaded aarch64 sha256: 03fe0a47af3a4ca1fa1f7fb38fe6fac07453a2b46ccc9f06631369c6d99197a7

YAML updated successfully.
Committing changes: April 2026 CPU, JDK 21.0.11
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-quarkus-images.git
Opening PR on Karm/test-fake-quarkus-images...
PR created: https://github.com/Karm/test-fake-quarkus-images/pull/7
Merging PR: April 2026 CPU, JDK 21.0.11
   [OK] Verified mandrel.yaml contains 23.1.11.0-Final

[TEST] Executing Step 7: sync-upstream

Fetching master from https://github.com/Karm/test-fake-graalvm-community-jdk21u.git
Merging upstream branch into current branch.
Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/compiler/mx.compiler/suite.py
Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/wasm/mx.wasm/suite.py
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-mandrel.git
Assigning Sync PR to milestone: 23.1.12.0
PR created: https://github.com/Karm/test-fake-mandrel/pull/19
   [OK] Verified Sync PR body contains upstream PR links.
Merging PR: Merge upstream test-fake-graalvm-community-jdk21u/master into mandrel/23.1 (2026-05-13)
   [OK] Verified suite.py (version=23.1.12.0, release=False)
   [OK] Verified wasm suite.py (version=23.1.12.0, no release attribute)

[TEST] Simulating further upstream development...

[TEST] Executing Step 8: sync-upstream (Second sync with --since)

Fetching master from https://github.com/Karm/test-fake-graalvm-community-jdk21u.git
Merging upstream branch into current branch.
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-mandrel.git
Assigning Sync PR to milestone: 23.1.12.0
PR created: https://github.com/Karm/test-fake-mandrel/pull/20
   [OK] Verified Second Sync PR body correctly respects the --since bound.
Merging PR: Merge upstream test-fake-graalvm-community-jdk21u/master into mandrel/23.1 (2026-05-13)

[TEST] Executing Auxiliary Step: tag-mandrel

GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing refs/tags/mandrel-23.1.11.0-Final to origin
   [OK] Pushed refs/tags/mandrel-23.1.11.0-Final (OK)
   [OK] Verified tag exists remotely: mandrel-23.1.11.0-Final

[TEST] End-to-end workflow completed successfully.

Local repositories have been left in /tmp/mandrel-test-workspace
```
