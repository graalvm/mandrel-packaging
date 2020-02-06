#!/usr/bin/env bash

set -e -x

setEnv() {
    if [[ -n "${JBANG_CLONE}" ]]; then
        RUN_OPTIONS="-v ${JBANG_CLONE}:/home/mandrel/jbang ${RUN_OPTIONS}"
        SKIP_JBANG=1
    fi

    if [[ -n "${SKIP_JBANG}" ]]; then
        RUN_OPTIONS="-e SKIP_JBANG=1 ${RUN_OPTIONS}"
    fi
}

dockerRun() {
    echo "Opening an interactive terminal in the latest JDG snapshot builder image"
    echo " For building: ./run.sh "
    echo ""

    docker run -it ${RUN_OPTIONS} --entrypoint /bin/bash mandrel-packaging
    exit 0
}

main() {
    setEnv
    dockerRun
}

main
