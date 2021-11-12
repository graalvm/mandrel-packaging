matrixJob('mandrel-windows-quarkus-release-tests') {
    axes {
        text('JDK_VERSION',
                'jdk11',
                'jdk17'
        )
        text('MANDREL_VERSION',
                '21.3'
        )
        text('QUARKUS_VERSION',
                '2.2.3.Final',
                '2.4.1.Final'
        )
        labelExpression('LABEL', ['w2k19'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Windows :: Quarkus TS RELEASE')
    logRotator {
        numToKeep(30)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(720)
        }
    }
    combinationFilter(
            '!(JDK_VERSION.contains("17") && QUARKUS_VERSION.contains("2.2"))'
    )
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
        stringParam('MANDREL_21_3_BUILD_NUM', '3', 'Build number of the Final build: https://ci.modcluster.io/job/mandrel-21.3-windows-build-matrix/')
    }
    steps {
        batchFile('''
REM Prepare Mandrel
call vcvars64
IF NOT %ERRORLEVEL% == 0 ( exit 1 )

IF "%MANDREL_VERSION%"=="21.3" (
    set MANDREL_BUILD_NUM=%MANDREL_21_3_BUILD_NUM%
) ELSE (
  echo "UNKNOWN Mandrel version: %MANDREL_VERSION% Quitting..."
  exit 1
)

set downloadCommand= ^
$c = New-Object System.Net.WebClient; ^
$url = 'https://ci.modcluster.io/view/Mandrel/job/mandrel-%MANDREL_VERSION%-windows-build-matrix/JDK_VERSION=%JDK_VERSION%,LABEL=%LABEL%/%MANDREL_BUILD_NUM%/artifact/*zip*/archive.zip'; $file = 'archive.zip'; ^
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

REM Prepare Quarkus
git clone --depth 1 --branch %QUARKUS_VERSION% %QUARKUS_REPO%
cd quarkus

REM Build and test Quarkus

set "MODULES=-pl !bouncycastle-fips-jsse,!devtools,!google-cloud-functions,!google-cloud-functions-http,!kubernetes-client,!kubernetes/maven-invoker-way,!maven"

mvnw install -Dquickly & mvnw verify -f integration-tests/pom.xml --fail-at-end --batch-mode -DfailIfNoTests=false -Dnative %MODULES%

        ''')
    }
    publishers {
        groovyPostBuild('''
            if(manager.logContains(".*GRAALVM_HOME.*mandrel-java1.*-Final.*")){
                def build = Thread.currentThread()?.executable
                build.rootBuild.keepLog(true)
            }
        ''', Behavior.DoNothing)
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
        postBuildCleanup {
            cleaner {
                psCleaner {
                    killerType('org.jenkinsci.plugins.proccleaner.PsRecursiveKiller')
                }
            }
        }
    }
}
