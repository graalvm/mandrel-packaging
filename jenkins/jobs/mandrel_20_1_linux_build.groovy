job('mandrel-20.1-linux-build') {
    label 'el8'
    displayName('Linux Build :: 20.1')
    description('''
Linux build for 20.1 branch.
    ''')
    logRotator {
        numToKeep(5)
    }
    parameters {
        choiceParam(
                'REPOSITORY',
                [
                        'https://github.com/graalvm/mandrel.git',
                        'https://github.com/jerboaa/graal.git',
                        'https://github.com/Karm/graal.git',
                        'https://github.com/zakkak/mandrel.git',
                ],
                'Mandrel repo'
        )
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
                'mandrel/20.1',
                'e.g. your PR branch or a specific tag.'
        )
        choiceParam(
                'OPENJDK',
                [
                        'openjdk-11.0.10_9',
                        'openjdk-11.0.9.1_1',
                        'openjdk-11-ea',
                        'openjdk-11'

                ],
                'OpenJDK including Static libs'
        )
        choiceParam(
                'PACKAGING_REPOSITORY',
                [
                        'https://github.com/mandrel/mandrel-packaging.git',
                        'https://github.com/Karm/mandrel-packaging.git',
                        'https://github.com/zakkak/mandrel-packaging.git'
                ],
                'Mandrel packaging scripts.'
        )
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
                '20.1',
                'e.g. master if you use heads or some tag if you use tags.'
        )
        stringParam(
                'MANDREL_VERSION_SUBSTRING',
                '20.1-SNAPSHOT',
                '''Used as a part of Mandrel version, i.e. <pre>export MANDREL_VERSION="${MANDREL_VERSION_SUBSTRING} 
git rev.: $( git log --pretty=format:%h -n1 ) </pre> it defaults to <pre>${BRANCH_OR_TAG}</pre>. 
Could be e.g. 20.1.0.0.Alpha1. It must not contain spaces as it is used in tarball name too.'''
        )
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
            branches('*/master')
            extensions {
                localBranch('master')
                relativeTargetDirectory('mx')
            }
        }
    }
    triggers {
        scm('H H/2 * * *')
    }
    steps {
        shell('echo MANDREL_VERSION_SUBSTRING=${MANDREL_VERSION_SUBSTRING}')
        buildDescription(/MANDREL_VERSION_SUBSTRING=([^\s]*)/, '\\1')
        shell('./jenkins/jobs/scripts/mandrel_linux_build.sh')
    }
    publishers {
        archiveArtifacts('*.tar.gz,MANDREL.md,*.sha1,*.sha256')
        wsCleanup()
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
            trigger(['mandrel-linux-quarkus-tests', 'mandrel-linux-integration-tests']) {
                condition('SUCCESS')
                parameters {
                    currentBuild()
                    matrixSubset('(MANDREL_VERSION=="20.1" && QUARKUS_VERSION=="1.7.6.Final" && LABEL=="el8") ||' +
                                 '(MANDREL_VERSION=="20.1" && LABEL=="el8")')
                }
            }
        }
    }
}
