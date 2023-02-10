package jenkins.jobs.tests

final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-linux-integration-tests-perf') {
    axes {
        text('JDK_VERSION',
                '11',
                '17',
                '20'
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
        text('QUARKUS_VERSION', Constants.QUARKUS_VERSION_RELEASED_PERF)
        labelExpression('LABEL', ['el9_aarch64_perf', 'el8_aarch64_perf', 'el8_amd64_perf'])
    }
    description('Run Mandrel integration tests perf profile DEBUG')
    displayName('Linux :: Integration tests :: Perf sandbox playground')
    logRotator {
        numToKeep(30)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        preBuildCleanup()
        timestamps()
        timeout {
            absolute(60)
        }
        credentialsBinding {
            string("PERF_APP_SECRET_TOKEN", "WRITE_stage-collector.foci.life")
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
        choiceParam(
                'PERF_APP_ENDPOINT',
                ['https://stage-collector.foci.life',
                 'https://collector.foci.life'],
                'Collector endpoint.'
        )
        booleanParam('PERF_APP_REPORT', true, 'Whether the JSON perf report should be actually uploaded.')
        stringParam('MANDREL_INTEGRATION_TESTS_REF', 'q-json', 'Branch or tag.')
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
            command(
                // The LABEL for downloading Mandrel builds is el8_aarch64 or el8 as we don't build on el9.
                '''
                if [[ "${LABEL}" == *aarch64* ]]; then
                    export LABEL=el8_aarch64
                else
                    export LABEL=el8
                fi
                ''' + Constants.LINUX_INTEGRATION_TESTS_PERF)
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
