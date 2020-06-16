#!/usr/bin/env bash

if [[ "${VERBOSE}" == "true" ]]; then
    set -x
    VERBOSE_BUILD=--verbose
    VERBOSE_MX=-v
fi

MX_HOME=${MX_HOME:-/opt/mx}
JAVA_HOME=${JAVA_HOME:-/opt/jdk}
MANDREL_REPO=${MANDREL_REPO:-/tmp/mandrel}
MANDREL_HOME=${MANDREL_HOME:-/opt/mandrelJDK}
MAVEN_REPO=${MAVEN_REPO:-~/.m2/repository}
if [[ "${SKIP_CLEAN}" == "true" ]]; then
    SKIP_CLEAN_FLAG=--skipClean
fi

pushd ${MANDREL_REPO}/substratevm
MANDREL_VERSION=${MANDREL_VERSION:-$(git describe)}
popd

### Build Mandrel
## JVM bits
basename="$(dirname $0)"
${JAVA_HOME}/bin/java -ea $basename/src/build.java ${VERBOSE_BUILD} --version ${MANDREL_VERSION}.redhat-00001 --maven-local-repository ${MAVEN_REPO} --mx-home ${MX_HOME} --mandrel-home ${MANDREL_REPO} ${SKIP_CLEAN_FLAG}

## native bits
pushd ${MANDREL_REPO}/substratevm
${MX_HOME}/mx ${VERBOSE_MX} build --projects com.oracle.svm.native.libchelper
${MX_HOME}/mx ${VERBOSE_MX} build --projects com.oracle.svm.native.jvm.posix
${MX_HOME}/mx ${VERBOSE_MX} build --only native-image.image-bash
popd

### Copy default JDK
rm -rf ${MANDREL_HOME}
cp -r ${JAVA_HOME} ${MANDREL_HOME}

### Copy needed jars
mkdir ${MANDREL_HOME}/lib/svm
cp ${MANDREL_REPO}/substratevm/mxbuild/dists/jdk1.8/library-support.jar ${MANDREL_HOME}/lib/svm

mkdir ${MANDREL_HOME}/lib/svm/builder
cp ${MANDREL_REPO}/substratevm/mxbuild/dists/jdk11/{svm,pointsto}.jar ${MANDREL_HOME}/lib/svm/builder
cp ${MANDREL_REPO}/substratevm/mxbuild/dists/jdk1.8/objectfile.jar ${MANDREL_HOME}/lib/svm/builder

mkdir ${MANDREL_HOME}/languages
cp ${MANDREL_REPO}/truffle/mxbuild/dists/jdk11/truffle-nfi.jar ${MANDREL_HOME}/languages

mkdir ${MANDREL_HOME}/lib/graalvm
cp ${MANDREL_REPO}/substratevm/mxbuild/dists/jdk1.8/svm-driver.jar ${MANDREL_HOME}/lib/graalvm

## The following jars are not included in the GraalJDK created by `mx --components="Native Image" build`
mkdir ${MANDREL_HOME}/lib/jvmci
cp ${MANDREL_REPO}/sdk/mxbuild/dists/jdk11/graal-sdk.jar ${MANDREL_HOME}/lib/jvmci
cp ${MANDREL_REPO}/compiler/mxbuild/dists/jdk11/graal.jar ${MANDREL_HOME}/lib/jvmci

mkdir ${MANDREL_HOME}/lib/truffle
cp ${MANDREL_REPO}/truffle/mxbuild/dists/jdk11/truffle-api.jar ${MANDREL_HOME}/lib/truffle


### Copy native bits
mkdir -p ${MANDREL_HOME}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/substratevm/src/com.oracle.svm.native.libchelper/include/amd64cpufeatures.h ${MANDREL_HOME}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/substratevm/src/com.oracle.svm.native.libchelper/include/aarch64cpufeatures.h ${MANDREL_HOME}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/substratevm/src/com.oracle.svm.libffi/include/svm_libffi.h ${MANDREL_HOME}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/truffle/src/com.oracle.truffle.nfi.native/include/trufflenfi.h ${MANDREL_HOME}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/substratevm/mxbuild/linux-amd64/src/com.oracle.svm.native.libchelper/amd64/liblibchelper.a ${MANDREL_HOME}/lib/svm/clibraries/linux-amd64
cp ${MANDREL_REPO}/substratevm/mxbuild/linux-amd64/src/com.oracle.svm.native.jvm.posix/amd64/libjvm.a ${MANDREL_HOME}/lib/svm/clibraries/linux-amd64
mkdir ${MANDREL_HOME}/lib/svm/bin
cp ${MANDREL_REPO}/sdk/mxbuild/linux-amd64/native-image.image-bash/native-image ${MANDREL_HOME}/lib/svm/bin/native-image
## Create symbolic link in bin
ln -s ../lib/svm/bin/native-image ${MANDREL_HOME}/bin/native-image

### Fix native-image launcher
sed -i -e "s!EnableJVMCI!EnableJVMCI -Dorg.graalvm.version=\"${MANDREL_VERSION} (Mandrel Distribution)\" --upgrade-module-path \${location}/../../jvmci/graal.jar --add-modules \"org.graalvm.truffle,org.graalvm.sdk\" --module-path \${location}/../../truffle/truffle-api.jar:\${location}/../../jvmci/graal-sdk.jar!" \
    "${MANDREL_HOME}/lib/svm/bin/native-image"

### Create tarball
tar -czf mandrel.tar.gz -C ${MANDREL_HOME}/.. mandrelJDK
