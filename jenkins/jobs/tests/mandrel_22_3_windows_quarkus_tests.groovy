package jenkins.jobs.tests

final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-22-3-windows-quarkus-tests') {
    axes {
        text('JDK_VERSION',
                '17'
        )
        text('JDK_RELEASE',
                'ga'
        )
        text('MANDREL_BUILD',
                'mandrel-22-3-windows-build-matrix'
        )
        text('QUARKUS_VERSION', Constants.QUARKUS_VERSION_SHORT)
        labelExpression('LABEL', ['w2k19'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Windows :: Quarkus TS :: 22.3')
    logRotator {
        numToKeep(30)
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
            Constants.QUARKUS_VERSION_SHORT_COMBINATION_FILTER
    )
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
        stringParam('QUARKUS_MODULES', Constants.QUARKUS_MODULES_TESTS)
        stringParam(
                'MANDREL_BUILD_NUMBER',
                'lastSuccessfulBuild',
                'Pick a build number from MANDREL_BUILD or leave the default latest.'
        )
    }
    steps {
        conditionalSteps {
            condition {
                batch(Constants.WINDOWS_CHECK_MANDREL_BUILD_AVAILABILITY)
            }
            runner('DontRun')
            steps {
                batchFile {
                    command(Constants.WINDOWS_QUARKUS_TESTS)
                    unstableReturn(1)
                }
            }
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
