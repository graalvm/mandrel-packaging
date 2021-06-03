matrixJob('mandrel-linux-container-integration-tests') {
    axes {
        text('BUILDER_IMAGE',
                'quay.io/quarkus/ubi-quarkus-mandrel:20.3-java11',
                'quay.io/quarkus/ubi-quarkus-mandrel:21.1-java11',
                'registry.access.redhat.com/quarkus/mandrel-20-rhel8',
                'registry-proxy.engineering.redhat.com/rh-osbs/quarkus-quarkus-mandrel-20-rhel8'
        )
        text('QUARKUS_VERSION',
                '1.11.6.Final',
                '2.0.0.CR2',
        )
        labelExpression('label', ['el8'])
    }
    description('Run Mandrel container integration tests')
    displayName('Linux :: Container Integration tests')
    logRotator {
        numToKeep(3)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(120)
        }
    }
    combinationFilter(
            ' (BUILDER_IMAGE.contains("20") && QUARKUS_VERSION=="1.11.6.Final") ||' +
            ' (BUILDER_IMAGE.contains("20") && QUARKUS_VERSION=="2.0.0.CR2") ||' +
            ' (BUILDER_IMAGE.contains("21") && QUARKUS_VERSION=="2.0.0.CR2")')
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
            source /etc/profile.d/jdks.sh
            yes | podman volume prune
            export JAVA_HOME="/usr/java/openjdk-11"
            export PATH="${JAVA_HOME}/bin:${PATH}"
            mvn clean verify -Ptestsuite-builder-image -Dquarkus.version=${QUARKUS_VERSION} -Dquarkus.native.container-runtime=podman -Dquarkus.native.builder-image=${BUILDER_IMAGE}
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
