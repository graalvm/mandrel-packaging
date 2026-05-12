# Mandrel

Mandrel {{FULL_VERSION}} is a downstream distribution of the [GraalVM community edition](https://github.com/{{UPSTREAM_REPO}}/releases/tag/{{UPSTREAM_TAG}}).
Mandrel's main goal is to provide a `native-image` release specifically to support [Quarkus](https://quarkus.io).
The aim is to align the `native-image` capabilities from GraalVM with OpenJDK and Red Hat Enterprise Linux libraries to improve maintainability for native Quarkus applications.

## How Does Mandrel Differ From Graal

Mandrel releases are built from a code base derived from the upstream GraalVM code base, with only minor changes but some significant exclusions.
They support the same native image capability as GraalVM with no significant changes to functionality.
They do not include support for Polyglot programming via the Truffle interpreter and compiler framework.
In consequence, it is not possible to extend Mandrel by downloading languages from the Truffle language catalogue.

Mandrel is also built slightly differently to GraalVM, using the standard OpenJDK project release of jdk {{JDK_VERSION}}.
This means it does not profit from a few small enhancements that Oracle have added to the version of OpenJDK used to build their own GraalVM downloads.
Most of these enhancements are to the JVMCI module that allows the Graal compiler to be run inside OpenJDK.
The others are small cosmetic changes to behaviour.
These enhancements may in some cases cause minor differences in the progress of native image generation.
They should not cause the resulting images themselves to execute in a noticeably different manner.

### Prerequisites

Mandrel's `native-image` depends on the following packages:
* freetype-devel
* gcc
* glibc-devel
* libstdc++-static
* zlib-devel

On Fedora/CentOS/RHEL they can be installed with:
```bash
dnf install glibc-devel zlib-devel gcc freetype-devel libstdc++-static
```

**Note**: The package might be called `glibc-static` or `libstdc++-devel` instead of `libstdc++-static` depending on your system.
If the system is missing stdc++, `gcc-c++` package is needed too.

On Ubuntu-like systems with:
```bash
apt install g++ zlib1g-dev libfreetype6-dev
```

Arch-like systems:
```bash
sudo pacman -S freetype2 gcc glibc lib32-gcc-libs zlib
```

## Quick start Linux/MacOS
Mac users:
* Use artifact mandrel-java{{JDK_MAJOR}}-macos-aarch64-{{FULL_VERSION}}.tar.gz
* Use JAVA_HOME="$( pwd )/mandrel-java{{JDK_MAJOR}}-{{FULL_VERSION}}/Contents/Home"
* Use `xattr -c -r ./path/to/mandrel` to prevent quarantine

```
$ curl -O -J -L 'https://github.com/graalvm/mandrel/releases/download/{{VERSION}}/mandrel-java{{JDK_MAJOR}}-linux-amd64-{{FULL_VERSION}}.tar.gz'
$ tar -xf mandrel-java{{JDK_MAJOR}}-linux-amd64-{{FULL_VERSION}}.tar.gz
$ export JAVA_HOME="$( pwd )/mandrel-java{{JDK_MAJOR}}-{{FULL_VERSION}}"
$ export GRAALVM_HOME="${JAVA_HOME}"
$ export PATH="${JAVA_HOME}/bin:${PATH}"
$ curl -O -J "https://code.quarkus.io/d?e=rest&cn=code.quarkus.io&j={{JDK_MAJOR}}"
$ unzip code-with-quarkus.zip
$ cd code-with-quarkus/
$ ./mvnw package -Pnative
$ ./target/code-with-quarkus-1.0.0-SNAPSHOT-runner
```

## Quick start Windows
Note that `vcvars64` command is usually located in your VS installation and you should add it to your PATH,
e.g. `C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build`.

```
powershell -c "Start-BitsTransfer -Source 'https://github.com/graalvm/mandrel/releases/download/{{VERSION}}/mandrel-java{{JDK_MAJOR}}-windows-amd64-{{FULL_VERSION}}.zip'"
powershell -c "Expand-Archive -Path mandrel-java{{JDK_MAJOR}}-windows-amd64-{{FULL_VERSION}}.zip -DestinationPath . -Force"
SET JAVA_HOME=%CD%\mandrel-java{{JDK_MAJOR}}-{{FULL_VERSION}}
SET GRAALVM_HOME=%JAVA_HOME%
SET PATH=%JAVA_HOME%\bin;%PATH%
vcvars64
powershell -Command "Invoke-WebRequest -Uri 'https://code.quarkus.io/d?e=rest&cn=code.quarkus.io&j={{JDK_MAJOR}}' -OutFile 'code-with-quarkus.zip'"
powershell -c "Expand-Archive -Path code-with-quarkus.zip -DestinationPath . -Force"
cd code-with-quarkus
mvnw package -Pnative
target\code-with-quarkus-1.0.0-SNAPSHOT-runner
```

### Quarkus builder image

Mandrel Quarkus builder image can be used to build a Quarkus native Linux executable right away without any GRAALVM_HOME setup.

```bash
curl -O -J 'https://code.quarkus.io/d?e=rest&cn=code.quarkus.io'
unzip code-with-quarkus.zip
cd code-with-quarkus
./mvnw package -Pnative -Dquarkus.native.container-build=true -Dquarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-{{JDK_MAJOR}}
./target/code-with-quarkus-1.0.0-SNAPSHOT-runner
```

One can use the builder image on Windows with e.g. Podman Desktop, see [Podman For Windows](https://quarkus.io/blog/podman-for-windows/).

```batchfile
powershell -c "Invoke-WebRequest -OutFile quarkus.zip -Uri https://code.quarkus.io/d?e=rest&cn=code.quarkus.io"
powershell -c "Expand-Archive -Path quarkus.zip -DestinationPath . -Force"
cd code-with-quarkus
mvnw package -Pnative -Dquarkus.native.container-build=true -Dquarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-{{JDK_MAJOR}}
podman build -f src/main/docker/Dockerfile.native -t my-quarkus-mandrel-app .
podman run -i --rm -p 8080:8080 my-quarkus-mandrel-app
```

### Changelog

For a complete list of changes please visit https://github.com/graalvm/mandrel/compare/{{PREV_VERSION}}...{{VERSION}}

---
Mandrel {{FULL_VERSION}}
OpenJDK used: {{JDK_VERSION}}
