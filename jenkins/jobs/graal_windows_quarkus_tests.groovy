matrixJob('graal-windows-quarkus-tests') {
    axes {
        text('GRAAL_VERSION',
                '20.3.1',
        )
        text('QUARKUS_VERSION',
                '1.11.6.Final',
                'master'
        )
        labelExpression('LABEL', ['w2k19'])
    }
    description('Run Quarkus TS with GraalVM distros.')
    displayName('Windows (Graal) :: Quarkus TS')
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
    combinationFilter('(GRAAL_VERSION=="20.3.1" && QUARKUS_VERSION=="master")')
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
    }
    steps {
        batchFile('''
REM Prepare Mandrel
@echo off
call vcvars64
IF NOT %ERRORLEVEL% == 0 ( exit 1 )

set "GRAALVM_HOME=C:\\Program Files\\graalvm-ce-java11-%GRAAL_VERSION%"
set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%JAVA_HOME%\\bin;%PATH%"

if not exist "%GRAALVM_HOME%\\bin\\native-image.cmd" (
  echo "Cannot find native-image tool. Quitting..."
  exit 1
) else (
  echo "native-image.cmd is present, good."
  cmd /C native-image --version
)

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
