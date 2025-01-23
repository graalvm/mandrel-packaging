package jenkins.jobs.tests

final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-windows-quarkus-subset') {
    axes {
        text('JDK_VERSION',
                '21',
                '23',
                '24'
        )
        text('JDK_RELEASE',
                'ea',
                'ga'
        )
        text('MANDREL_BUILD',
                'mandrel-23-1-windows-build-matrix',
                'mandrel-24-1-windows-build-matrix',
                'mandrel-24-2-windows-build-matrix',
                'mandrel-master-windows-build-matrix'
        )
        text('QUARKUS_VERSION', Constants.QUARKUS_VERSION_RELEASED)
        labelExpression('LABEL', ['w2k19'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Windows :: Quarkus TS 999-SNAPSHOT Subset')
    logRotator {
        numToKeep(3)
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
            //@formatter:off
                    '(JDK_VERSION.equals("21") && JDK_RELEASE.equals("ga") && MANDREL_BUILD.startsWith("mandrel-23-1")) ||' +
                    '(JDK_VERSION.equals("23") && JDK_RELEASE.equals("ga") && MANDREL_BUILD.startsWith("mandrel-24-1")) ||' +
                    '(JDK_VERSION.equals("24") && JDK_RELEASE.equals("ea") && MANDREL_BUILD.startsWith("mandrel-master"))'
            //@formatter:on
    )
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/zakkak/quarkus.git', 'Quarkus repository.')
        stringParam('QUARKUS_MODULES', 'locales/all,locales/some,locales/default', 'Uses .github/native-tests.json unless specified here.')
        stringParam(
                'MANDREL_BUILD_NUMBER',
                'lastSuccessfulBuild',
                'Pick a build number from MANDREL_BUILD or leave the default latest.'
        )
        matrixCombinationsParam('MATRIX_COMBINATIONS_FILTER', "", 'Choose which combinations to run')
    }
    triggers {
        cron {
            spec('0 1 * * 1,4')
        }
    }
    steps {
        batchFile {
            command(Constants.WINDOWS_QUARKUS_TESTS.stripIndent())
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
