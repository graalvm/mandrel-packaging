matrixJob('mandrel-windows-quarkus-tests') {
    axes {
        text('MANDREL_VERSION',
                '20.3',
                '21.0',
                'master'
        )
        text('QUARKUS_VERSION',
                '1.11.1.Final',
                'master'
        )
        labelExpression('LABEL', ['w2k19'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Windows :: Quarkus TS')
    logRotator {
        numToKeep(3)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(720)
        }
    }
    combinationFilter(' (MANDREL_VERSION=="20.3" && QUARKUS_VERSION=="1.11.1.Final") ||' +
            ' ((MANDREL_VERSION=="21.0" || MANDREL_VERSION=="master") && QUARKUS_VERSION=="master")')
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
    }
    steps {
        batchFile('''
REM Prepare Mandrel
@echo off
call vcvars64
IF NOT %ERRORLEVEL% == 0 ( exit 1 )

set BUILD_JOB=""
IF "%MANDREL_VERSION%"=="20.3" (
    set BUILD_JOB=mandrel-20.3-windows-build
) ELSE IF "%MANDREL_VERSION%"=="21.0" (
    set BUILD_JOB=mandrel-21.0-windows-build
) ELSE IF "%MANDREL_VERSION%"=="master" (
    set BUILD_JOB=mandrel-master-windows-build
) ELSE (
    echo "UNKNOWN Mandrel version: %MANDREL_VERSION%"
    exit 1
)

set downloadCommand= ^
$c = New-Object System.Net.WebClient; ^
$url = 'https://ci.modcluster.io/view/Mandrel/job/%BUILD_JOB%/lastSuccessfulBuild/artifact/*zip*/archive.zip'; $file = 'archive.zip'; ^
$c.DownloadFile($url, $file);
powershell -Command "%downloadCommand%"

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

REM Prepare Quarkus
git clone --depth 1 --branch %QUARKUS_VERSION% %QUARKUS_REPO%
cd quarkus

REM Build and test Quarkus

set "MODULES=-pl !google-cloud-functions,!google-cloud-functions-http,!kubernetes/maven-invoker-way,!kubernetes-client"

mvnw clean install -DskipTests -pl '!docs' & mvnw verify -f integration-tests/pom.xml --fail-at-end --batch-mode -DfailIfNoTests=false -Dnative %MODULES%

        ''')
    }
    publishers {
        archiveJunit('**/target/*-reports/*.xml') {
            allowEmptyResults(false)
            retainLongStdout(false)
            healthScaleFactor(1.0)
        }
        archiveArtifacts('**/target/*-reports/*.xml')

        extendedEmail {
            recipientList('karm@redhat.com')
            triggers {
                failure {
                    sendTo {
                        recipientList()
                        attachBuildLog(true)
                    }
                }
            }
        }
        wsCleanup()
    }
}
