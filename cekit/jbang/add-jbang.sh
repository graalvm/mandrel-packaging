#!/bin/bash

set -e

pushd /opt
git clone https://github.com/maxandersen/jbang --depth 10 -b master
pushd jbang
./gradlew --no-daemon -x test build install
popd
popd
