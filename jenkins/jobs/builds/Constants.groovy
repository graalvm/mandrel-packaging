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
    setx MX_PYTHON C:\\Python310\\python.exe
    set MX_PYTHON=C:\\Python310\\python.exe
    set JAVA_HOME=%WORKSPACE%\\JDK
    powershell -Command "Remove-Item -ErrorAction Ignore -Recurse \\"$Env:JAVA_HOME\\";"
    set downloadCmd=^
        $wc = New-Object System.Net.WebClient;^
        $wc.DownloadFile(\\"https://api.adoptium.net/v3/binary/latest/$Env:JDK_VERSION/$Env:JDK_RELEASE/windows/x64/jdk/hotspot/normal/eclipse\\", \\"$Env:temp\\jdk.zip\\");^
        Expand-Archive \\"$Env:temp\\jdk.zip\\" -DestinationPath \\"$Env:temp\\";^
        Move-Item -Path \\"$Env:temp\\jdk-*\\" -Destination $Env:JAVA_HOME;^
        $wc.DownloadFile(\\"https://api.adoptium.net/v3/binary/latest/$Env:JDK_VERSION/$Env:JDK_RELEASE/windows/x64/staticlibs/hotspot/normal/eclipse\\", \\"$Env:temp\\jdk-staticlibs.zip\\");^
        Expand-Archive \\"$Env:temp\\jdk-staticlibs.zip\\" -DestinationPath \\"$Env:temp\\";^
        Move-Item -Path \\"$Env:temp\\jdk-*\\lib\\static\\" -Destination $Env:JAVA_HOME\\lib\\;^
        Remove-Item -Recurse \\"$Env:temp\\jdk-*\\";
    powershell -Command "%downloadCmd%"
    echo JAVA_HOME is %JAVA_HOME%
    if not exist "%JAVA_HOME%\\bin\\java.exe" (
        echo "Cannot find downloaded JDK. Quitting..."
    )
    if NOT "%BRANCH_OR_TAG%"=="%BRANCH_OR_TAG:23=%" (
        echo "USE VS 2022"
        set "PATH=C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\IDE;C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\VC\\Auxiliary\\Build;C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools;%PATH%"
    )
    if NOT "%BRANCH_OR_TAG%"=="%BRANCH_OR_TAG:master=%" (
        echo "USE VS 2022"
        set "PATH=C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\IDE;C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\VC\\Auxiliary\\Build;C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\Common7\\Tools;%PATH%"
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
