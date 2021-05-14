# Mandrel Packaging

This repo contains all the necessary scripts and tools to build [Mandrel](https://github.com/graalvm/mandrel).

## Building mandrelJDK locally

```shell
export JAVA_HOME=/opt/jvms/openjdk-11.0.8_10
$JAVA_HOME/bin/java -ea build.java --mx-home ~/code/mx --mandrel-repo ~/code/mandrel
```

where:
* `JAVA_HOME` is the path to the OpenJDK you want to use for building mandrel.
* `--mx-home` is the path where you cloned https://github.com/graalvm/mx. Defaults to `/opt/mx`.
* `--mandrel-repo` is the path where you cloned https://github.com/graalvm/mandrel. Defaults to `/tmp/mandrel`.

This should print something similar to:
```
INFO [build] Building!
build: Checking SubstrateVM requirements for building ...
build: Checking SubstrateVM requirements for building ...
build: Checking SubstrateVM requirements for building ...
build: Checking SubstrateVM requirements for building ...
INFO [build] Creating JDK!
INFO [build] Congratulations you successfully built Mandrel 20.1.0.1.Final-3-g9abceb5ac7d based on Java 11.0.8+10
INFO [build] You can find your newly built native-image enabled JDK under ./mandrel-java11-20.1.0.1.Final-3-g9abceb5ac7d
```

### More options

#### Generic
* `--verbose` enables verbose logging.
* `--skip-clean` skips cleaning before building.
* `--skip-java` skips building java bits.
* `--skip-native` skips building native bits.
* `--archive-suffix` defines the suffix for creating an archive with the new JDK. By default no archive is built. Accepted values are `tar.gz` and `tarxz` (slower but with better compresion).
* `--dependencies` overrides `mx` dependencies, e.g., `--dependencies id=ASM_7.1,version=7.1.0.redhat-00001,sha1=41bc48bf913569bd001cc132624f811d91004af4,sourceSha1=8c938bc977786f0f3964b394e28f31e726769bac`

#### Mandrel related
* `--mandrel-home` sets the path where you want mandrel to be installed, after completion you will be
 able to use this as `JAVA_HOME` or/and `GRAALVM_HOME` in your projects (e.g. quarkus). 
 By default mandrel is built in the current directory and the version-based path gets printed at the end.
* `--mandrel-version` defines the version to be shown when running `native-image --version` (e.g. 20.1.0). Defaults to the result of `git describe` or `git rev-parse --short HEAD` in `mandrel-repo`.

#### Maven-related

* `--maven-install` generates maven artifacts and installs them to the local maven repository.
* `--maven-deploy` generates maven artifacts, installs them to the local maven repository and deploys them.
* `--maven-version` specifies the version (e.g., `20.1.0.2-0-redhat-00000`) to be used in the maven artifacts (required by `--maven-install` and `--maven-deploy`).
* `--maven-proxy` specifies a maven proxy to use.
* `--maven-repo-id` specifies the maven repository ID for deploying maven artifacts. Required by `--maven-deploy`.
* `--maven-url` specifies the maven url for deploying maven artifacts. Required by `--maven-deploy`.
* `--maven-local-repository` specifies the local repository. Defaults to `~/.m2/repository`.
* `--maven-home` specifies the maven installation path in case one wants to use a different maven version than the one provided by the system. 

## Building mandrelJDK java parts separately from native parts

If your build infrastructure requires building java parts separately from native parts you can use the following steps.

**Step 1: Build the java parts**
```shell
$JAVA_HOME/bin/java -ea build.java --mx-home ~/code/mx --mandrel-repo ~/code/mandrel --mandrel-home /tmp/java_step --skip-native
```

**Step 2: Build the java parts**
```shell
/tmp/java_step/bin/java -ea build.java --mx-home ~/code/mx --mandrel-repo ~/code/mandrel --skip-java --skip-clean
```

After the last step you can delete the intermediate JDK with:
```shell
rm -rf /tmp/java_step
```
