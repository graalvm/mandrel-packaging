final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-linux-integration-tests') {
    axes {
        text('JDK_VERSION',
                '17',
                '21',
                '22'
        )
        text('JDK_RELEASE',
                'ea',
                'ga'
        )
        text('MANDREL_BUILD',
                'mandrel-22-3-linux-build-matrix',
                'mandrel-23-1-linux-build-matrix',
                'mandrel-23-0-linux-build-matrix',
                'mandrel-master-linux-build-matrix'
        )
        text('QUARKUS_VERSION', Constants.QUARKUS_VERSION_RELEASED)
        labelExpression('LABEL', ['el8_aarch64', 'el8'])
    }
    description('Run Mandrel integration tests')
    displayName('Linux :: Integration tests')
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
            Constants.QUARKUS_VERSION_RELEASED_COMBINATION_FILTER
    )
    parameters {
        stringParam(
                'MANDREL_BUILD_NUMBER',
                'lastSuccessfulBuild',
                'Pick a build number from MANDREL_BUILD or leave the default latest.'
        )
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
    steps {
        shell('echo DESCRIPTION_STRING=Q:${QUARKUS_VERSION},M:${MANDREL_BUILD},J:${JDK_VERSION}-${JDK_RELEASE}')
        buildDescription(/DESCRIPTION_STRING=([^\s]*)/, '\\1')
        shell {
            command(Constants.LINUX_INTEGRATION_TESTS)
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
