class Constants {
    static final ArrayList<String> REPOSITORY =
            [
                    'https://github.com/graalvm/mandrel.git',
                    'https://github.com/jerboaa/graal.git',
                    'https://github.com/Karm/graal.git',
                    'https://github.com/zakkak/mandrel.git'
            ]
    static final ArrayList<String> REPOSITORY_M23_1_JDK21 =
            [
                    'https://github.com/graalvm/graalvm-community-jdk21u.git',
                    'https://github.com/jerboaa/graalvm-for-jdk21-community-backports.git',
                    'https://github.com/zakkak/graalvm-community-jdk21u.git',
                    'https://github.com/Karm/graalvm-community-jdk21u.git'
            ]
    static final ArrayList<String> PACKAGING_REPOSITORY =
            [
                    'https://github.com/mandrel/mandrel-packaging.git',
                    'https://github.com/Karm/mandrel-packaging.git',
                    'https://github.com/zakkak/mandrel-packaging.git'
            ]

    static final String LINUX_BUILD_CMD = '''
    if [[ "${LABEL}" == *aarch64* ]]; then
        export JDK_ARCH=aarch64
    else
        export JDK_ARCH=x64
    fi
    if [[ "${JDK_RELEASE_NAME}" == latest ]]; then
        curl -OJLs https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/${JDK_RELEASE}/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse
        curl -OJLs https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/${JDK_RELEASE}/linux/${JDK_ARCH}/staticlibs/hotspot/normal/eclipse
    else 
        curl -OJLs https://api.adoptium.net/v3/binary/version/${JDK_RELEASE_NAME}/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse
        curl -OJLs https://api.adoptium.net/v3/binary/version/${JDK_RELEASE_NAME}/linux/${JDK_ARCH}/staticlibs/hotspot/normal/eclipse
    fi
    if ! ls OpenJDK*-jdk* >/dev/null 2>&1; then
        if [[ "${INHOUSE_JDK_FALLBACK}" == "true" ]]; then
            export MANDREL_JDK_URL="https://ci.modcluster.io/job/mandrel-jdk-build-matrix/lastSuccessfulBuild/LABEL=${LABEL}/artifact/graal-builder-jdk.tar.xz"
            curl -OJLs "${MANDREL_JDK_URL}"
            tar -xf graal-builder-jdk.tar.xz
            export JAVA_HOME=$( pwd )/graal-builder-jdk
        else
            echo "Adoptium download failed and INHOUSE_JDK_FALLBACK is not enabled. Quitting..."
            exit 1
        fi
    else
        tar -xf OpenJDK*-jdk*
        export JAVA_HOME=$( pwd )/$( echo jdk-${JDK_VERSION}* )
        tar -xf OpenJDK*-static-libs* --strip-components=1 -C ${JAVA_HOME}
    fi
    if [[ ! -e "${JAVA_HOME}/bin/java" ]]; then
        echo "Cannot find downloaded JDK. Quitting..."
        exit 1
    fi
    tar -xf OpenJDK*-static-libs* --strip-components=1 -C ${JAVA_HOME}
    pushd mandrel
        echo MANDREL_DESCRIBE="$(git describe --always --long)"
    popd
    sed -i "s~export JAVA_HOME=/usr/java/.*~export JAVA_HOME=${JAVA_HOME}~g" ./jenkins/jobs/scripts/mandrel_linux_build.sh
    ./jenkins/jobs/scripts/mandrel_linux_build.sh
    '''

