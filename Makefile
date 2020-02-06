.PHONY: build-image run-image test

SCRIPT_PATH ?= src/image.java

build-image:
	eval `jbang ${SCRIPT_PATH} build-image-command`

run-image:
	eval `jbang ${SCRIPT_PATH} run-image-command`

test:
	jbang ${SCRIPT_PATH} test
