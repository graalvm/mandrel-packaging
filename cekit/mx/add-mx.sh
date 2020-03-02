#!/bin/bash

set -e

pushd /opt
git clone https://github.com/graalvm/mx
sudo chown -R mandrel:mandrel mx
popd