    static final String MACOS_BUILD_CMD = '''
    export JDK_ARCH=aarch64
    export OS=mac
    if [[ "${JDK_RELEASE_NAME}" == latest ]]; then
        curl -OJLs https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/${JDK_RELEASE}/${OS}/${JDK_ARCH}/jdk/hotspot/normal/eclipse
        curl -OJLs https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/${JDK_RELEASE}/${OS}/${JDK_ARCH}/staticlibs/hotspot/normal/eclipse
    else
        curl -OJLs https://api.adoptium.net/v3/binary/version/${JDK_RELEASE_NAME}/${OS}/${JDK_ARCH}/jdk/hotspot/normal/eclipse
        curl -OJLs https://api.adoptium.net/v3/binary/version/${JDK_RELEASE_NAME}/${OS}/${JDK_ARCH}/staticlibs/hotspot/normal/eclipse
    fi
    tar -xf OpenJDK*-jdk*
    export JAVA_HOME=$( pwd )/$( echo jdk-${JDK_VERSION}* )/Contents/Home/
    if [[ ! -e "${JAVA_HOME}/bin/java" ]]; then
        echo "Cannot find downloaded JDK. Quitting..."
        exit 1
    fi
    tar -xf OpenJDK*-static-libs* --strip-components=3 -C ${JAVA_HOME}
    pushd mandrel
        echo MANDREL_DESCRIBE="$(git describe --always --long)"
    popd
    export PATH=/opt/homebrew/opt/python/libexec/bin:${PATH}
    echo ${PATH}
    sed -i '' "s~export JAVA_HOME=/usr/java/.*~export JAVA_HOME=${JAVA_HOME}~g" ./jenkins/jobs/scripts/mandrel_linux_build.sh
    sed -i '' "s~export MANDREL_HOME=.*~export MANDREL_HOME=\\"\\$( find . -name 'mandrel-*' -type d )/Contents/Home/\\"~g" ./jenkins/jobs/scripts/mandrel_linux_build.sh
    sed -i '' "s~sha1sum~shasum -a1~g" ./jenkins/jobs/scripts/mandrel_linux_build.sh
    sed -i '' "s~sha256sum~shasum -a256~g" ./jenkins/jobs/scripts/mandrel_linux_build.sh
    # https://github.com/adoptium/temurin-build/pull/3827
    if [[ -d "${JAVA_HOME}/lib/static/darwin-arm64" ]]; then
        mv ${JAVA_HOME}/lib/static/darwin-arm64 ${JAVA_HOME}/lib/static/darwin-aarch64
    fi
    ./jenkins/jobs/scripts/mandrel_linux_build.sh
    # We align to what GraalVM CE calls its releases
    # this is no longer needed as of mandrel-packaging 24.2, but we keep it for backwards compatibility:
    for m in mandrel-*.tar.gz*;do n=$(echo $m | sed 's/darwin-aarch64/macos-aarch64/g'); mv $m $n;done
    find .
    '''

    static final String WINDOWS_BUILD_CMD = '''
    set JAVA_HOME=%WORKSPACE%\\JDK
    powershell -Command "Remove-Item -ErrorAction Ignore -Recurse \\"$Env:JAVA_HOME\\";"
    set downloadCmd=^
    $wc = New-Object System.Net.WebClient;^
    if ($Env:JDK_RELEASE_NAME -eq 'latest') {^
        $wc.DownloadFile(\\"https://api.adoptium.net/v3/binary/latest/$Env:JDK_VERSION/$Env:JDK_RELEASE/windows/x64/jdk/hotspot/normal/eclipse\\", \\"$Env:temp\\jdk.zip\\");^
        Expand-Archive \\"$Env:temp\\jdk.zip\\" -DestinationPath \\"$Env:temp\\";^
        Move-Item -Path \\"$Env:temp\\jdk-*\\" -Destination $Env:JAVA_HOME;^
        $wc.DownloadFile(\\"https://api.adoptium.net/v3/binary/latest/$Env:JDK_VERSION/$Env:JDK_RELEASE/windows/x64/staticlibs/hotspot/normal/eclipse\\", \\"$Env:temp\\jdk-staticlibs.zip\\");^
        Expand-Archive \\"$Env:temp\\jdk-staticlibs.zip\\" -DestinationPath \\"$Env:temp\\";^
        Move-Item -Path \\"$Env:temp\\jdk-*\\lib\\static\\" -Destination $Env:JAVA_HOME\\lib\\;^
        Remove-Item -Recurse \\"$Env:temp\\jdk-*\\";^
    } else {^
        $wc.DownloadFile(\\"https://api.adoptium.net/v3/binary/version/$Env:JDK_RELEASE_NAME/windows/x64/jdk/hotspot/normal/eclipse\\", \\"$Env:temp\\jdk.zip\\");^
        Expand-Archive \\"$Env:temp\\jdk.zip\\" -DestinationPath \\"$Env:temp\\";^
        Move-Item -Path \\"$Env:temp\\jdk-*\\" -Destination $Env:JAVA_HOME;^
        $wc.DownloadFile(\\"https://api.adoptium.net/v3/binary/version/$Env:JDK_RELEASE_NAME/windows/x64/staticlibs/hotspot/normal/eclipse\\", \\"$Env:temp\\jdk-staticlibs.zip\\");^
        Expand-Archive \\"$Env:temp\\jdk-staticlibs.zip\\" -DestinationPath \\"$Env:temp\\";^
        Move-Item -Path \\"$Env:temp\\jdk-*\\lib\\static\\" -Destination $Env:JAVA_HOME\\lib\\;^
        Remove-Item -Recurse \\"$Env:temp\\jdk-*\\";^
    }
    powershell -Command "%downloadCmd%"
    echo JAVA_HOME is %JAVA_HOME%
    if not exist "%JAVA_HOME%\\bin\\java.exe" (
        echo "Cannot find downloaded JDK. Quitting..."
    )
    set "PATH=C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\IDE;C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\VC\\Auxiliary\\Build;C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools;%PATH%"
    pushd mandrel
        for /F "tokens=*" %%i in (\'"git describe --always --long"\') do set M_DESCRIBE=%%i
        powershell -Command "$c=(Select-String -Path \'%JAVA_HOME%\\release\' -Pattern \'^^SOURCE\').Line -replace \'SOURCE=.*:([a-z0-9]*).*\', \'$1\';Write-Host MANDREL_DESCRIBE=%M_DESCRIBE% JDK git: $c"
    popd
    SET PYTHONIOENCODING=utf-8
    SET PYTHONUTF8=1
    powershell -Command "$c=Get-Content \'jenkins\\jobs\\scripts\\mandrel_windows_build.bat\';$c -replace \'.*JAVA_HOME=C.*\',\'set \\"JAVA_HOME=%JAVA_HOME%"\' | Out-File -Encoding ASCII -FilePath \'jenkins\\jobs\\scripts\\mandrel_windows_build.bat\';"
    jenkins\\jobs\\scripts\\mandrel_windows_build.bat
    '''

