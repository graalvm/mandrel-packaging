final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-linux-quarkus-tests') {
    axes {
        text('JDK_VERSION',
                '11',
                '17'
        )
        text('JDK_RELEASE',
                'ea',
                'ga'
        )
        text('MANDREL_BUILD',
                'mandrel-21-3-linux-build-matrix',
                'mandrel-22-3-linux-build-matrix',
                'mandrel-master-linux-build-matrix'
        )
        text('QUARKUS_VERSION', Constants.QUARKUS_VERSION_SHORT)
        labelExpression('LABEL', ['el8_aarch64', 'el8'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Linux :: Quarkus TS')
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
            '!(QUARKUS_VERSION.equals("main") && (MANDREL_BUILD.contains("mandrel-21") || JDK_VERSION.contains("11")))'
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
                shell {
                    command(Constants.LINUX_CHECK_MANDREL_BUILD_AVAILABILITY)
                }
            }
            runner('DontRun')
            steps {
                shell {
                    command(Constants.LINUX_QUARKUS_TESTS)
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
