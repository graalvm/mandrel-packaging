.PHONY: all build-image run-image

SCRIPT_PATH ?= src/image.java

all: build-image run-image

build-image:
	cekit -v build docker --no-squash

run-image:
	./run-image.sh
