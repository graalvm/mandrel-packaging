#!/bin/bash

set -e

export PATH="${JAVA_BIN}:${PATH}"
export PATH="${MAVEN_BIN}:${PATH}"

echo "HOME=${HOME}"
echo "PATH=${PATH}"

pushd "${HOME}"

if [[ -z "${SKIP_PACKAGING}" ]]; then
    rm -rf mandrel-packaging
    git clone https://github.com/galderz/mandrel-packaging --depth 10 -b master
fi

if [[ -z "${SKIP_MANDREL}" ]]; then
    rm -rf mandrel
    git clone https://github.com/oracle/graal --depth 10 -b master mandrel
fi

pushd mandrel-packaging

java src/build.java "$@"
