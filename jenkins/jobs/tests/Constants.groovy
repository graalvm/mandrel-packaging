class Constants {
    static final ArrayList<String> QUARKUS_VERSION_RELEASED =
            [
                    '2.16.2.Final',
                    '2.13.7.Final',
                    '2.7.7.Final'
            ]

    static final ArrayList<String> QUARKUS_VERSION_RELEASED_PERF =
            [
                    '2.16.2.Final',
                    '2.13.7.Final',
                    '2.7.7.Final',
                    '3.0.0.Alpha4'
            ]

    static final ArrayList<String> QUARKUS_VERSION_SHORT =
            [
                    '2.13.7.Final',
                    '2.7.7.Final',
                    'main'
            ]

    static final String QUARKUS_VERSION_SHORT_COMBINATION_FILTER =
            //@formatter:off
            '(QUARKUS_VERSION.startsWith("2.7") && MANDREL_BUILD.startsWith("mandrel-21")) ||' +
            '(QUARKUS_VERSION.startsWith("2.13") && MANDREL_BUILD.startsWith("mandrel-22") && JDK_VERSION.equals("17")) ||' +
            '(QUARKUS_VERSION.equals("main") && !MANDREL_BUILD.startsWith("mandrel-21") && JDK_VERSION.equals("17"))'
            //@formatter:on

    static final String QUARKUS_VERSION_RELEASED_COMBINATION_FILTER =
            //@formatter:off
            '(' +
                '(MANDREL_BUILD.startsWith("mandrel-21") && QUARKUS_VERSION.startsWith("2.7")) ||' +
                '(MANDREL_BUILD.startsWith("mandrel-22") && QUARKUS_VERSION.trim().matches("^2.13.*|^2.16.*|^3.*|^main.*")) ||' +
                '(MANDREL_BUILD.startsWith("mandrel-master") && QUARKUS_VERSION.trim().matches("^2.16.*|^3.*|^main.*"))' +
            ') && (' +
                '(JDK_VERSION.equals("20") && JDK_RELEASE.equals("ea") && MANDREL_BUILD.startsWith("mandrel-master")) ||' +
                '(JDK_VERSION.equals("11") && MANDREL_BUILD.startsWith("mandrel-21")) ||' +
                'JDK_VERSION.equals("17")' +
            ')'
            //@formatter:on

    static final ArrayList<String> QUARKUS_VERSION_BUILDER_IMAGE =
            [
                    '2.13.7.Final',
                    '2.7.7.Final'
            ]

    static final String QUARKUS_MODULES_TESTS = '' +
            '!bouncycastle-fips-jsse,' +
            '!container-image/quarkus-standard-way,' +
            '!devtools,' +
            '!google-cloud-functions,' +
            '!google-cloud-functions-http,' +
            '!gradle,' +
            '!kubernetes-client,' +
            '!kubernetes/maven-invoker-way,' +
            '!maven,' +
            '!mongodb-rest-data-panache,' +
            '!liquibase-mongodb,' +
            '!mongodb-client,' +
            '!smallrye-opentracing'

    static final String QUARKUS_MODULES_SUBSET_TESTS = '' +
            'bouncycastle-jsse,' +
            'bouncycastle'

    static final String LINUX_PREPARE_MANDREL = '''
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
    export JAVA_HOME="$( pwd )/$( echo mandrel-java*-*/ )"
    export GRAALVM_HOME="${JAVA_HOME}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    if [[ ! -e "${JAVA_HOME}/bin/native-image" ]]; then
        echo "Cannot find native-image tool. Quitting..."
        exit 1
    fi
    native-image --version
    popd
    '''

    static final String LINUX_INTEGRATION_TESTS = LINUX_PREPARE_MANDREL + '''
    free -h
    df -h
    ps aux | grep java
    mvn --batch-mode clean verify -Ptestsuite -Dquarkus.version=${QUARKUS_VERSION}
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
    mvn --batch-mode clean verify -Ptestsuite -DexcludeTags=all -DincludeTags=perfcheck -Dtest=PerfCheckTest -Dquarkus.version=${QUARKUS_VERSION}
    '''

    static final String LINUX_INTEGRATION_TESTS_PERF_COMPARATOR = LINUX_PREPARE_MANDREL + '''
    free -h
    df -h
    ps aux | grep java
    git clone ${QUARKUS_REPO}
    pushd quarkus
    if [ ${QUARKUS_OPTION} == "A" ]; then
        git checkout ${QUARKUS_COMMIT_A}
    else
        git checkout ${QUARKUS_COMMIT_B}
    fi
    ./mvnw clean install -Dquickly
    popd
    export PERF_APP_RUNNER_DESCRIPTION=${NODE_NAME}
    export PERFCHECK_TEST_REQUESTS_MX_HEAP_MB=20480
    export PERFCHECK_TEST_HEAVY_REQUESTS=10
    export PERFCHECK_TEST_LIGHT_REQUESTS=500
    mvn --batch-mode clean verify -Ptestsuite -DexcludeTags=all -DincludeTags=perfcheck -Dtest=PerfCheckTest -Dquarkus.version=999-SNAPSHOT
    '''

    static final String LINUX_QUARKUS_TESTS = LINUX_PREPARE_MANDREL + '''
    free -h
    df -h
    ps aux | grep java
    git clone --depth 1 --branch ${QUARKUS_VERSION} ${QUARKUS_REPO}
    cd quarkus
    export MAVEN_OPTS="-Xmx5g -XX:MaxMetaspaceSize=3g"
    ./mvnw --batch-mode install -Dquickly
    ./mvnw verify -fae -f integration-tests/pom.xml -Dmaven.test.failure.ignore=true --batch-mode -Dno-format \\
        -DfailIfNoTests=false -Dnative -pl ${QUARKUS_MODULES} \\
        -Dquarkus.native.native-image-xmx=6g
    '''

    static final String LINUX_CONTAINER_INTEGRATION_TESTS = '''
    free -h
    df -h
    ps aux | grep java
    export CONTAINER_RUNTIME=podman
    source /etc/profile.d/jdks.sh
    set +e
       sudo ${CONTAINER_RUNTIME} stop $(sudo ${CONTAINER_RUNTIME} ps -a -q)
       sudo ${CONTAINER_RUNTIME} rm $(sudo ${CONTAINER_RUNTIME} ps -a -q)
       yes | sudo ${CONTAINER_RUNTIME} volume prune
       rm -rf /tmp/run-1000/libpod/tmp/pause.pid
       ${CONTAINER_RUNTIME} stop $(${CONTAINER_RUNTIME} ps -a -q)
       ${CONTAINER_RUNTIME} rm $(${CONTAINER_RUNTIME} ps -a -q)
       yes | ${CONTAINER_RUNTIME} volume prune
    set -e
    sudo ${CONTAINER_RUNTIME} pull ${BUILDER_IMAGE}
    if [ "$?" -ne 0 ]; then
        echo There was a problem pulling the image ${BUILDER_IMAGE}. We cannot proceed.
        exit 1
    fi
    export PATH="${JAVA_HOME}/bin:${PATH}"
    mvn --batch-mode clean verify -Ptestsuite-builder-image -Dquarkus.version=${QUARKUS_VERSION} \\
        -Dquarkus.native.container-runtime=${CONTAINER_RUNTIME} -Dquarkus.native.builder-image=${BUILDER_IMAGE}
    '''

    static final String LINUX_CONTAINER_QUARKUS_TESTS = '''
    free -h
    df -h
    ps aux | grep java
    git clone --depth 1 --branch ${QUARKUS_VERSION} ${QUARKUS_REPO}
    cd quarkus
    source /etc/profile.d/jdks.sh
    set +e
       sudo ${CONTAINER_RUNTIME} stop $(sudo ${CONTAINER_RUNTIME} ps -a -q)
       sudo ${CONTAINER_RUNTIME} rm $(sudo ${CONTAINER_RUNTIME} ps -a -q)
       yes | sudo ${CONTAINER_RUNTIME} volume prune
       rm -rf /tmp/run-1000/libpod/tmp/pause.pid
       ${CONTAINER_RUNTIME} stop $(${CONTAINER_RUNTIME} ps -a -q)
       ${CONTAINER_RUNTIME} rm $(${CONTAINER_RUNTIME} ps -a -q)
       yes | ${CONTAINER_RUNTIME} volume prune
    set -e
    sudo ${CONTAINER_RUNTIME} pull ${BUILDER_IMAGE}
    if [ "$?" -ne 0 ]; then
        echo There was a problem pulling the image ${BUILDER_IMAGE}. We cannot proceed.
        exit 1
    fi
    export PATH="${JAVA_HOME}/bin:${PATH}"
    export MAVEN_OPTS="-Xmx5g -XX:MaxMetaspaceSize=3g"
    ./mvnw --batch-mode install -Dquickly
    ./mvnw --batch-mode verify -f integration-tests/pom.xml --fail-at-end \\
        -pl ${QUARKUS_MODULES} -Dno-format -Ddocker -Dnative -Dnative.surefire.skip \\
        -Dquarkus.native.container-build=true \\
        -Dquarkus.native.builder-image="${BUILDER_IMAGE}" \\
        -Dquarkus.native.container-runtime=${CONTAINER_RUNTIME} \\
        -Dquarkus.native.native-image-xmx=6g
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
        set "JAVA_HOME=%GRAALVM_HOME%"
        set "PATH=%JAVA_HOME%\\bin;%PATH%"
        if not exist "%GRAALVM_HOME%\\bin\\native-image.cmd" (
            echo "Cannot find native-image tool. Quitting..."
            exit 1
        ) else (
            echo "native-image.cmd is present, good."
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
    set "MAVEN_OPTS=-Xmx5g -XX:MaxMetaspaceSize=3g"
    mvnw --batch-mode install -Dquickly & mvnw verify -f integration-tests/pom.xml --fail-at-end ^
        --batch-mode -Dno-format -DfailIfNoTests=false -Dnative -Dquarkus.native.native-image-xmx=6g -pl %QUARKUS_MODULES%
    '''
}
