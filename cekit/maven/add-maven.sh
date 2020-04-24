#!/bin/bash

set -e

curl http://mirror.easyname.ch/apache/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz > /opt/maven.tar.gz
mkdir /opt/maven
cd /opt
tar -xzvpf maven.tar.gz -C maven --strip-components 1
rm maven.tar.gz
