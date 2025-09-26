class Constants {
    static final ArrayList<String> QUARKUS_VERSION_RELEASED =
            [
                    '3.28.1',
                    '3.20.3',
                    '3.15.7'
            ]

    static final ArrayList<String> QUARKUS_VERSION_MACOS =
            [
                    '3.20.3',
                    '3.15.7'
            ]

    static final ArrayList<String> QUARKUS_VERSION_BUILDER_IMAGE =
            [
                    '3.20.3',
                    '3.15.7'
            ]

    static final ArrayList<String> QUARKUS_VERSION_RELEASED_PERF = QUARKUS_VERSION_RELEASED

    static final String QUARKUS_VERSION_RELEASED_COMBINATION_FILTER =
            //@formatter:off
            '(' +
                '(MANDREL_BUILD.startsWith("mandrel-23-1") && QUARKUS_VERSION.trim().matches("^3.15.*|^3.20.*")) ||' +
                '(MANDREL_BUILD.startsWith("mandrel-24-") && QUARKUS_VERSION.trim().matches("^3.15.*|^main.*")) ||' +
                '(MANDREL_BUILD.startsWith("mandrel-25-") && QUARKUS_VERSION.trim().matches("^3.2.+|^3.15.*|^main.*")) ||' +
                '(MANDREL_BUILD.startsWith("mandrel-master") && QUARKUS_VERSION.trim().matches("^3.2.+|^3.15.*|^main.*"))' +
            ') && (' +
                '(JDK_VERSION.equals("21") && MANDREL_BUILD.startsWith("mandrel-23-1")) ||' +
                '(JDK_VERSION.equals("23") && MANDREL_BUILD.startsWith("mandrel-24-1")) ||' +
                '(JDK_VERSION.equals("24") && MANDREL_BUILD.startsWith("mandrel-24-2")) ||' +
                '(JDK_VERSION.equals("25") && MANDREL_BUILD.startsWith("mandrel-25-0")) ||' +
                '(JDK_VERSION.equals("26") && JDK_RELEASE.equals("ea") && MANDREL_BUILD.startsWith("mandrel-master"))' +
            ')'
            //@formatter:on

    static final String QUARKUS_VERSION_BUILDER_COMBINATION_FILTER =
            //@formatter:off
            '(BUILDER_IMAGE.contains("jdk-21") && QUARKUS_VERSION.trim().matches("^3.15.*|^3.20.*")) ||' +
            '(BUILDER_IMAGE.contains("jdk-23") && QUARKUS_VERSION.trim().matches("^3.15.*|^main.*")) ||' +
            '(BUILDER_IMAGE.contains("jdk-24") && QUARKUS_VERSION.trim().matches("^3.15.*|^main.*")) ||' +
            '(BUILDER_IMAGE.contains("jdk-25") && QUARKUS_VERSION.trim().matches("^3.2.+|^3.15.*|^main.*"))'
            //@formatter:on

    static final String QUARKUS_MODULES_SUBSET_TESTS = '' +
            'awt,' +
            'no-awt'

    static final String LINUX_PREPARE_MANDREL = '''#!/bin/bash
    set -x
    # Prepare Mandrel
    wget --quiet "https://ci.modcluster.io/view/Mandrel/job/${MANDREL_BUILD}/JDK_VERSION=${JDK_VERSION},JDK_RELEASE=${JDK_RELEASE},LABEL=${LABEL}/${MANDREL_BUILD_NUMBER}/artifact/*zip*/archive.zip"
    if [[ ! -f "archive.zip" ]]; then
        echo "Download failed. Quitting..."
        exit 1
    fi
    unzip archive.zip
    pushd archive
    export MANDREL_TAR=`ls -1 mandrel*.tar.gz`
    tar -xvf "${MANDREL_TAR}"
    source /etc/profile.d/jdks.sh
    # export JAVA_HOME="$( pwd )/$( echo mandrel-java*-*/ )"
    export GRAALVM_HOME="$( pwd )/$( echo mandrel-java*-*/ )"
    # java, javac comes from JDK 17 by default now.
    export PATH="${JAVA_HOME}/bin:${GRAALVM_HOME}/bin:${PATH}"
    
    # Workaround for plain Temurin java to find libnative-image-agent.so
    mkdir lib
    cp ${GRAALVM_HOME}/lib/libnative-image-agent.so lib/
    export LD_LIBRARY_PATH="$( pwd )/lib:$LD_LIBRARY_PATH"
    if [[ ! -e "${GRAALVM_HOME}/bin/native-image" ]]; then
        echo "Cannot find native-image tool. Quitting..."
        exit 1
    fi
    java --version
    native-image --version
    echo "LD_LIBRARY_PATH: ${LD_LIBRARY_PATH}"
    echo "PATH: ${PATH}"
    popd
    '''

    static final String LINUX_INTEGRATION_TESTS = LINUX_PREPARE_MANDREL + '''
    free -h
    df -h
    ps aux | grep java
    export QUARKUS_NATIVE_CONTAINER_BUILD=false
    mvn --batch-mode clean verify -Ptestsuite -DincludeTags=reproducers,perfcheck,runtimes -Dquarkus.version=${QUARKUS_VERSION}
    '''

    static final String MACOS_PREPARE_MANDREL = '''#!/bin/bash
    set -x
    # Prepare Mandrel
    wget --quiet "https://ci.modcluster.io/view/Mandrel/job/${MANDREL_BUILD}/JDK_VERSION=${JDK_VERSION},JDK_RELEASE=${JDK_RELEASE},LABEL=${LABEL}/${MANDREL_BUILD_NUMBER}/artifact/*zip*/archive.zip"
    if [[ ! -f "archive.zip" ]]; then
        echo "Download failed. Quitting..."
        exit 1
    fi
    unzip archive.zip
    pushd archive
    export MANDREL_TAR=$(ls -1 mandrel*.tar.gz)
    tar -xvf "${MANDREL_TAR}"
    export JAVA_HOME=$( pwd )/$( echo mandrel-java*-*/ )/Contents/Home
    export GRAALVM_HOME=${JAVA_HOME}
    export PATH=$JAVA_HOME/bin:/opt/apache-maven-3.9.7/bin:$PATH 
    if [[ ! -e "${GRAALVM_HOME}/bin/native-image" ]]; then
        echo "Cannot find native-image tool. Quitting..."
        exit 1
    fi
    java --version
    native-image --version
    popd
    '''

    static final String MACOS_INTEGRATION_TESTS = MACOS_PREPARE_MANDREL + '''
    vm_stat
    df -h
    ps aux | grep java
    export QUARKUS_NATIVE_CONTAINER_RUNTIME=podman
    export QUARKUS_NATIVE_CONTAINER_BUILD=false
    podman machine init --now || true
    podman machine stop || true
    podman machine set --memory 8192
    eval "$(podman machine start | grep "DOCKER_HOST=")"
    echo -e "GET /_ping HTTP/1.0\\r\\n\\r\\n" | nc -U $(echo $DOCKER_HOST | cut -d: -f2)
    mvn --batch-mode clean verify -Ptestsuite -DincludeTags=reproducers,perfcheck,runtimes \\
    -Dquarkus.version=${QUARKUS_VERSION} -Dquarkus.native.container-runtime=podman -Dpodman.with.sudo=false
    '''

    static final String LINUX_INTEGRATION_TESTS_PERF = LINUX_PREPARE_MANDREL + '''
    free -h
    df -h
    ps aux | grep java
    export PERF_APP_RUNNER_DESCRIPTION=${NODE_NAME}
    #export PERFCHECK_TEST_REQUESTS_MX_HEAP_MB=20480
    #export PERFCHECK_TEST_HEAVY_REQUESTS=10
    export PERFCHECK_TEST_REQUESTS_MX_HEAP_MB=2560
    export PERFCHECK_TEST_HEAVY_REQUESTS=2
    export PERFCHECK_TEST_LIGHT_REQUESTS=100
    mvn --batch-mode clean verify -Ptestsuite -DexcludeTags=all -DincludeTags=perfcheck \\
    -Dtest=PerfCheckTest -Dquarkus.version=${QUARKUS_VERSION}
    '''

    static final String LINUX_INTEGRATION_TESTS_PERF_COMPARATOR = LINUX_PREPARE_MANDREL + '''
    free -h
    df -h
    ps aux | grep java
    git clone ${QUARKUS_REPO}
    pushd quarkus
    if [ "${QUARKUS_VERSION}" == "main_A" ]; then
        git checkout ${QUARKUS_COMMIT_A}
        export QUARKUS_VERSION_GITSHA=${QUARKUS_COMMIT_A}
    else
        git checkout ${QUARKUS_COMMIT_B}
        export QUARKUS_VERSION_GITSHA=${QUARKUS_COMMIT_B}
    fi
    ./mvnw clean install -Dquickly
    popd
    export PERF_APP_RUNNER_DESCRIPTION=${NODE_NAME}
    export PERFCHECK_TEST_REQUESTS_MX_HEAP_MB=20480
    export PERFCHECK_TEST_HEAVY_REQUESTS=10
    export PERFCHECK_TEST_LIGHT_REQUESTS=500
    export QUARKUS_VERSION=999-SNAPSHOT
    mvn --batch-mode clean verify -Ptestsuite -DexcludeTags=all -DincludeTags=perfcheck \\
    -Dtest=PerfCheckTest -Dquarkus.version=${QUARKUS_VERSION}
    '''

    static final String LINUX_QUARKUS_TESTS = LINUX_PREPARE_MANDREL + '''
    free -h
    df -h
    ps aux | grep java
    native-image --version
    # TestContainers tooling
    export DOCKER_HOST=unix:///run/user/${UID}/podman/podman.sock
    export TESTCONTAINERS_RYUK_DISABLED=false
    echo -e "GET /_ping HTTP/1.0\\r\\n\\r\\n" | nc -U $(echo $DOCKER_HOST | cut -d: -f2)
    rm -rf quarkus
    git clone --quiet --depth 1 --branch ${QUARKUS_VERSION} ${QUARKUS_REPO}
    cd quarkus
    export MAVEN_OPTS="-Xmx5g -XX:MaxMetaspaceSize=5g"
    export QUARKUS_NATIVE_CONTAINER_RUNTIME=podman
    export QUARKUS_NATIVE_CONTAINER_BUILD=false
    # Get rid of docker.io
    find . -name "pom.xml" -exec sed -i 's~image>docker.io/~image>quay.io/karmkarm/~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"apachepulsar/pulsar:~"quay.io/karmkarm/apachepulsar/pulsar:~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"axllent/mailpit~"quay.io/karmkarm/axllent/mailpit~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"docker.io/grafana/otel-lgtm:~"quay.io/karmkarm/grafana/otel-lgtm:~g' {} \\;
    find . -name "pom.xml" -exec sed -i 's~<name>redis:~<name>quay.io/karmkarm/redis:~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"rabbitmq:~"quay.io/karmkarm/rabbitmq:~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"reachfive/fake-smtp-server:~"quay.io/karmkarm/reachfive/fake-smtp-server:~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"docker.io/vectorized/redpanda~"quay.io/karmkarm/vectorized/redpanda~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"vectorized/redpanda~"quay.io/karmkarm/vectorized/redpanda~g' {} \\; 
    ./mvnw install --batch-mode -Dquickly
    export JSON_FILE=".github/native-tests.json"
    json_array=()
    while IFS= read -r line; do
        json_array+=("$line")
    done < <(jq -c '.include[]' "$JSON_FILE")
    for line in "${json_array[@]}"; do
        category=$(echo "$line" | jq -r '.category')
        test_modules=$(echo "$line" | jq -r '.["test-modules"]' | tr -d ' ')
        echo "Running tests for category: $category"
        echo "Test modules: $test_modules"
        ./mvnw verify --batch-mode -f integration-tests/pom.xml -fn \\
                      -Dnative -Dquarkus.native.native-image-xmx=8g \\
                      -Dquarkus.native.container-build=false \\
                      -Dmaven.test.failure.ignore=true -Dno-format \\
                      -Dtest-containers -Dstart-containers -Ddocker -pl "$test_modules" || true
    done
    '''

    static final String LINUX_CONTAINER_INTEGRATION_TESTS = '''#!/bin/bash
    set +e
    set -x
    free -h
    df -h
    ps aux | grep java
    export QUARKUS_NATIVE_CONTAINER_RUNTIME=podman
    source /etc/profile.d/jdks.sh
    sudo podman stop $(sudo podman ps -a -q)
    sudo podman rm $(sudo podman ps -a -q)
    yes | sudo podman volume prune
    rm -rf /tmp/run-1000/libpod/tmp/pause.pid
    podman stop $(podman ps -a -q)
    podman rm $(podman ps -a -q)
    yes | podman volume prune
    sudo podman pull ${BUILDER_IMAGE}
    if [ "$?" -ne 0 ]; then
        echo There was a problem pulling the image ${BUILDER_IMAGE}. We cannot proceed.
        exit 1
    fi
    sudo podman run ${BUILDER_IMAGE} --version
    if [ "$?" -ne 0 ]; then
        echo There was a problem running --version with  ${BUILDER_IMAGE}. We cannot proceed.
        exit 1
    fi
    # TestContainers tooling
    export DOCKER_HOST=unix:///run/user/${UID}/podman/podman.sock
    export TESTCONTAINERS_RYUK_DISABLED=true
    export QUARKUS_NATIVE_CONTAINER_RUNTIME=podman
    echo -e "GET /_ping HTTP/1.0\\r\\n\\r\\n" | nc -U $(echo $DOCKER_HOST | cut -d: -f2)

    export PATH="${JAVA_HOME}/bin:${PATH}"
    mvn --batch-mode clean verify -Ptestsuite-builder-image -Dquarkus.version=${QUARKUS_VERSION} \\
        -Dquarkus.native.container-runtime=podman \\
        -Drootless.container-runtime=false -Dpodman.with.sudo=true \\
        -Dquarkus.native.builder-image=${BUILDER_IMAGE}
    '''

    static final String LINUX_CONTAINER_QUARKUS_TESTS = '''#!/bin/bash
    free -h
    df -h
    ps aux | grep java
    export QUARKUS_NATIVE_CONTAINER_RUNTIME=podman
    source /etc/profile.d/jdks.sh
    set +e
       sudo podman stop $(sudo podman ps -a -q)
       sudo podman rm $(sudo podman ps -a -q)
       yes | sudo podman volume prune
       rm -rf /tmp/run-1000/libpod/tmp/pause.pid
       podman stop $(podman ps -a -q)
       podman rm $(podman ps -a -q)
       yes | podman volume prune
    set -e
    sudo podman pull ${BUILDER_IMAGE}
    if [ "$?" -ne 0 ]; then
        echo There was a problem pulling the image ${BUILDER_IMAGE}. We cannot proceed.
        exit 1
    fi
    # TestContainers tooling
    export DOCKER_HOST=unix:///run/user/${UID}/podman/podman.sock
    export TESTCONTAINERS_RYUK_DISABLED=false
    echo -e "GET /_ping HTTP/1.0\\r\\n\\r\\n" | nc -U $(echo $DOCKER_HOST | cut -d: -f2)
    rm -rf quarkus
    git clone --quiet --depth 1 --branch ${QUARKUS_VERSION} ${QUARKUS_REPO}
    cd quarkus
    export MAVEN_OPTS="-Xmx5g -XX:MaxMetaspaceSize=5g"
    export QUARKUS_NATIVE_CONTAINER_RUNTIME=podman
    export PATH="${JAVA_HOME}/bin:${PATH}"
    # Get rid of docker.io
    find . -name "pom.xml" -exec sed -i 's~image>docker.io/~image>quay.io/karmkarm/~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"apachepulsar/pulsar:~"quay.io/karmkarm/apachepulsar/pulsar:~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"axllent/mailpit~"quay.io/karmkarm/axllent/mailpit~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"docker.io/grafana/otel-lgtm:~"quay.io/karmkarm/grafana/otel-lgtm:~g' {} \\;
    find . -name "pom.xml" -exec sed -i 's~<name>redis:~<name>quay.io/karmkarm/redis:~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"rabbitmq:~"quay.io/karmkarm/rabbitmq:~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"reachfive/fake-smtp-server:~"quay.io/karmkarm/reachfive/fake-smtp-server:~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"docker.io/vectorized/redpanda~"quay.io/karmkarm/vectorized/redpanda~g' {} \\;
    find . -name "*.java" -exec sed -i 's~"vectorized/redpanda~"quay.io/karmkarm/vectorized/redpanda~g' {} \\; 
    ./mvnw install --batch-mode -Dquickly
    export JSON_FILE=".github/native-tests.json"
    json_array=()
    while IFS= read -r line; do
        json_array+=("$line")
    done < <(jq -c '.include[]' "$JSON_FILE")
    for line in "${json_array[@]}"; do
        category=$(echo "$line" | jq -r '.category')
        test_modules=$(echo "$line" | jq -r '.["test-modules"]' | tr -d ' ')
        echo "Running tests for category: $category"
        echo "Test modules: $test_modules"
        ./mvnw verify --batch-mode -f integration-tests/pom.xml -fn \\
                      -Dnative -Dquarkus.native.native-image-xmx=8g \\
                      -Dquarkus.native.container-build=true \\
                      -Dquarkus.native.builder-image="${BUILDER_IMAGE}" \\
                      -Dmaven.test.failure.ignore=true -Dno-format \\
                      -Dtest-containers -Dstart-containers -Ddocker -pl "$test_modules" || true
    done
    '''

    static final String WINDOWS_PREPARE_MANDREL = '''
    call vcvars64
    IF NOT %ERRORLEVEL% == 0 ( exit 1 )
    set downloadCommand= ^
        $c = New-Object System.Net.WebClient; ^
        $url = 'https://ci.modcluster.io/view/Mandrel/job/%MANDREL_BUILD%/JDK_VERSION=%JDK_VERSION%,JDK_RELEASE=%JDK_RELEASE%,LABEL=%LABEL%/%MANDREL_BUILD_NUMBER%/artifact/*zip*/archive.zip'; $file = 'archive.zip'; ^
        Write-Host $url; ^
        $c.DownloadFile($url, $file);
    
    powershell -Command "%downloadCommand%"
    if not exist archive.zip exit 1
    powershell -c "Expand-Archive -Path archive.zip -DestinationPath . -Force"
    pushd archive
        for /f "tokens=5" %%g in ('dir mandrel-*.zip ^| findstr /R mandrel-.*.zip') do set ZIP_NAME=%%g
        powershell -c "Expand-Archive -Path %ZIP_NAME% -DestinationPath . -Force"
        echo ZIP_NAME: %ZIP_NAME%
        for /f "tokens=5" %%g in ('dir /AD mandrel-* ^| findstr /R mandrel-.*') do set GRAALVM_HOME=%cd%\\%%g
        echo GRAALVM_HOME: %GRAALVM_HOME%
        echo JAVA_HOME: %JAVA_HOME%
        REM set "JAVA_HOME=%GRAALVM_HOME%"
        REM java and javac come from JDK 17 by default now
        set "PATH=%JAVA_HOME%\\bin;%GRAALVM_HOME%\\bin;%GRAALVM_HOME%\\lib;%PATH%"
        if not exist "%GRAALVM_HOME%\\bin\\native-image.cmd" (
            echo "Cannot find native-image tool. Quitting..."
            exit 1
        ) else (
            echo "native-image.cmd is present, good."
            echo "Java version:"
            cmd /C java --version
            echo "native-image version:"
            cmd /C native-image --version
        )
    popd
    '''

    static final String WINDOWS_INTEGRATION_TESTS = WINDOWS_PREPARE_MANDREL + '''
    mvn --batch-mode clean verify -Ptestsuite -Dquarkus.version=%QUARKUS_VERSION%
    '''

    static final String WINDOWS_QUARKUS_TESTS = WINDOWS_PREPARE_MANDREL + '''
    git clone --depth 1 --branch %QUARKUS_VERSION% %QUARKUS_REPO%
    cd quarkus
    set "MAVEN_OPTS=-Xmx9g -XX:MaxMetaspaceSize=4g"
    call mvnw --batch-mode install -Dquickly
    set "MAVEN_OPTS=-Xmx5g -XX:MaxMetaspaceSize=5g"
    @echo off
    setlocal enabledelayedexpansion
    set "JSON_FILE=.github\\native-tests.json"
    for /f "usebackq delims=" %%A in (`jq -c ".include[]" "%JSON_FILE%"`) do (
        set "line=%%A"
        for /f "usebackq delims=" %%B in (`echo !line! ^| jq -r ".category"`) do set "category=%%B"
        for /f "usebackq delims=" %%C in (`echo !line! ^| jq -r ".\\"test-modules\\""`) do set "test_modules=%%C"
        set "test_modules=!test_modules: =!"
        echo Running tests for category: !category!
        echo Test modules: !test_modules!
        call mvnw verify --batch-mode -f integration-tests/pom.xml -fn ^
          -Dnative -Dquarkus.native.native-image-xmx=8g ^
          -Dquarkus.native.container-build=false ^
          -Dmaven.test.failure.ignore=true -Dno-format ^
          -pl "!test_modules!" || exit /b 0
    )
    endlocal
    '''
}
