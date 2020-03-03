#!/bin/bash

set -e

function fetchMx {
    local version=$1
    curl -L https://github.com/graalvm/mx/tarball/${version} > mx.tar.gz
    mkdir -p mx/${version}
    tar -xzvpf mx.tar.gz -C mx/${version} --strip-components 1
    sudo chown -R mandrel:mandrel mx/${version}
    rm -f mx.tar.gz
}

pushd /opt
fetchMx "5.244.0"
fetchMx "5.247.11"
popd
