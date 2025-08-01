# Mandrel release scripts

This folder contains a number of scripts targetting to ease the process of
creating new Mandrel releases.

## Prerequisites

1. [Install jbang](https://www.jbang.dev/documentation/guide/latest/installation.html)
2. Create a `~/.github` [property files](https://hub4j.github.io/github-api/#Property_file) or set [environmental variables](https://hub4j.github.io/github-api/#Environmental_variables)

We suggest [creating a personal access token](https://docs.github.com/en/github/authenticating-to-github/creating-a-personal-access-token) and using the [property file approach](https://hub4j.github.io/github-api/#Property_file) by creating a file `~/.github` with content `oauth=YOURTOKEN` 

## Creating a Final release

### Step 1: Repository preparation

First we invoke `./mandrel-release.java prepare -m <path/to/mandrel> -f <username/mandrel-fork>` which is responsible for:

1. Marking the suite.py files as releases
2. Creating a new branch and commiting the changes there
3. Pushing the changes to a fork
4. Openning a PR

For usage information please run `./mandrel-release.java -h`

### Step 2: Tags creation

After the changes get approved and merged someone needs to tag the new commit
like this:

```
git checkout mandrel/20.1
git pull upstream mandrel/20.1
git tag -a mandrel-20.1.0.2.Final -m "mandrel-20.1.0.2.Final" -s
git push upstream mandrel-20.1.0.2.Final
```

Make sure to create the same tag in the `mandrel-packaging` repository.

NOTE: This can't be integrated into `mandrel-release.java` yet due to
https://bugs.eclipse.org/bugs/show_bug.cgi?id=386908

### Step 3: Release and bump version

Then `./mandrel-release.java release -m <path/to/mandrel> -f <username/mandrel-fork>` must be invoked which is responsible for:

e.g. 

```bash
$ ./mandrel-release.java release --download --linux-job-build-number=36 \
--windows-job-build-number=28 --macos-job-build-number=37 \
-m /home/karm/tmp/mandrel -s Final -f Karm/graal --verbose
```

1. Creating a new GitHub release for the new tag including a changelog
2. Marking the suite.py files as non-releases
3. Bumping the version in suite.py files
4. Creating a new branch and commiting the changes there
5. Pushing the changes to a fork
6. Openning a PR
7. Uploading the binaries to the GitHub release
8. TODO Open PR for a new image on quarkus-images based on the new release
9. TODO send out emails to relevant lists

For usage information please run `./mandrel-release.java -h`

## Creating an Alpha or Beta pre-release

### Step 1: Tags creation

First we need to create a tag.

```
git checkout mandrel/20.2
git pull upstream mandrel/20.2
git tag -a mandrel-20.2.0.0.Beta2 -m "mandrel-20.2.0.0.Beta2" -s
git push upstream mandrel-20.2.0.0.Beta2
```

Make sure to create the same tag in the `mandrel-packaging` repository.

### Step 2: Create pre-release

Then `./mandrel-release.java release -s Beta2 -m <path/to/mandrel> -f <username/mandrel-fork>` must be invoked which is responsible for:

1. Creating a new GitHub pre-release for the new tag including a changelog
2. TODO Open PR for a new image on quarkus-images based on the new release
3. TODO send out emails to relevant lists 
4. TODO? Send a Slack message

For usage information please run `./mandrel-release.java -h`

## Changelog creation

The changelog is a list of merged PRs in the GH milestone of the version being
released that are also marked with the label `backport` or
`release/noteworthy-feature`.

## Example session

```bash
$ ./mandrel-release.java release --download --linux-job-build-number=36 --windows-job-build-number=28 --macos-job-build-number=37 -m /home/karm/tmp/mandrel -s Final -f Karm/graal --verbose
[INFO] Current version is 24.1.0.0
[INFO] Downloading mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz...
[INFO] Downloading mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz.sha1...
[INFO] Downloading mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz.sha256...
[DEBUG] URL: https://ci.modcluster.io/job/mandrel-24-1-linux-build-matrix/36/JDK_RELEASE=ga,JDK_VERSION=23,LABEL=el8_aarch64/artifact/MANDREL.md file contents: This is a dev build of Mandrel from https://github.com/graalvm/mandrel.
Mandrel 23
Runtime
64-Bit
OpenJDK used: 23+37

[INFO] Downloading mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz...
[INFO] Downloading mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz.sha1...
[INFO] Downloading mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz.sha256...
[DEBUG] URL: https://ci.modcluster.io/job/mandrel-24-1-linux-build-matrix/36/JDK_RELEASE=ga,JDK_VERSION=23,LABEL=el8/artifact/MANDREL.md file contents: This is a dev build of Mandrel from https://github.com/graalvm/mandrel.
Mandrel 23
Runtime
64-Bit
OpenJDK used: 23+37

[INFO] Downloading mandrel-java23-windows-amd64-24.1.0.0-Final.zip...
[INFO] Downloading mandrel-java23-windows-amd64-24.1.0.0-Final.zip.sha1...
[INFO] Downloading mandrel-java23-windows-amd64-24.1.0.0-Final.zip.sha256...
[DEBUG] URL: https://ci.modcluster.io/job/mandrel-24-1-windows-build-matrix/28/JDK_RELEASE=ga,JDK_VERSION=23,LABEL=w2k19/artifact/MANDREL.md file contents: This is a dev build of Mandrel from https://github.com/graalvm/mandrel.
Mandrel 64-Bit
OpenJDK used: 23+37

[INFO] Downloading mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz...
[INFO] Downloading mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz.sha1...
[INFO] Downloading mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz.sha256...
[DEBUG] URL: https://ci.modcluster.io/job/mandrel-24-1-macos-build-matrix/37/JDK_RELEASE=ga,JDK_VERSION=23,LABEL=macos_aarch64/artifact/MANDREL.md file contents: This is a dev build of Mandrel from https://github.com/graalvm/mandrel.
Mandrel 23
Runtime
64-Bit
OpenJDK used: 23+37

[DEBUG] Getting all open milestones
[DEBUG] Got 5 milestones
[DEBUG] Found milestone 87
[INFO] Getting merged PRs for 24.1.0.0-Final (87)
[DEBUG] Checking if PR #757 is merged
[DEBUG] Checking if PR #751 is merged
[DEBUG] Checking if PR #750 is merged
24.1.0.0-Final
[INFO] Closed milestone 24.1.0.0-Final (87)
[INFO] Created milestone 24.1.0.1-Final (110)
[INFO] Uploading mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz
[INFO] Uploaded mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz
[INFO] Uploading mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz.sha1
[INFO] Uploaded mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz.sha1
[INFO] Uploading mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz.sha256
[INFO] Uploaded mandrel-java23-linux-amd64-24.1.0.0-Final.tar.gz.sha256
[INFO] Uploading mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz
[INFO] Uploaded mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz
[INFO] Uploading mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz.sha1
[INFO] Uploaded mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz.sha1
[INFO] Uploading mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz.sha256
[INFO] Uploaded mandrel-java23-linux-aarch64-24.1.0.0-Final.tar.gz.sha256
[INFO] Uploading mandrel-java23-windows-amd64-24.1.0.0-Final.zip
[INFO] Uploaded mandrel-java23-windows-amd64-24.1.0.0-Final.zip
[INFO] Uploading mandrel-java23-windows-amd64-24.1.0.0-Final.zip.sha1
[INFO] Uploaded mandrel-java23-windows-amd64-24.1.0.0-Final.zip.sha1
[INFO] Uploading mandrel-java23-windows-amd64-24.1.0.0-Final.zip.sha256
[INFO] Uploaded mandrel-java23-windows-amd64-24.1.0.0-Final.zip.sha256
[INFO] Uploading mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz
[INFO] Uploaded mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz
[INFO] Uploading mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz.sha1
[INFO] Uploaded mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz.sha1
[INFO] Uploading mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz.sha256
[INFO] Uploaded mandrel-java23-macos-aarch64-24.1.0.0-Final.tar.gz.sha256
[INFO] Created new draft release: https://github.com/graalvm/mandrel/releases/tag/untagged-9a996555616b0f035344
[INFO] Please review and publish!
[INFO] New version will be 24.1.0.1
2024-09-23 19:55:54 WARN  FS:752 - locking FileBasedConfig[/home/karm/.config/jgit/config] failed after 5 retries
[INFO] Git remote mandrel-release-fork points to git@github.com:Karm/graal
[INFO] Created new branch develop/mandrel-24.1.0.1-Final based on mandrel/24.1
[INFO] Updating /home/karm/tmp/mandrel/visualizer/mx.visualizer/suite.py
[INFO] Updating /home/karm/tmp/mandrel/compiler/mx.compiler/suite.py
[INFO] Updating /home/karm/tmp/mandrel/espresso/mx.espresso/suite.py
[INFO] Updating /home/karm/tmp/mandrel/regex/mx.regex/suite.py
[INFO] Updating /home/karm/tmp/mandrel/sdk/mx.sdk/suite.py
[INFO] Updating /home/karm/tmp/mandrel/substratevm/mx.substratevm/suite.py
[INFO] Updating /home/karm/tmp/mandrel/sulong/mx.sulong/suite.py
[INFO] Updating /home/karm/tmp/mandrel/tools/mx.tools/suite.py
[INFO] Updating /home/karm/tmp/mandrel/truffle/mx.truffle/suite.py
[INFO] Updating /home/karm/tmp/mandrel/vm/mx.vm/suite.py
[INFO] Updating /home/karm/tmp/mandrel/wasm/mx.wasm/suite.py
[INFO] Updated suites
[INFO] Changes commited
[INFO] Changes pushed to remote mandrel-release-fork
[INFO] Checked out mandrel/24.1
[INFO] Pull request https://github.com/graalvm/mandrel/pull/795 created
```
