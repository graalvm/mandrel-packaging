#!/bin/bash

set -e

MX_VERSION="5.259.0"

pushd /opt

curl -L https://github.com/graalvm/mx/tarball/${MX_VERSION} > mx.tar.gz
mkdir -p mx
tar -xzvpf mx.tar.gz -C mx --strip-components 1
sudo chown -R mandrel:mandrel mx
rm -f mx.tar.gz

popd
