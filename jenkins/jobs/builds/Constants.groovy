class Constants {
    static final ArrayList<String> REPOSITORY =
            [
                    'https://github.com/graalvm/mandrel.git',
                    'https://github.com/jerboaa/graal.git',
                    'https://github.com/Karm/graal.git',
                    'https://github.com/zakkak/mandrel.git'
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
    curl -OJLs https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/${JDK_RELEASE}/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse
    curl -OJLs https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/${JDK_RELEASE}/linux/${JDK_ARCH}/staticlibs/hotspot/normal/eclipse
    tar -xf OpenJDK${JDK_VERSION}U-jdk*
    export JAVA_HOME=$( pwd )/$( echo jdk-${JDK_VERSION}* )
    if [[ ! -e "${JAVA_HOME}/bin/java" ]]; then
        echo "Cannot find downloaded JDK. Quitting..."
        exit 1
    fi
    tar -xf OpenJDK${JDK_VERSION}U-static-libs* --strip-components=1 -C ${JAVA_HOME}
    pushd mandrel
        echo MANDREL_DESCRIBE="$(git describe --always --long)"
    popd
    sed -i "s~export JAVA_HOME=/usr/java/.*~export JAVA_HOME=${JAVA_HOME}~g" ./jenkins/jobs/scripts/mandrel_linux_build.sh
    ./jenkins/jobs/scripts/mandrel_linux_build.sh
    '''

    static final String LINUX_GRAAL_VM_BRANCH_BUILD_CMD = '''
    pushd mandrel
        git config --global user.email "karm@redhat.com"
        git config --global user.name "Karm"
        git remote add upstream ${GRAALVM_REPO}
        git fetch upstream ${GRAALVM_BRANCH}
        git config --global merge.ours.driver true
        echo -e \'\\n**/suite.py merge=ours\\n\' >> .gitattributes
        git add .gitattributes
        git commit -m x
        git merge -s recursive -Xdiff-algorithm=patience --no-edit upstream/${GRAALVM_BRANCH}
        echo MANDREL_DESCRIBE="$(git describe --always --long)"
    popd
    
    ''' + LINUX_BUILD_CMD

    static final String WINDOWS_BUILD_CMD = '''
    setx MX_PYTHON C:\\Python39\\python.exe
    set MX_PYTHON=C:\\Python39\\python.exe
    REM https://github.com/graalvm/mandrel-packaging/pull/210#issuecomment-1003631204
    mklink C:\\Python39\\python3.exe C:\\Python39\\python.exe
    set downloadCmd=^
    $wc = New-Object System.Net.WebClient;^
    $urls = @(;^
        \\"https://api.adoptium.net/v3/binary/latest/%JDK_VERSION%/%JDK_RELEASE%/windows/x64/jdk/hotspot/normal/eclipse\\";^
        \\"https://api.adoptium.net/v3/binary/latest/%JDK_VERSION%/%JDK_RELEASE%/windows/x64/staticlibs/hotspot/normal/eclipse\\";^
    );^
    foreach($url in $urls) {;^
        $ProgressPreference = 'SilentlyContinue';^
        $headers = Invoke-WebRequest -Uri $url -Method HEAD;^
        $fileName = [System.Net.Mime.ContentDisposition]::new($headers.Headers[\\"Content-Disposition\\"]).FileName;^
        $wc.DownloadFile($url, $fileName);^
        Expand-Archive $fileName -DestinationPath '.';^
    };^
    $src = Get-Item .\\jdk-*-static-libs\\lib\\static;^
    $dst = Get-Item .\\jdk-*\\lib\\ -Exclude '*static*';^
    Copy-Item -Path $src -Destination $dst -Recurse;

    powershell -Command "%downloadCmd%"
    for /f "tokens=5" %%g in ('dir .\\jdk-* ^| findstr /R jdk-.* ^| findstr /V static') do set JAVA_HOME=%WORKSPACE:/=\\%\\%%g
    echo JAVA_HOME is %JAVA_HOME%
    if not exist "%JAVA_HOME%\\bin\\java.exe" (
        echo "Cannot find downloaded JDK. Quitting..."
        exit 1
    )
    pushd mandrel
        for /F "tokens=*" %%i in (\'"git describe --always --long"\') do set M_DESCRIBE=%%i
        powershell -Command "$c=(Select-String -Path \'%JAVA_HOME%\\release\' -Pattern \'^^SOURCE\').Line -replace \'SOURCE=.*:([a-z0-9]*).*\', \'$1\';Write-Host MANDREL_DESCRIBE=%M_DESCRIBE% JDK git: $c"
    popd
    powershell -Command "$c=Get-Content \'jenkins\\jobs\\scripts\\mandrel_windows_build.bat\';$c -replace \'.*JAVA_HOME=C.*\',\'set \\"JAVA_HOME=%JAVA_HOME%"\' | Out-File -Encoding ASCII -FilePath \'jenkins\\jobs\\scripts\\mandrel_windows_build.bat\';"
    jenkins\\jobs\\scripts\\mandrel_windows_build.bat
    '''

    static final String WINDOWS_GRAAL_VM_BRANCH_BUILD_CMD = '''
    pushd mandrel
        git remote add upstream %GRAALVM_REPO%
        git fetch upstream %GRAALVM_BRANCH%
        git config --global merge.ours.driver true
        @echo off
        echo(>>.gitattributes
        echo **/suite.py merge=ours>>.gitattributes
        @echo on
        type .gitattributes
        git add .gitattributes
        git commit -m x
        git merge -s recursive -Xdiff-algorithm=patience --no-edit upstream/%GRAALVM_BRANCH%
    popd
    
    ''' + WINDOWS_BUILD_CMD
}
