matrixJob('mandrel-linux-container-integration-tests') {
    axes {
        text('BUILDER_IMAGE',
                'quay.io/quarkus/ubi-quarkus-mandrel:21.2-java11',
                'quay.io/quarkus/ubi-quarkus-mandrel:21.3-java11',
                'quay.io/quarkus/ubi-quarkus-mandrel:21.3-java17',
                'registry-proxy.engineering.redhat.com/rh-osbs/quarkus-mandrel-21-rhel8'
        )
        text('QUARKUS_VERSION',
                '2.2.3.Final',
                '2.4.1.Final',
                '2.5.0.CR1'
        )
        labelExpression('LABEL', ['el8'])
    }
    description('Run Mandrel container integration tests')
    displayName('Linux :: Container Integration tests')
    logRotator {
        numToKeep(30)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(120)
        }
    }
    combinationFilter(
            '!(BUILDER_IMAGE.contains("17") && QUARKUS_VERSION.contains("2.2"))'
    )
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
        shell('echo DESCRIPTION_STRING=${QUARKUS_VERSION},${BUILDER_IMAGE}')
        buildDescription(/DESCRIPTION_STRING=([^\s]*)/, '\\1')
        shell('''
            export CONTAINER_RUNTIME=podman
            source /etc/profile.d/jdks.sh
            set +e
            sudo ${CONTAINER_RUNTIME} stop $(sudo ${CONTAINER_RUNTIME} ps -a -q)
            sudo ${CONTAINER_RUNTIME} rm -f $(sudo ${CONTAINER_RUNTIME} ps -a -f "status=exited" -q)
            yes | sudo ${CONTAINER_RUNTIME} volume prune
            set -e
            sudo ${CONTAINER_RUNTIME} pull ${BUILDER_IMAGE}
            if [ "$?" -ne 0 ]; then
                echo There was a problem pulling the image ${BUILDER_IMAGE}. We cannot proceed.
                exit 1
            fi
            export JAVA_HOME="/usr/java/openjdk-11"
            export PATH="${JAVA_HOME}/bin:${PATH}"
            mvn clean verify -Ptestsuite-builder-image -Dquarkus.version=${QUARKUS_VERSION} -Dquarkus.native.container-runtime=${CONTAINER_RUNTIME} -Dquarkus.native.builder-image=${BUILDER_IMAGE}
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
        postBuildCleanup {
            cleaner {
                psCleaner {
                    killerType('org.jenkinsci.plugins.proccleaner.PsRecursiveKiller')
                }
            }
        }
    }
}
