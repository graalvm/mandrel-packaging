package jenkins.jobs.builds

final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/builds/Constants.groovy"))
matrixJob('mandrel-24-2-linux-build-matrix') {
    axes {
        labelExpression('LABEL', ['el8_aarch64', 'el8'])
        text('JDK_VERSION',
                '24'
        )
        text('JDK_RELEASE',
                'ea',
                'ga'
        )
    }
    displayName('Linux Build Matrix :: 24.2')
    description('Linux build for 24.2 branch.')
    logRotator {
        numToKeep(10)
    }
    combinationFilter(
            '!(JDK_VERSION=="24" && JDK_RELEASE=="ga")'
    )
    parameters {
        stringParam(
                'JDK_RELEASE_NAME',
                'latest',
                'You can pick from https://api.adoptium.net/v3/info/release_names or leave it as latest.'
        )
        choiceParam('REPOSITORY', Constants.REPOSITORY, 'Mandrel repo')
        choiceParam(
                'HEADS_OR_TAGS',
                [
                        'heads',
                        'tags',
                ],
                'To be used with the repository, e.g. to use a certain head or a tag.'
        )
        stringParam(
                'BRANCH_OR_TAG',
                'mandrel/24.2',
                'e.g. your PR branch or a specific tag.'
        )
        choiceParam('PACKAGING_REPOSITORY', Constants.PACKAGING_REPOSITORY, 'Mandrel packaging scripts.')
        choiceParam(
                'PACKAGING_REPOSITORY_HEADS_OR_TAGS',
                [
                        'heads',
                        'tags',
                ],
                'To be used with the repository, e.g. to use a certain head or a tag.'
        )
        stringParam(
                'PACKAGING_REPOSITORY_BRANCH_OR_TAG',
                '24.2',
                'e.g. master if you use heads or some tag if you use tags.'
        )
        stringParam(
                'MANDREL_VERSION_SUBSTRING',
                '24.2-SNAPSHOT',
                'It must not contain spaces as it is used in tarball name too.'
        )
        matrixCombinationsParam('MATRIX_COMBINATIONS_FILTER', "", 'Choose which combinations to run')
    }
    multiscm {
        git {
            remote {
                url('${PACKAGING_REPOSITORY}')
            }
            branches('refs/${PACKAGING_REPOSITORY_HEADS_OR_TAGS}/${PACKAGING_REPOSITORY_BRANCH_OR_TAG}')
            extensions {
                localBranch('${PACKAGING_REPOSITORY_BRANCH_OR_TAG}')
            }
        }
        git {
            remote {
                url('${REPOSITORY}')
            }
            branches('refs/${HEADS_OR_TAGS}/${BRANCH_OR_TAG}')
            extensions {
                localBranch('${BRANCH_OR_TAG}')
                relativeTargetDirectory('mandrel')
            }
        }
        git {
            remote {
                url('https://github.com/graalvm/mx.git')
            }
            branches('refs/tags/7.38.1')
            extensions {
                localBranch('7.38.1')
                relativeTargetDirectory('mx')
            }
        }
    }
    triggers {
        scm('H H/2 * * *')
        cron {
            spec('0 2 * * 2,5')
        }
    }
    steps {
        shell {
            command(Constants.LINUX_BUILD_CMD)
            unstableReturn(1)
        }
    }
    publishers {
        buildDescription(/^MANDREL_DESCRIBE=(.*)$/, '\\1')
        archiveArtifacts('mandrel*.tar.gz,MANDREL.md,mandrel*.sha1,mandrel*.sha256,jdk*/release')
        wsCleanup()
        postBuildCleanup {
            cleaner {
                psCleaner {
                    killerType('org.jenkinsci.plugins.proccleaner.PsRecursiveKiller')
                }
            }
        }
        extendedEmail {
            recipientList('karm@redhat.com,fzakkak@redhat.com')
            triggers {
                failure {
                    sendTo {
                        recipientList()
                        attachBuildLog(true)
                    }
                }
            }
        }
        downstreamParameterized {
            trigger(['mandrel-linux-integration-tests']) {
                condition('SUCCESS')
                parameters {
                    predefinedProp('MANDREL_BUILD_NUMBER', '${BUILD_NUMBER}')
                    matrixSubset('(MANDREL_BUILD=="${JOB_BASE_NAME}" && JDK_VERSION=="${JDK_VERSION}" && JDK_RELEASE=="${JDK_RELEASE}" && LABEL=="${LABEL}")')
                }
            }
        }
    }
}
