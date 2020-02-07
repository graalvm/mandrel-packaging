#!/bin/bash

set -e

pushd /opt
git clone https://github.com/graalvm/mx --depth 10 -b master
popd
