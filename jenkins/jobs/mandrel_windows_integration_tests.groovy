matrixJob('mandrel-windows-integration-tests') {
    axes {
        text('MANDREL_VERSION',
                'graal-vm-20.3',
                '20.3',
                '21.2',
                '21.3',
                'master',
                'master-jdk17'
        )
        text('QUARKUS_VERSION',
                '1.11.7.Final',
                '2.1.3.Final',
                '2.2.3.Final'
        )
        labelExpression('LABEL', ['w2k19'])
    }
    description('Run Mandrel integration tests')
    displayName('Windows :: Integration tests')
    logRotator {
        numToKeep(5)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(120)
        }
    }
    combinationFilter(
            ' (MANDREL_VERSION.contains("20") && QUARKUS_VERSION.startsWith("1.")) ||' +
            ' (MANDREL_VERSION.contains("20") && QUARKUS_VERSION.startsWith("2.")) ||' +
            ' ((MANDREL_VERSION.contains("21") || MANDREL_VERSION.contains("master")) && (QUARKUS_VERSION=="main" || QUARKUS_VERSION.startsWith("2.")))')
    parameters {
        stringParam('MANDREL_INTEGRATION_TESTS_REPO', 'https://github.com/Karm/mandrel-integration-tests.git', 'Test suite repository.')
        choiceParam(
                'MANDREL_INTEGRATION_TESTS_REF_TYPE',
                ['heads', 'tags'],
                'Choose "heads" if you want to build from a branch, or "tags" if you want to build from a tag.'
        )
        stringParam('MANDREL_INTEGRATION_TESTS_REF', 'master', 'Branch or tag.')
    }
    scm {
        git {
            remote {
                url('${MANDREL_INTEGRATION_TESTS_REPO}')
            }
            branch('refs/${MANDREL_INTEGRATION_TESTS_REF_TYPE}/${MANDREL_INTEGRATION_TESTS_REF}')
        }
    }
    steps {
        batchFile('echo DESCRIPTION_STRING=Q:%QUARKUS_VERSION%,M:%MANDREL_VERSION%')
        buildDescription(/DESCRIPTION_STRING=([^\s]*)/, '\\1')
        batchFile('''
@echo off
call vcvars64
IF NOT %ERRORLEVEL% == 0 ( exit 1 )

set BUILD_JOB=""
IF "%MANDREL_VERSION%"=="20.3" (
    set BUILD_JOB=mandrel-20.3-windows-build
) ELSE IF "%MANDREL_VERSION%"=="graal-vm-20.3" (
    set BUILD_JOB=mandrel-graal-vm-20.3-windows-build
) ELSE IF "%MANDREL_VERSION%"=="21.2" (
    set BUILD_JOB=mandrel-21.2-windows-build
) ELSE IF "%MANDREL_VERSION%"=="21.3" (
    set BUILD_JOB=mandrel-21.3-windows-build
) ELSE IF "%MANDREL_VERSION%"=="master" (
    set BUILD_JOB=mandrel-master-windows-build
) ELSE IF "%MANDREL_VERSION%"=="master-jdk17" (
    set BUILD_JOB=mandrel-master-jdk17-windows-build
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

mvn clean verify -Ptestsuite -Dquarkus.version=%QUARKUS_VERSION%
                 ''')
    }
    publishers {
        archiveJunit('**/target/*-reports/*.xml') {
            allowEmptyResults(false)
            retainLongStdout(false)
            healthScaleFactor(1.0)
        }
        archiveArtifacts('**/target/*-reports/*.xml,**/target/archived-logs/**')

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
