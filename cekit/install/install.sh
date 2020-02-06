#!/bin/bash

set -e

SRC_DIR=$(dirname $0)/src

cp $SRC_DIR/run.sh /home/mandrel
cp $SRC_DIR/build.java /home/mandrel