    static final String MACOS_JDK_BUILD_CMD = '''#!/bin/bash
    set +e
    set -x
    export JDK_ARCH=aarch64
    
    # Boot JDK
    export JAVA_HOME=$WORKSPACE/JDK-boot/Contents/Home
    rm -rf JDK-boot
    curl -OJLs "https://api.adoptium.net/v3/binary/latest/${BOOT_JDK_VERSION}/${BOOT_JDK_RELEASE}/mac/${JDK_ARCH}/jdk/hotspot/normal/${BOOT_JDK_PROVIDER}"
    curl -OJLs "https://api.adoptium.net/v3/binary/latest/${BOOT_JDK_VERSION}/${BOOT_JDK_RELEASE}/mac/${JDK_ARCH}/staticlibs/hotspot/normal/${BOOT_JDK_PROVIDER}"
    tar -xf OpenJDK*-jdk*
    export JAVA_HOME=$(pwd)/$(ls -d jdk-${JDK_VERSION}*)/Contents/Home
    tar -xf OpenJDK*-static-libs* --strip-components=3 -C "${JAVA_HOME}"
    if [[ ! -e "${JAVA_HOME}/bin/java" ]]; then
        echo "Cannot find downloaded JDK. Quitting..."
        exit 1
    fi
    export PATH="${JAVA_HOME}/bin:${PATH}"

    # JTreg
    rm -rf jtreg
    git clone ${JTREG_REPO} jtreg
    pushd jtreg
    git checkout ${JTREG_TAG}
    bash make/build.sh --jdk $JAVA_HOME
    export JTREG_HOME=$(pwd)/build/images/jtreg
    export PATH=$JTREG_HOME/bin:$PATH
    popd

    # JDK repo
    rm -rf jdk
    git clone ${JDK_REPO} jdk
    pushd jdk
    git checkout ${JDK_BRANCH}

    # Configure and build JDK
    JOBS=$(sysctl -n hw.ncpu)
    bash ./configure \\
        --enable-hsdis-bundling \\
        --with-hsdis=capstone \\
        --with-capstone=/opt/homebrew \\
        --with-zlib=bundled \\
        --with-libjpeg=bundled \\
        --with-libpng=bundled \\
        --with-lcms=bundled \\
        --with-giflib=bundled \\
        --with-stdc++lib=static \\
        --disable-warnings-as-errors \\
        --with-boot-jdk=$JAVA_HOME \\
        --enable-unlimited-crypto \\
        --with-debug-level=release \\
        --with-version-feature=${JDK_FEATURE_VERSION} \\
        --with-version-interim=0 \\
        --with-version-update=${JDK_UPDATE_VERSION}

    make all graal-builder-image JOBS=$JOBS

    # Archive graal-builder-image as tar.xz
    # For plain JDK, should archive the jdk image instead
    pushd build/macosx-${JDK_ARCH}-server-release/images
    tar -cJf graal-builder-jdk.tar.xz graal-builder-jdk
    mv graal-builder-jdk.tar.xz $WORKSPACE/
    popd

    # Run JTreg tests
    # For plain JDK, should run jtreg on images/jdk instead
    rm -rf jtreg_results
    mkdir -p $WORKSPACE/jtreg_results/jdk/JTwork $WORKSPACE/jtreg_results/jdk/JTreport
    jtreg -a -ignore:quiet -w:jtreg_results/jdk/JTwork -r:jtreg_results/jdk/JTreport \\
        -jdk:build/macosx-${JDK_ARCH}-server-release/images/graal-builder-jdk ${JTREG_TEST_DIR}
    echo Done
    exit 0
    '''

