.PHONY: build-image run-image

SCRIPT_PATH ?= src/image.java

build-image:
	cekit -v build docker --no-squash

run-image:
	./run-image.sh
