final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-linux-quarkus-container-tests') {
    axes {
        text('BUILDER_IMAGE',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:22.3-java17',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:23.0-java17',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:23.1-java21'
        )
        text('QUARKUS_VERSION', Constants.QUARKUS_VERSION_BUILDER_IMAGE)
        labelExpression('LABEL', ['el8'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Linux :: Quarkus Builder image TS')
    logRotator {
        numToKeep(10)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        preBuildCleanup()
        timestamps()
        timeout {
            absolute(860)
        }
    }
    combinationFilter(Constants.QUARKUS_VERSION_BUILDER_COMBINATION_FILTER)
    parameters {
        stringParam('CONTAINER_RUNTIME', 'podman', 'Command used, either "docker" or "podman". Note that podman is not installed on all executors...')
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
        stringParam('QUARKUS_MODULES', '', 'Uses .github/native-tests.json unless specified here.')
        matrixCombinationsParam('MATRIX_COMBINATIONS_FILTER', "", 'Choose which combinations to run')
    }
    triggers {
        cron {
            spec('0 1 * * 1,4')
        }
    }
    steps {
        shell('echo DESCRIPTION_STRING=Q:${QUARKUS_VERSION},${BUILDER_IMAGE}')
        buildDescription(/DESCRIPTION_STRING=([^\s]*)/, '\\1')
        shell {
            command(Constants.LINUX_CONTAINER_QUARKUS_TESTS)
            unstableReturn(1)
        }
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
        postBuildCleanup {
            cleaner {
                psCleaner {
                    killerType('org.jenkinsci.plugins.proccleaner.PsRecursiveKiller')
                }
            }
        }
    }
}