    static final String LINUX_JDK_BUILD_CMD = '''#!/bin/bash
    set +e
    set -x
    ARCH=$(uname -m)
    if [ "$ARCH" = "x86_64" ]; then
        export JDK_ARCH=x86_64
    elif [ "$ARCH" = "aarch64" ]; then
        export JDK_ARCH=aarch64
    else
        echo "Unsupported architecture: $ARCH"
        exit 1
    fi
    
    # Boot JDK
    export JAVA_HOME=$WORKSPACE/JDK-boot
    rm -rf $JAVA_HOME
    curl -OJLs "https://api.adoptium.net/v3/binary/latest/${BOOT_JDK_VERSION}/${BOOT_JDK_RELEASE}/linux/${JDK_ARCH}/jdk/hotspot/normal/${BOOT_JDK_PROVIDER}"
    curl -OJLs "https://api.adoptium.net/v3/binary/latest/${BOOT_JDK_VERSION}/${BOOT_JDK_RELEASE}/linux/${JDK_ARCH}/staticlibs/hotspot/normal/${BOOT_JDK_PROVIDER}"
    tar -xf OpenJDK*-jdk* && mv jdk-* $JAVA_HOME
    tar -xf OpenJDK*-static-libs* --strip-components=3 -C $JAVA_HOME
    rm -rf OpenJDK*
    export PATH=$JAVA_HOME/bin:$PATH
   
    # Clone and build JTreg
    rm -rf jtreg
    git clone ${JTREG_REPO} jtreg
    pushd jtreg
    git checkout ${JTREG_TAG}
    bash make/build.sh --jdk $JAVA_HOME
    export JTREG_HOME=$(pwd)/build/images/jtreg
    export PATH=$JTREG_HOME/bin:$PATH
    popd

    # Clone JDK repo
    rm -rf jdk
    git clone ${JDK_REPO} jdk
    pushd jdk
    git checkout ${JDK_BRANCH}

    # Configure and build JDK
    JOBS=$(nproc)
    scl enable gcc-toolset-10 -- bash -c "bash ./configure \\
        --enable-hsdis-bundling \\
        --with-hsdis=capstone \\
        --with-zlib=bundled \\
        --with-libjpeg=bundled \\
        --with-libpng=bundled \\
        --with-lcms=bundled \\
        --with-giflib=bundled \\
        --with-stdc++lib=static \\
        --disable-warnings-as-errors \\
        --with-boot-jdk=$JAVA_HOME \\
        --enable-unlimited-crypto \\
        --with-debug-level=release \\
        --with-version-feature=${JDK_FEATURE_VERSION} \\
        --with-version-interim=0 \\
        --with-version-update=${JDK_UPDATE_VERSION}"

    scl enable gcc-toolset-10 -- bash -c "make all graal-builder-image JOBS=$JOBS"

    # Archive graal-builder-image as tar.xz
    pushd build/linux-${JDK_ARCH}-server-release/images
    tar -cJf graal-builder-jdk.tar.xz graal-builder-jdk
    mv graal-builder-jdk.tar.xz $WORKSPACE/
    popd

    # Run JTreg tests
    rm -rf jtreg_results
    mkdir -p $WORKSPACE/jtreg_results/jdk/JTwork $WORKSPACE/jtreg_results/jdk/JTreport
    jtreg -a -ignore:quiet -w:jtreg_results/jdk/JTwork -r:jtreg_results/jdk/JTreport \\
        -jdk:build/linux-${JDK_ARCH}-server-release/images/graal-builder-jdk ${JTREG_TEST_DIR}
    echo Done
    exit 0
    '''

