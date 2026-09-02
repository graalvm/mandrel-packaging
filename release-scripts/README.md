# Mandrel Release Operations (`mandrel-ops.java`)

This script orchestrates the cascading release workflow from the upstream `graalvm-community` repositories down to `mandrel`, and manages publishing releases to `mandrel` repository and to `Quarkus images` repository.

**Before you start:**
* Make sure local repositories are clean and checked out to the proper branches, e.g. `master` for GraalVM Community repos, `mandrel/25.0` for Mandrel repo, `25.0` for Mandrel packaging repo and `main` for Quarkus Images repo.
* **For CSPU releases:** Ensure you are checked out to the dedicated CSPU branches (e.g., `cspu-25.0.4` upstream and `mandrel/25.0-cspu-25.0.4` downstream).
* Make sure each repo has your proper `git config user.signingkey`, email, name set.
* Make sure the `graalvm-community` repos, where the process begins, are in the code freeze period, talk to your peers upstream, make sure people expect you to do this now :)
* If in doubt, use `-D` (dry run) on any step to validate locally before affecting GitHub.

### CSPU (Critical Security Patch Update) Note
Always append the `--cspu` flag for CSPU releases. If the CSPU contains only JDK updates (empty diff compared to previous release), the script automatically bypasses Mark/Unmark suite PRs and milestone creation, merely bumping the version directly.

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

