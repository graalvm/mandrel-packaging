package jenkins.jobs.builds

final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/builds/Constants.groovy"))
matrixJob('mandrel-23-1-macos-build-matrix') {
    axes {
        labelExpression('LABEL', ['macos_aarch64'])
        text('JDK_VERSION',
                '21',
        )
        text('JDK_RELEASE',
                'ea',
                'ga'
        )
    }
    displayName('MacOS Build Matrix :: 23.1')
    description('MacOS build for 23.1 branch.')
    logRotator {
        numToKeep(10)
    }
    //combinationFilter(
    //        '!(JDK_VERSION=="21" && JDK_RELEASE=="ga")'
    //)
    parameters {
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
                'mandrel/23.1',
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
                '23.1',
                'e.g. master if you use heads or some tag if you use tags.'
        )
        stringParam(
                'MANDREL_VERSION_SUBSTRING',
                '23.1-SNAPSHOT',
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
            branches('refs/tags/6.52.0')
            extensions {
                localBranch('6.52.0')
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
            command(Constants.MACOS_BUILD_CMD)
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
    }
}
