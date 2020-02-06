#!/bin/bash

set -x -e

echo "HOME=${HOME}"
echo "PATH=${PATH}"

pushd "${HOME}"

if [[ -z "${SKIP_JBANG}" ]]; then
    rm -rf jbang
    git clone https://github.com/maxandersen/jbang -depth 10 -b master
    pushd jbang
    ./gradlew build install -x test
    popd
fi
export PATH="${HOME}/jbang/build/install/jbang/bin:${PATH}"

if [[ -z "${SKIP_PACKAGING}" ]]; then
    rm -rf mandrel-packaging
    git clone https://github.com/galderz/mandrel-packaging -depth 10 -b master
fi

pushd mandrel-packaging

jbang src/build.java