1. **Sync Upstream:** Run `sync-upstream`, see [Step 7](#step-7). Use this right after a release to merge the upstream `master` into the downstream branch as it autoresolves `suite.py` conflicts **OR** use it routinely midcycle with or without the `--since` flag to pull in general upstream development without bloating the PR body with old links.

---

## <a id="step-1"></a>Step 1: Upstream Mark Release (`upstream-mark`)

Marks the upstream suites for release (`release: True`). Creates a branch and opens a "Mark suite files for release" PR on the `graalvm-community` repo.


*Note: Using the `--cspu` flag will auto-detect if the release diff is empty (only JDK updates). If empty, it bypasses the `release: True` state change, solely bumps the version, and adjusts the PR title to `Bump version for CSPU...`.*

**Example for standard release of 21u (23.1.11):**
```bash
./mandrel-ops.java upstream-mark \
  --dir /home/karm/workspaceRH/graal/21/graalvm-community-jdk21u \
  --fork Karm/graalvm-community-jdk21u \
  --repo graalvm/graalvm-community-jdk21u \
  --base-branch master \
  --version 23.1.11
```

**Example for CSPU release of 25u (25.0.4.1):**
```bash
./mandrel-ops.java upstream-mark \
  --cspu \
  --dir /home/karm/workspaceRH/graal/25/graalvm-community-jdk25u \
  --fork Karm/graalvm-community-jdk25u \
  --repo graalvm/graalvm-community-jdk25u \
  --base-branch cspu-25.0.4 \
  --version 25.0.4.1
```
*Wait for this PR to be reviewed and merged upstream before proceeding.*

---

## <a id="step-2"></a>Step 2: Upstream Finalize (`upstream-finalize`)

Once the Step 1 PR is merged, this command tags the commit, pushes the tags, advances milestones, unmarks suites (`release: False`), bumps the version, and opens the "Unmark suite files" PR upstream.


*Note: Using the `--cspu` flag on an empty release will fully skip the "Unmark suites" step, leaving `release: True` active.*

**Example for standard 21u (23.1.10 previous → 23.1.11 release → 23.1.12 next):**
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

**Example for CSPU release of 25u (25.0.4.1):**
```bash
./mandrel-ops.java upstream-finalize \
  --cspu \
  --dir /home/karm/workspaceRH/graal/25/graalvm-community-jdk25u \
  --fork Karm/graalvm-community-jdk25u \
  --repo graalvm/graalvm-community-jdk25u \
  --base-branch cspu-25.0.4 \
  --version 25.0.4.1 \
  --jdk-version 25.0.4.1 \
  --upstream-remote upstream
```
*Don't wait for this PR to be merged upstream. You may proceed to Step 3 simultaneously. As a matter of convention, we merge that PR when at least one vendor had a successful release.*

---

## <a id="step-3"></a>Step 3: Downstream Sync Mark (`downstream-sync-mark`)

Fetches the newly created upstream tag into the downstream `mandrel` repo. Automatically handles the merge and `suite.py` conflict resolution appending the 4th digit suffix, and opens the Mark Release PR on Mandrel.


*Note: Using `--cspu` prevents the `.0` suffix from being forcefully appended, correctly mirroring upstream's `.1` suffix format.*

**Example for standard 23.1 (23.1.11.0):**
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

**Example for CSPU release 25.0 (25.0.4.1):**
```bash
./mandrel-ops.java downstream-sync-mark \
  --cspu \
  --dir /home/karm/tmp/mandrel_25.0 \
  --fork Karm/graal \
  --repo graalvm/mandrel \
  --base-branch mandrel/25.0-cspu-25.0.4 \
  --upstream-url https://github.com/graalvm/graalvm-community-jdk25u.git \
  --upstream-tag vm-25.0.4.1
```
*Wait for this PR to be reviewed and merged downstream before proceeding.*

---

## <a id="step-4"></a>Step 4: Downstream Finalize (`downstream-finalize`)

Completes the cycle for the `mandrel` repo. Tags the release and manages downstream milestones.

**Example for standard 23.1 (23.1.11.0):**
```bash
./mandrel-ops.java downstream-finalize \
  --dir /home/karm/tmp/mandrel_23.1 \
  --repo graalvm/mandrel \
  --base-branch mandrel/23.1 \
  --version mandrel-23.1.11.0-Final \
  --upstream-remote origin
```

**Example for CSPU release 25.0 (25.0.4.1):**
```bash
./mandrel-ops.java downstream-finalize \
  --cspu \
  --dir /home/karm/tmp/mandrel_25.0 \
  --repo graalvm/mandrel \
  --base-branch mandrel/25.0-cspu-25.0.4 \
  --version mandrel-25.0.4.1-Final \
  --upstream-remote origin
```

---

## <a id="step-5"></a>Step 5: Publish Draft Release (`publish-release`)

Downloads the release artifacts from Jenkins skipping already downloaded files, validates the OpenJDK version from downloaded `MANDREL.md`, and creates a draft release on GitHub. It relies on the presence of `release-template.md` in the execution directory (can be overridden via `-T /path/to/template.md`). Make sure you use the correct Jenkins build numbers, those that passed QA.

*Note: The JDK major version is automatically inferred from the upstream-repo name (e.g., 25 from jdk25u). Alternatively, it can be provided explicitly using the `-j` or `--jdk-major` option.*


*Note: The Quarkus version for the release text is automatically inferred from the Mandrel version. Alternatively, it can be overridden using the `-q` or `--quarkus-version` option. It's used in the Quickstarts section, https://code.quarkus.io/*


*Note: Using `--cspu` prompts the script to compare versions via the GitHub API. If it identifies an empty diff (only `suite.py` updates), it injects specialized CSPU headers and replaces the changelog with an empty-diff notice.*

**Example for standard release (25.0.3.0):**
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

**Example for CSPU release (25.0.4.1):**
```bash
./mandrel-ops.java publish-release \
  --cspu \
  --repo graalvm/mandrel \
  --version mandrel-25.0.4.1-Final \
  --prev-version mandrel-25.0.4.0-Final \
  --upstream-repo graalvm/graalvm-community-jdk25u \
  --upstream-tag vm-25.0.4.1 \
  --linux-build 143 \
  --windows-build 105 \
  --macos-build 162 \
  --download-dir ./artifacts
```
*Review the draft release on GitHub and publish it when ready.*

---

## <a id="step-6"></a>Step 6: Update Quarkus Images (`update-quarkus-images`)

This step needs the GitHub Mandrel Release to be already Released, not a Draft. It updates `mandrel.yaml` and `graalvm.yaml` in the `quarkus-images` repository with the new tags, versions, and sha256 checksums from the completed releases. Opens a PR upstream, that is on https://github.com/quarkusio/quarkus-images.

*Note: The order of elements in `--version` and `--prev-version` matters, i.e. it has to match each other.*

**Example of a release (25.0.4.0 & 23.1.12.0) alongside GraalVM CE:**
```bash
./mandrel-ops.java update-quarkus-images \
  --dir /home/karm/workspaceRH/quarkus-images \
  --month April \
  --version mandrel-25.0.4.0-Final,mandrel-23.1.12.0-Final,graal-25.3.4.1 \
  --prev-version mandrel-25.0.3.0-Final,mandrel-23.1.11.0-Final,graal-25.2.4 \
  --download-dir ./artifacts \
  --fork Karm/quarkus-images \
  --upstream-repo quarkusio/quarkus-images
```

**Example for CSPU release (25.0.4.1 & 23.1.12.1) alongside GraalVM CE:**
```bash
./mandrel-ops.java update-quarkus-images \
  --cspu \
  --dir /home/karm/workspaceRH/quarkus-images \
  --month August \
  --version mandrel-25.0.4.1-Final,mandrel-23.1.12.1-Final,graal-25.3.4.1 \
  --prev-version mandrel-25.0.4.0-Final,mandrel-23.1.12.0-Final,graal-25.2.4 \
  --download-dir ./artifacts \
  --fork Karm/quarkus-images \
  --upstream-repo quarkusio/quarkus-images
```
*Note: This reads the `.sha256` files created locally during Step 5 for Mandrel hashes. For GraalVM CE, it queries the GitHub API directly to infer JDK versions and download the required SHA artifacts.*

---

## <a id="step-7"></a>Step 7: Sync Upstream (`sync-upstream`)

This command is highly versatile. It merges the upstream branch (e.g., `master`) into the downstream branch and generates a standard sync PR populated with a list of the merged upstream PRs.

**Usage A: Post-Release Sync**
Once the upstream `Unmark suites` PR is merged and the Mandrel release is fully published, run this to bring downstream up to speed. It autoresolves `suite.py` conflicts if they arise. Passing `--next-version` tells the script exactly what version to enforce in `suite.py`.

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

**Usage B: Routine Mid-Cycle Sync (Auto-calculated)**
You can use this command anytime during the development cycle to sync upstream changes. If you omit `--since`, the script automatically calculates the git `merge-base` between the upstream and downstream histories to figure out exactly which upstream PRs have not been synced yet.

*Caveat: The autodetection works for standard merges. If a squash merge or a cherry-pick exists in the downstream history since the last sync, the script will actively detect it, bail out, and ask you to provide the `--since` SHA manually.*

**Example (Routine Auto-Sync):**
```bash
./mandrel-ops.java sync-upstream \
  --dir /home/karm/tmp/mandrel_23.1 \
  --fork Karm/graal \
  --repo graalvm/mandrel \
  --base-branch mandrel/23.1 \
  --upstream-url https://github.com/graalvm/graalvm-community-jdk21u.git \
  --upstream-branch master
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

It is rather cumbersome to test this properly. It requires signed tags, i.e. gpg infra, pushing to GitHub repositories, having local repositories, downloadoing and uploading artifacts etc. The way it is written now, you can use `expect` in a small script and give your passphrase to your env so as jgit doesn't keep prompting you for one, e.g. `$ GPG_PASSPHRASE="changeit" ./signer.exp ./testing/test-mandrel-ops.java`. Usual word of caution about leaking your passphrase to an env.


The test script, [test-mandrel-ops.java](./testing/test-mandrel-ops.java) that calls [mandrel-ops.java](./mandrel-ops.java), creates fake **private** repositories with dummy content on your GitHub account and on your local disk. It has hardcoded 'Karm' account username in it out of paranoia; you change that to your own desired org or user. It requires a GH *Pro* plan due to the usage of private repos. The reason for private repos is merely to keep the testing mess hidden. It can work with public repos too if necessary.


It runs stage by stage and really verifies that the script works. From signed tags to milestones opening and closing, including suite.py `release: true/false` conflicts resolutions, downloading real artifacts from Jenkins, publishing a GitHub Release, uploading those artifatcs to it etc. The only corner it cuts is assigning Reviewers. That is skipped for test runs.

It is not exhaustive. The main goal of the testing infra is to be able to locally verify that a change to the release script [mandrel-ops.java](./mandrel-ops.java) didn't outright blow everything up to smithereens.

## Example of a test flow

```
$ GPG_PASSPHRASE="changeit" ./signer.exp ./testing/test-mandrel-ops.java
spawn ./testing/test-mandrel-ops.java
Starting Integration Test for Mandrel Release Ops...

[SETUP] Wiping local workspace and forcing pristine remote state...
   Found existing remote repository: test-fake-graalvm-community-jdk21u. Scrubbing it...
   Found existing remote repository: test-fake-graalvm-community-jdk25u. Scrubbing it...
   Found existing remote repository: test-fake-mandrel. Scrubbing it...
   Found existing remote repository: test-fake-mandrel-packaging. Scrubbing it...
   Found existing remote repository: test-fake-quarkus-images. Scrubbing it...
[SETUP] Fake repositories completely generated with credible baseline history.

[TEST] Executing Step 1: upstream-mark
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-graalvm-community-jdk21u.git
PR created: https://github.com/Karm/test-fake-graalvm-community-jdk21u/pull/111
Merging PR: Mark suite files for 23.1.12 release [skip ci]
   [OK] Verified suite.py (version=23.1.12, release=True)

[TEST] Executing Step 2: upstream-finalize
Auto-calculated next version: 23.1.13
Checking remote tags on origin...
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Created local tag: vm-23.1.12
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Created local tag: jdk-21.0.12
Pushing refs/tags/vm-23.1.12 to origin
   [OK] Pushed refs/tags/vm-23.1.12 (OK)
Pushing refs/tags/jdk-21.0.12 to origin
   [OK] Pushed refs/tags/jdk-21.0.12 (OK)
Closed open milestone: 23.1.12
Created milestone: 23.1.13
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-graalvm-community-jdk21u.git
PR created: https://github.com/Karm/test-fake-graalvm-community-jdk21u/pull/112
Merging PR: Unmark suite files and bump version to 23.1.13 [skip ci]
   [OK] Verified suite.py (version=23.1.13, release=False)
   [OK] Verified tag exists remotely: vm-23.1.12
   [OK] Verified tag exists remotely: jdk-21.0.12

[TEST] Executing Step 3: downstream-sync-mark
Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/compiler/mx.compiler/suite.py
Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/wasm/mx.wasm/suite.py
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-mandrel.git
PR created: https://github.com/Karm/test-fake-mandrel/pull/100
Merging PR: Mark suites for 23.1.12.0-Final release [skip ci]
   [OK] Verified suite.py (version=23.1.12.0, release=True)
   [OK] Verified wasm suite.py (version=23.1.12.0, no release attribute)

[TEST] Executing Step 4: downstream-finalize
Auto-calculated next version: 23.1.13.0-Final
Creating signed tag: mandrel-23.1.12.0-Final
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing refs/tags/mandrel-23.1.12.0-Final to origin
   [OK] Pushed refs/tags/mandrel-23.1.12.0-Final (OK)
Closed open milestone: 23.1.12.0-Final
Created milestone: 23.1.13.0-Final
   [OK] Verified tag exists remotely: mandrel-23.1.12.0-Final
   [OK] Verified milestone 23.1.12.0-Final is completely closed.

[TEST] Executing Step 5: publish-release
Inferred JDK major version 21 from upstream repo Karm/test-fake-graalvm-community-jdk21u
Inferred Quarkus version 3.27 from Mandrel version 23.1.12.0-Final
Resolving Jenkins artifacts and fetching MANDREL.md...
Verified uniform OpenJDK version across all platforms: 21.0.12+8-LTS
Downloading 12 artifact files...
File mandrel-java21-linux-amd64-23.1.12.0-Final.tar.gz already exists locally. Skipping download.
File mandrel-java21-linux-amd64-23.1.12.0-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java21-linux-amd64-23.1.12.0-Final.tar.gz.sha256 already exists locally. Skipping download.
File mandrel-java21-linux-aarch64-23.1.12.0-Final.tar.gz already exists locally. Skipping download.
File mandrel-java21-linux-aarch64-23.1.12.0-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java21-linux-aarch64-23.1.12.0-Final.tar.gz.sha256 already exists locally. Skipping download.
File mandrel-java21-windows-amd64-23.1.12.0-Final.zip already exists locally. Skipping download.
File mandrel-java21-windows-amd64-23.1.12.0-Final.zip.sha1 already exists locally. Skipping download.
File mandrel-java21-windows-amd64-23.1.12.0-Final.zip.sha256 already exists locally. Skipping download.
File mandrel-java21-macos-aarch64-23.1.12.0-Final.tar.gz already exists locally. Skipping download.
File mandrel-java21-macos-aarch64-23.1.12.0-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java21-macos-aarch64-23.1.12.0-Final.tar.gz.sha256 already exists locally. Skipping download.
Creating draft GitHub release for mandrel-23.1.12.0-Final...
Uploading asset: mandrel-java21-linux-amd64-23.1.12.0-Final.tar.gz
Uploading asset: mandrel-java21-linux-amd64-23.1.12.0-Final.tar.gz.sha1
Uploading asset: mandrel-java21-linux-amd64-23.1.12.0-Final.tar.gz.sha256
Uploading asset: mandrel-java21-linux-aarch64-23.1.12.0-Final.tar.gz
Uploading asset: mandrel-java21-linux-aarch64-23.1.12.0-Final.tar.gz.sha1
Uploading asset: mandrel-java21-linux-aarch64-23.1.12.0-Final.tar.gz.sha256
Uploading asset: mandrel-java21-windows-amd64-23.1.12.0-Final.zip
Uploading asset: mandrel-java21-windows-amd64-23.1.12.0-Final.zip.sha1
Uploading asset: mandrel-java21-windows-amd64-23.1.12.0-Final.zip.sha256
Uploading asset: mandrel-java21-macos-aarch64-23.1.12.0-Final.tar.gz
Uploading asset: mandrel-java21-macos-aarch64-23.1.12.0-Final.tar.gz.sha1
Uploading asset: mandrel-java21-macos-aarch64-23.1.12.0-Final.tar.gz.sha256
Draft release created successfully: https://github.com/Karm/test-fake-mandrel/releases/tag/untagged-09ddfe6175751030df73

[TEST] Publishing the draft release so Step 6 can find it
   [OK] Successfully published draft release: Mandrel 23.1.12.0-Final

[TEST] Executing Step 6: update-quarkus-images

Processing updates for Mandrel mandrel-23.1.12.0-Final (replacing mandrel-23.1.11.0-Final)
Querying GitHub API for release mandrel-23.1.12.0-Final...
Inferred new JDK version: 21.0.12
Loaded amd64 sha256: 71235de50e27cc86ca6511cef4f8dbd342adab4c438da3232a6c50ed2ef963aa
Loaded aarch64 sha256: a32645a6e858b64c191f5289dab8ef742c46b47253e72c7748e03fbaaaffc4c1

Mandrel YAML updated successfully.

Processing updates for GraalVM graal-25.3.4.1 (replacing graal-25.2.4)
Querying GitHub API for GraalVM release graal-25.3.4.1...
Inferred new JDK version: 25.0.4.1
File graalvm-community-jdk-25i3-25.0.4.1_linux-aarch64_bin.tar.gz.sha256 already exists locally. Skipping download.
File graalvm-community-jdk-25i3-25.0.4.1_linux-x64_bin.tar.gz.sha256 already exists locally. Skipping download.
Loaded amd64 sha256: b2bc38d0c4141426eb44d0eefa3cc172c96faf92727d703b61541699128b6fc7
Loaded aarch64 sha256: 7e8a3fbc2e4c28566107039a298cef690d86a37599a8e50536fc5a65b7f1bd56
GraalVM YAMLs updated successfully.
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-quarkus-images.git
Opening PR on Karm/test-fake-quarkus-images...
PR created: https://github.com/Karm/test-fake-quarkus-images/pull/31
Merging PR: April 2026 CPU, JDK 25.0.4.1, 21.0.12
   [OK] Verified mandrel.yaml contains 23.1.12.0-Final and its URL
   [OK] Verified quarkus-native-s2i/graalvm.yaml contains graal-25.3.4.1 and its URL
   [OK] Verified quarkus-graalvm-builder-image/graalvm.yaml contains graal-25.3.4.1 and its URL

[TEST] Executing Step 7: sync-upstream
Fetching master from https://github.com/Karm/test-fake-graalvm-community-jdk21u.git
No --since provided. Calculating the merge base between downstream and upstream...
Calculated merge base: 05692edaa95116d3b9b5a4fa7b873acb8bae286e (Merge pull request #111 from Karm/release-prep-1788303832535)
Merging upstream branch into current branch.
Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/compiler/mx.compiler/suite.py
Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/wasm/mx.wasm/suite.py
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-mandrel.git
Assigning Sync PR to milestone: 23.1.13.0-Final
PR created: https://github.com/Karm/test-fake-mandrel/pull/101
   [OK] Verified Sync PR body contains upstream PR links.
Merging PR: Merge upstream test-fake-graalvm-community-jdk21u/master into mandrel/23.1 (2026-09-02)
   [OK] Verified suite.py (version=23.1.13.0, release=False)
   [OK] Verified wasm suite.py (version=23.1.13.0, no release attribute)

[TEST] Simulating further upstream development (feature-A)...

[TEST] Executing Step 8: sync-upstream (Second sync with explicit --since)
Fetching master from https://github.com/Karm/test-fake-graalvm-community-jdk21u.git
Using explicitly provided --since commit: a8dc0ead4dbf3dbf7612e691a0c799806959df27
Merging upstream branch into current branch.
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-mandrel.git
Assigning Sync PR to milestone: 23.1.13.0-Final
PR created: https://github.com/Karm/test-fake-mandrel/pull/102
   [OK] Verified Second Sync PR body correctly respects the explicit --since bound.
Merging PR: Merge upstream test-fake-graalvm-community-jdk21u/master into mandrel/23.1 (2026-09-02)

[TEST] Simulating further upstream development for auto-since (feature-B)...

[TEST] Executing Step 9: sync-upstream (Third sync without --since)
Fetching master from https://github.com/Karm/test-fake-graalvm-community-jdk21u.git
No --since provided. Calculating the merge base between downstream and upstream...
Calculated merge base: 2fc452162c64507b762c7d9c268b6337757c3f3f (Merge pull request #113 from Karm/feature-A-1788304137481)
Merging upstream branch into current branch.
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-mandrel.git
Assigning Sync PR to milestone: 23.1.13.0-Final
PR created: https://github.com/Karm/test-fake-mandrel/pull/103
   [OK] Verified Third Sync PR body correctly auto-calculated the merge-base bound.
Merging PR: Merge upstream test-fake-graalvm-community-jdk21u/master into mandrel/23.1 (2026-09-02)

[TEST] Executing Step 10: Testing Cherry-pick bail out
Fetching master from https://github.com/Karm/test-fake-graalvm-community-jdk21u.git
No --since provided. Calculating the merge base between downstream and upstream...
Calculated merge base: 582ac237acff17ae1404acc1010547fdb7e0d796 (Merge pull request #114 from Karm/feature-B-1788304164924)
   [OK] Script correctly bailed out with expected error.

[TEST] Executing Step 11: Testing Squash bail out
Fetching master from https://github.com/Karm/test-fake-graalvm-community-jdk21u.git
No --since provided. Calculating the merge base between downstream and upstream...
Calculated merge base: 582ac237acff17ae1404acc1010547fdb7e0d796 (Merge pull request #114 from Karm/feature-B-1788304164924)
   [OK] Script correctly bailed out with expected error.

[TEST] Executing CSPU Step 1: upstream-mark (Empty Diff)
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-graalvm-community-jdk25u.git
PR created: https://github.com/Karm/test-fake-graalvm-community-jdk25u/pull/10
Merging PR: Bump version for CSPU 25.0.4.1 [skip ci]
   [OK] Verified suite.py (version=25.0.4.1, release=True)

[TEST] Executing CSPU Step 2: upstream-finalize (Empty Diff)
Auto-calculated next version: 25.0.4.2
Checking remote tags on origin...
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Created local tag: vm-25.0.4.1
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Created local tag: jdk-25.0.4.1
Pushing refs/tags/vm-25.0.4.1 to origin
   [OK] Pushed refs/tags/vm-25.0.4.1 (OK)
Pushing refs/tags/jdk-25.0.4.1 to origin
   [OK] Pushed refs/tags/jdk-25.0.4.1 (OK)
Empty CSPU detected. Skipping Milestone and Unmark suites PR creation.
   [OK] Verified tag exists remotely: vm-25.0.4.1

[TEST] Executing CSPU Step 3: downstream-sync-mark (Empty Diff)
Resolved conflict in: /tmp/mandrel-test-workspace/test-fake-mandrel/compiler/mx.compiler/suite.py
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-mandrel.git
PR created: https://github.com/Karm/test-fake-mandrel/pull/104
Merging PR: [Backport] Bump version for CSPU 25.0.4.1 [skip ci]
   [OK] Verified suite.py (version=25.0.4.1, release=True)

[TEST] Executing CSPU Step 4: downstream-finalize (Empty Diff)
Auto-calculated next version: 25.0.4.2-Final
Creating signed tag: mandrel-25.0.4.1-Final
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing refs/tags/mandrel-25.0.4.1-Final to origin
   [OK] Pushed refs/tags/mandrel-25.0.4.1-Final (OK)
Created milestone: 25.0.4.2-Final
   [OK] Verified tag exists remotely: mandrel-25.0.4.1-Final

[TEST] Executing CSPU Step 5: publish-release (Empty Diff)
Inferred JDK major version 25 from upstream repo Karm/test-fake-graalvm-community-jdk25u
Inferred Quarkus version 3.39 from Mandrel version 25.0.4.1-Final
Resolving Jenkins artifacts and fetching MANDREL.md...
Verified uniform OpenJDK version across all platforms: 25.0.4.1+1-LTS
Downloading 12 artifact files...
File mandrel-java25-linux-amd64-25.0.4.1-Final.tar.gz already exists locally. Skipping download.
File mandrel-java25-linux-amd64-25.0.4.1-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java25-linux-amd64-25.0.4.1-Final.tar.gz.sha256 already exists locally. Skipping download.
File mandrel-java25-linux-aarch64-25.0.4.1-Final.tar.gz already exists locally. Skipping download.
File mandrel-java25-linux-aarch64-25.0.4.1-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java25-linux-aarch64-25.0.4.1-Final.tar.gz.sha256 already exists locally. Skipping download.
File mandrel-java25-windows-amd64-25.0.4.1-Final.zip already exists locally. Skipping download.
File mandrel-java25-windows-amd64-25.0.4.1-Final.zip.sha1 already exists locally. Skipping download.
File mandrel-java25-windows-amd64-25.0.4.1-Final.zip.sha256 already exists locally. Skipping download.
File mandrel-java25-macos-aarch64-25.0.4.1-Final.tar.gz already exists locally. Skipping download.
File mandrel-java25-macos-aarch64-25.0.4.1-Final.tar.gz.sha1 already exists locally. Skipping download.
File mandrel-java25-macos-aarch64-25.0.4.1-Final.tar.gz.sha256 already exists locally. Skipping download.
Creating draft GitHub release for mandrel-25.0.4.1-Final...
Uploading asset: mandrel-java25-linux-amd64-25.0.4.1-Final.tar.gz
Uploading asset: mandrel-java25-linux-amd64-25.0.4.1-Final.tar.gz.sha1
Uploading asset: mandrel-java25-linux-amd64-25.0.4.1-Final.tar.gz.sha256
Uploading asset: mandrel-java25-linux-aarch64-25.0.4.1-Final.tar.gz
Uploading asset: mandrel-java25-linux-aarch64-25.0.4.1-Final.tar.gz.sha1
Uploading asset: mandrel-java25-linux-aarch64-25.0.4.1-Final.tar.gz.sha256
Uploading asset: mandrel-java25-windows-amd64-25.0.4.1-Final.zip
Uploading asset: mandrel-java25-windows-amd64-25.0.4.1-Final.zip.sha1
Uploading asset: mandrel-java25-windows-amd64-25.0.4.1-Final.zip.sha256
Uploading asset: mandrel-java25-macos-aarch64-25.0.4.1-Final.tar.gz
Uploading asset: mandrel-java25-macos-aarch64-25.0.4.1-Final.tar.gz.sha1
Uploading asset: mandrel-java25-macos-aarch64-25.0.4.1-Final.tar.gz.sha256
Draft release created successfully: https://github.com/Karm/test-fake-mandrel/releases/tag/untagged-b8089142ad4d178bb008

[TEST] Publishing the draft release so Step 6 can find it
   [OK] Successfully published draft release: Mandrel 25.0.4.1-Final CSPU

[TEST] Executing CSPU Step 6: update-quarkus-images (Empty Diff)

Processing updates for Mandrel mandrel-25.0.4.1-Final (replacing mandrel-25.0.4.0-Final)
Querying GitHub API for release mandrel-25.0.4.1-Final...
Inferred new JDK version: 25.0.4.1
Loaded amd64 sha256: e47ff6bfe6a8dbb482fdc65c9a49fc6f3ba2fa61ef3cc8fb6229a88989054c31
Loaded aarch64 sha256: 86033db0337ba64694a1f3680b991b671ba26d192f26fd525e39b714847fbf20

Mandrel YAML updated successfully.

Processing updates for GraalVM graal-25.3.4.1 (replacing graal-25.2.4)
Querying GitHub API for GraalVM release graal-25.3.4.1...
Inferred new JDK version: 25.0.4.1
File graalvm-community-jdk-25i3-25.0.4.1_linux-aarch64_bin.tar.gz.sha256 already exists locally. Skipping download.
File graalvm-community-jdk-25i3-25.0.4.1_linux-x64_bin.tar.gz.sha256 already exists locally. Skipping download.
Loaded amd64 sha256: b2bc38d0c4141426eb44d0eefa3cc172c96faf92727d703b61541699128b6fc7
Loaded aarch64 sha256: 7e8a3fbc2e4c28566107039a298cef690d86a37599a8e50536fc5a65b7f1bd56
GraalVM YAMLs updated successfully.
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-quarkus-images.git
Opening PR on Karm/test-fake-quarkus-images...
PR created: https://github.com/Karm/test-fake-quarkus-images/pull/32
Merging PR: April 2026 CSPU, JDK 25.0.4.1
   [OK] Verified mandrel.yaml contains 25.0.4.1-Final and its URL
   [OK] Verified quarkus-native-s2i/graalvm.yaml contains graal-25.3.4.1 and its URL
   [OK] Verified quarkus-graalvm-builder-image/graalvm.yaml contains graal-25.3.4.1 and its URL
   [OK] Verified CSPU Release body text formatting.

[TEST] Preparing isolated branches for CSPU 25.0.4.2 (Non-Empty)...

[TEST] Injecting code changes to trigger non-empty CSPU logic...

[TEST] Executing CSPU Step 1: upstream-mark (Non-Empty)
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing branch to fork git@github.com:Karm/test-fake-graalvm-community-jdk25u.git
PR created: https://github.com/Karm/test-fake-graalvm-community-jdk25u/pull/11
   [OK] Verified Non-Empty CSPU PR uses traditional title.
Merging PR: Mark suite files for 25.0.4.2 release [skip ci]

[TEST] Executing Auxiliary Step: tag-mandrel
GPG Key (fingerprint d72501ba9a2a624b000f38858cdbce4379381fc4)
Passphrase: 
Pushing refs/tags/mandrel-23.1.12.0-Final to origin
   [OK] Pushed refs/tags/mandrel-23.1.12.0-Final (OK)
   [OK] Verified tag exists remotely: mandrel-23.1.12.0-Final

[TEST] End-to-end workflow completed successfully.

Local repositories have been left in /tmp/mandrel-test-workspace
```
