#!/usr/bin/env bash

set -e

setEnv() {
    if [ -f .env ]; then
        set -a
        . ./.env
        set +a
    fi

    if [[ -n "${PACKAGING_CLONE}" ]]; then
        RUN_OPTIONS="-v ${PACKAGING_CLONE}:/home/mandrel/mandrel-packaging ${RUN_OPTIONS}"
        SKIP_PACKAGING=1
    fi

    if [[ -n "${SKIP_PACKAGING}" ]]; then
        RUN_OPTIONS="-e SKIP_PACKAGING=1 ${RUN_OPTIONS}"
    fi

    if [[ -n "${MANDREL_CLONE}" ]]; then
        RUN_OPTIONS="-v ${MANDREL_CLONE}:/tmp/mandrel ${RUN_OPTIONS}"
        SKIP_MANDREL=1
    fi

    if [[ -n "${SKIP_MANDREL}" ]]; then
        RUN_OPTIONS="-e SKIP_MANDREL=1 ${RUN_OPTIONS}"
    fi

    if [[ -n "${MAVEN_REPOSITORY}" ]]; then
        RUN_OPTIONS="-v ${MAVEN_REPOSITORY}:/home/mandrel/.m2/repository ${RUN_OPTIONS}"
    fi
}

dockerRun() {
    echo "Opening an interactive terminal in the latest JDG snapshot builder image"
    echo " To build locally:"
    echo "   ./run.sh --install --version 19.3.1-redhat-1 --verbose"
    echo ""
    echo " To build with a maven proxy:"
    echo "    ./run.sh --install --version 19.3.1-redhat-1 --maven-proxy https://repo1.maven.org/maven2/ --verbose"
    echo ""
    echo " To deploy:"
    echo "    ./run.sh --deploy --version 19.3.1 --verbose"
    echo ""

    docker run -it ${RUN_OPTIONS} --entrypoint /bin/bash mandrel-packaging
    exit 0
}

main() {
    setEnv
    dockerRun
}

main
