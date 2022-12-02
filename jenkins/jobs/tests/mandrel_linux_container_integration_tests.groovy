final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-linux-container-integration-tests') {
    axes {
        text('BUILDER_IMAGE',
                'quay.io/quarkus/ubi-quarkus-mandrel:21.3-java11',
                'quay.io/quarkus/ubi-quarkus-mandrel:21.3-java17',
                'quay.io/quarkus/ubi-quarkus-mandrel:22.2-java11',
                'quay.io/quarkus/ubi-quarkus-mandrel:22.2-java17',
                'quay.io/quarkus/ubi-quarkus-mandrel:22.3-java17'
        )
        text('QUARKUS_VERSION', Constants.QUARKUS_VERSION_RELEASED)
        labelExpression('LABEL', ['el8'])
    }
    description('Run Mandrel container integration tests')
    displayName('Linux :: Container Integration tests')
    logRotator {
        numToKeep(300)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        preBuildCleanup()
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
        matrixCombinationsParam('MATRIX_COMBINATIONS_FILTER', "", 'Choose which combinations to run')
    }
    scm {
        git {
            remote {
                url('${MANDREL_INTEGRATION_TESTS_REPO}')
            }
            branch('refs/${MANDREL_INTEGRATION_TESTS_REF_TYPE}/${MANDREL_INTEGRATION_TESTS_REF}')
        }
    }
    triggers {
        cron {
            spec('0 1 * * 3,6')
        }
    }
    steps {
        shell('echo DESCRIPTION_STRING=${QUARKUS_VERSION},${BUILDER_IMAGE}')
        buildDescription(/DESCRIPTION_STRING=([^\s]*)/, '\\1')
        shell {
            command(Constants.LINUX_CONTAINER_INTEGRATION_TESTS)
            unstableReturn(1)
        }
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
