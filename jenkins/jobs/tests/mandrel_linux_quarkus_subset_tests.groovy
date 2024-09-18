package jenkins.jobs.tests

final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-linux-quarkus-subset-tests') {
    axes {
        text('JDK_VERSION',
                '17',
                '21',
                '22',
                '23'
        )
        text('JDK_RELEASE',
                'ea',
                'ga'
        )
        text('MANDREL_BUILD',
                'mandrel-22-3-linux-build-matrix',
                'mandrel-23-0-linux-build-matrix',
                'mandrel-23-1-linux-build-matrix',
                'mandrel-24-0-linux-build-matrix',
                'mandrel-24-1-linux-build-matrix',
                'mandrel-master-linux-build-matrix'
        )
        text('QUARKUS_VERSION', Constants.QUARKUS_VERSION_RELEASED)
        labelExpression('LABEL', ['el8_aarch64', 'el8'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Linux :: Quarkus TS :: Subset')
    logRotator {
        numToKeep(10)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        preBuildCleanup()
        timestamps()
        timeout {
            absolute(820)
        }
    }
    combinationFilter(
            Constants.QUARKUS_VERSION_RELEASED_COMBINATION_FILTER
    )
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
        stringParam('QUARKUS_MODULES', Constants.QUARKUS_MODULES_SUBSET_TESTS)
        stringParam(
                'MANDREL_BUILD_NUMBER',
                'lastSuccessfulBuild',
                'Pick a build number from MANDREL_BUILD or leave the default latest.'
        )
        matrixCombinationsParam('MATRIX_COMBINATIONS_FILTER', "", 'Choose which combinations to run')
    }
    triggers {
        cron {
            spec('0 1 * * 2,5')
        }
    }
    steps {
        shell {
            command(Constants.LINUX_QUARKUS_TESTS)
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
