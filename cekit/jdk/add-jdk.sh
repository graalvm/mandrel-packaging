#!/bin/bash

set -e

curl -L https://github.com/AdoptOpenJDK/openjdk11-upstream-binaries/releases/download/jdk-11.0.8%2B4/OpenJDK11U-jdk_x64_linux_11.0.8_4_ea.tar.gz > /opt/jdk.tar.gz
curl -L https://github.com/AdoptOpenJDK/openjdk11-upstream-binaries/releases/download/jdk-11.0.8%2B4/OpenJDK11U-static-libs_x64_linux_11.0.8_4_ea.tar.gz > /opt/static-libs.tar.gz

mkdir /opt/jdk
pushd /opt

tar -xzvpf jdk.tar.gz -C jdk --strip-components 1
tar -xzvpf static-libs.tar.gz -C jdk --strip-components 1

rm jdk.tar.gz
rm static-libs.tar.gz

popd
