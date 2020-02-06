#!/bin/bash

set -x -e

echo "HOME=${HOME}"
echo "PATH=${PATH}"

#rm -rf mandrel-packaging
#git clone -q git@github.com:galderz/mandrel-packaging.git --depth 10 -b master
#sudo chown mandrel:mandrel mandrel-packaging
#
#cd ${HOME}/mandrel-packaging/mandrel-1.0
#make

#pushd "${HOME}"
#
## TODO use a local volume to speed up
#git clone https://github.com/maxandersen/jbang -depth 10 -b master
#pushd jbang
#./gradlew build install -x test
#popd
#
#export PATH="${HOME}/jbang/build/install/jbang/bin:${PATH}"
#jbang build.java
