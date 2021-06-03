job('mandrel-linux-quarkus-container-tests') {
    label('el8')
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Linux :: Quarkus Builder image TS')
    logRotator {
        numToKeep(3)
    }
    wrappers {
        timestamps()
        timeout {
            absolute(600)
        }
    }
    parameters {
        stringParam('CONTAINER_IMAGE', 'quay.io/quarkus/ubi-quarkus-mandrel:20.3-java11', 'Mandrel builder image.')
        stringParam('CONTAINER_RUNTIME', 'podman', 'Command used, either "docker" or "podman". Note that podman is not installed on all executors...')
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
        stringParam('QUARKUS_VERSION', '1.11.6.Final', 'Quarkus version branch or tag.')
        choiceParam(
                'OPENJDK',
                [
                        'openjdk-11.0.12_2',
                        'openjdk-11.0.11_9',
                        'openjdk-11-ea',
                        'openjdk-11'

                ],
                'OpenJDK installation.'
        )
    }
    steps {
        shell('echo DESCRIPTION_STRING=Quarkus${QUARKUS_VERSION},${CONTAINER_IMAGE}')
        buildDescription(/DESCRIPTION_STRING=([^\s]*)/, '\\1')
        shell('''
            # Prepare Quarkus
            git clone --depth 1 --branch ${QUARKUS_VERSION} ${QUARKUS_REPO}
            cd quarkus

            # Env
            export JAVA_HOME=/usr/java/${OPENJDK}
            export PATH=${JAVA_HOME}/bin:${PATH}
            set +e
            ${CONTAINER_RUNTIME} stop $(${CONTAINER_RUNTIME} ps -a -q)
            ${CONTAINER_RUNTIME} rm $(${CONTAINER_RUNTIME} ps -a -q)
            yes | ${CONTAINER_RUNTIME} volume prune
            set -e
            ${CONTAINER_RUNTIME} pull ${CONTAINER_IMAGE}
            if [ "$?" -ne 0 ]; then
                echo There was a problem pulling the image ${CONTAINER_IMAGE}. We cannot proceed.
                exit 1
            fi
            
            # Build Quarkus
            ./mvnw install -Dquickly
            
            # Test Quarkus
            export MODULES="-pl \\
!google-cloud-functions,\\
!devtools,\\
!bouncycastle-fips-jsse,\\
!maven"
            
            ./mvnw verify -f integration-tests/pom.xml --fail-at-end \\
   ${MODULES} -Dno-format -Ddocker -Dnative -Dnative.surefire.skip \\
  -Dquarkus.native.container-build=true \\
  -Dquarkus.native.builder-image="${CONTAINER_IMAGE}" \\
  -Dquarkus.native.container-runtime=${CONTAINER_RUNTIME}
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
