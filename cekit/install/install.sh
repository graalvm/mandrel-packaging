#!/bin/bash

set -e

ADDED_DIR=$(dirname $0)/added

cp $ADDED_DIR/run.sh /home/mandrel