    static final String WINDOWS_JDK_BUILD_CMD = '''
    @echo on
    setlocal EnableDelayedExpansion
    set "PATH=C:\\cygwin64;C:\\cygwin64\\bin;%PATH%"
    set "JDK_ARCH=x64"
    set "JAVA_HOME=%WORKSPACE%\\JDK-boot"
    if exist "%JAVA_HOME%" (
        rmdir /s /q "%JAVA_HOME%"
    )
    set downloadCmd=^
    $wc = New-Object System.Net.WebClient;^
    $wc.DownloadFile('https://api.adoptium.net/v3/binary/latest/%BOOT_JDK_VERSION%/%BOOT_JDK_RELEASE%/windows/%JDK_ARCH%/jdk/hotspot/normal/%BOOT_JDK_PROVIDER%', '%TEMP%\\jdk.zip');^
    Expand-Archive '%TEMP%\\jdk.zip' -DestinationPath '%TEMP%';^
    Move-Item -Path '%TEMP%\\jdk-*' -Destination '%JAVA_HOME%';^
    $wc.DownloadFile('https://api.adoptium.net/v3/binary/latest/%BOOT_JDK_VERSION%/%BOOT_JDK_RELEASE%/windows/%JDK_ARCH%/staticlibs/hotspot/normal/%BOOT_JDK_PROVIDER%', '%TEMP%\\jdk-staticlibs.zip');^
    Expand-Archive '%TEMP%\\jdk-staticlibs.zip' -DestinationPath '%TEMP%';^
    Move-Item -Path '%TEMP%\\jdk-*\\lib\\static' -Destination '%JAVA_HOME%\\lib';^
    Remove-Item -Recurse '%TEMP%\\jdk-*';
    powershell -Command "%downloadCmd%"
    if not exist "%JAVA_HOME%\\bin\\java.exe" (
        echo Cannot find Java at %JAVA_HOME%\\bin\\java.exe. Quitting...
        exit /b 1
    )
    set "PATH=%JAVA_HOME%\\bin;%PATH%"
    if exist "jtreg" (
        rmdir /s /q jtreg
    )
    git clone %JTREG_REPO% jtreg
    pushd jtreg
    git checkout %JTREG_TAG%
    bash -c "find . -type f \\( -name '*.sh' -o -name '*.bash' -o -path './make/build-support/*' \\) -exec dos2unix {} \\;"
    bash make/build.sh --jdk "%JAVA_HOME%"
    if not exist "build\\images\\jtreg\\bin\\jtreg" (
        echo Failed to build JTreg. Quitting...
        exit /b 1
    )
    set "JTREG_HOME=%CD%\\build\\images\\jtreg"
    set "PATH=%JTREG_HOME%\\bin;%PATH%"
    popd
    if exist "jdk" (
        rmdir /s /q jdk
    )
    git clone %JDK_REPO% jdk
    pushd jdk
    git checkout %JDK_BRANCH%
    set "VS_PATH=C:\\Program Files\\Microsoft Visual Studio\\2022\\Community"
    set "VS_TOOLS=%VS_PATH%\\Common7\\IDE;%VS_PATH%\\VC\\Auxiliary\\Build;%VS_PATH%\\Common7\\Tools"
    set "PATH=%VS_TOOLS%;%PATH%"
    call vcvars64.bat
    if %ERRORLEVEL% neq 0 (
        echo Failed to initialize Visual Studio.
        exit /b 1
    )
    REM dancing around the parallelism, wracks havoc on Windows VM I/O though
    for /f "tokens=*" %%i in ('powershell -Command "[System.Environment]::ProcessorCount"') do set JOBS=%%i
    set /a JOBS=JOBS/2
    if !JOBS! LSS 1 set JOBS=1
    bash -c "dos2unix configure"
    bash configure ^
        --with-zlib=bundled ^
        --with-libjpeg=bundled ^
        --with-libpng=bundled ^
        --with-lcms=bundled ^
        --with-giflib=bundled ^
        --with-stdc++lib=static ^
        --disable-warnings-as-errors ^
        --with-boot-jdk="%JAVA_HOME%" ^
        --enable-unlimited-crypto ^
        --with-debug-level=release ^
        --with-version-feature=%JDK_FEATURE_VERSION% ^
        --with-version-interim=0 ^
        --with-version-update=%JDK_UPDATE_VERSION%
    if %ERRORLEVEL% neq 0 (
        echo Configure failed. Quitting...
        exit /b 1
    )
    bash -c "make all graal-builder-image JOBS=%JOBS%"
    if %ERRORLEVEL% neq 0 (
        echo Make failed.
        exit /b 1
    )
    pushd build\\windows-x86_64-server-release\\images
    7z.exe a -tzip graal-builder-jdk.zip graal-builder-jdk
    move graal-builder-jdk.zip %WORKSPACE%\\
    popd
    if exist "jtreg_results" (
        rmdir /s /q jtreg_results
    )
    mkdir jtreg_results\\jdk\\JTwork jtreg_results\\jdk\\JTreport
    dir %JTREG_TEST_DIR%
    bash -c "jtreg -a -ignore:quiet -w:jtreg_results/jdk/JTwork -r:jtreg_results/jdk/JTreport -jdk:build/windows-x86_64-server-release/images/graal-builder-jdk %JTREG_TEST_DIR%"
    echo Done
    exit /b 0
    '''
}
