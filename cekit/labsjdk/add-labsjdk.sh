#!/bin/bash

set -e

curl -L https://github.com/graalvm/labs-openjdk-11/releases/download/jvmci-20.0-b02/labsjdk-ce-11.0.6+9-jvmci-20.0-b02-linux-amd64.tar.gz > /opt/labsjdk.tar.gz
mkdir /opt/labsjdk
cd /opt
tar -xzvpf labsjdk.tar.gz -C labsjdk --strip-components 1
