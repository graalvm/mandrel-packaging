#!/usr/bin/env bash

if [[ "${VERBOSE}" == "true" ]]; then
    set -x
fi

MX_HOME=${MX_HOME:-/opt/mx}
JAVA_HOME=${JAVA_HOME:-/opt/jdk}
MANDREL_REPO=${MANDREL_REPO:-/tmp/mandrel}
MANDREL_JDK=${MANDREL_JDK:-/opt/mandrelJDK}

### Build Mandrel
## JVM bits
basename="$(dirname $0)"
${JAVA_HOME}/bin/java -ea $basename/build.java --version 20.1.0.redhat-00001 --maven-local-repository /tmp/.m2/repository --mx-home ${MX_HOME} --mandrel-home ${MANDREL_REPO}

## native bits
pushd ${MANDREL_REPO}/substratevm
${MX_HOME}/mx build --projects com.oracle.svm.native.libchelper
${MX_HOME}/mx build --projects com.oracle.svm.native.jvm.posix
${MX_HOME}/mx build --projects com.oracle.svm.native.strictmath
${MX_HOME}/mx build --only native-image.image-bash
popd

### Copy default JDK
rm -rf ${MANDREL_JDK}
cp -r ${JAVA_HOME} ${MANDREL_JDK}

### Copy needed jars
mkdir ${MANDREL_JDK}/lib/svm
cp ${MANDREL_REPO}/substratevm/mxbuild/dists/jdk1.8/library-support.jar ${MANDREL_JDK}/lib/svm

mkdir ${MANDREL_JDK}/lib/svm/builder
cp ${MANDREL_REPO}/substratevm/mxbuild/dists/jdk11/{svm,pointsto}.jar ${MANDREL_JDK}/lib/svm/builder
cp ${MANDREL_REPO}/substratevm/mxbuild/dists/jdk1.8/objectfile.jar ${MANDREL_JDK}/lib/svm/builder

mkdir ${MANDREL_JDK}/languages
cp ${MANDREL_REPO}/truffle/mxbuild/dists/jdk11/truffle-nfi.jar ${MANDREL_JDK}/languages

mkdir ${MANDREL_JDK}/lib/graalvm
cp ${MANDREL_REPO}/substratevm/mxbuild/dists/jdk1.8/svm-driver.jar ${MANDREL_JDK}/lib/graalvm

## The following jars are not included in the GraalJDK created by `mx --components="Native Image" build`
mkdir ${MANDREL_JDK}/lib/jvmci
cp ${MANDREL_REPO}/sdk/mxbuild/dists/jdk11/graal-sdk.jar ${MANDREL_JDK}/lib/jvmci
cp ${MANDREL_REPO}/compiler/mxbuild/dists/jdk11/graal.jar ${MANDREL_JDK}/lib/jvmci

mkdir ${MANDREL_JDK}/lib/truffle
cp ${MANDREL_REPO}/truffle/mxbuild/dists/jdk11/truffle-api.jar ${MANDREL_JDK}/lib/truffle


### Copy native bits
mkdir -p ${MANDREL_JDK}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/substratevm/src/com.oracle.svm.native.libchelper/include/amd64cpufeatures.h ${MANDREL_JDK}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/substratevm/src/com.oracle.svm.native.libchelper/include/aarch64cpufeatures.h ${MANDREL_JDK}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/substratevm/src/com.oracle.svm.libffi/include/svm_libffi.h ${MANDREL_JDK}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/truffle/src/com.oracle.truffle.nfi.native/include/trufflenfi.h ${MANDREL_JDK}/lib/svm/clibraries/linux-amd64/include
cp ${MANDREL_REPO}/substratevm/mxbuild/linux-amd64/src/com.oracle.svm.native.libchelper/amd64/liblibchelper.a ${MANDREL_JDK}/lib/svm/clibraries/linux-amd64
cp ${MANDREL_REPO}/substratevm/mxbuild/linux-amd64/src/com.oracle.svm.native.jvm.posix/amd64/libjvm.a ${MANDREL_JDK}/lib/svm/clibraries/linux-amd64
cp ${MANDREL_REPO}/substratevm/mxbuild/linux-amd64/src/com.oracle.svm.native.strictmath/amd64/libstrictmath.a ${MANDREL_JDK}/lib/svm/clibraries/linux-amd64
mkdir ${MANDREL_JDK}/lib/svm/bin
cp ${MANDREL_REPO}/sdk/mxbuild/linux-amd64/native-image.image-bash/native-image ${MANDREL_JDK}/lib/svm/bin/native-image
## Create symbolic link in bin
ln -s ../lib/svm/bin/native-image ${MANDREL_JDK}/bin/native-image

### Fix native-image launcher
sed -i -e 's!EnableJVMCI!EnableJVMCI --upgrade-module-path ${location}/../../jvmci/graal.jar --add-modules "org.graalvm.truffle,org.graalvm.sdk" --module-path ${location}/../../truffle/truffle-api.jar:${location}/../../jvmci/graal-sdk.jar!' \
    ${MANDREL_JDK}/lib/svm/bin/native-image

### Create tarball
tar -czf mandrel.tar.gz -C ${MANDREL_JDK}/.. mandrelJDK