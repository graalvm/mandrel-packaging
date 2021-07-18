job('mandrel-graal-vm-20.3-windows-build') {
    label 'w2k19'
    displayName('Windows Build :: graal-vm/20.3')
    description('''
Graal Windows build for graal-vm/20.3 branch.
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
                'mandrel/20.3',
                'e.g. your PR branch or a specific tag.'
        )
        choiceParam(
                'OPENJDK',
                [
                        'openjdk-11.0.12_6',
                        'openjdk-11.0.11_9',
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
                '20.3',
                'e.g. master if you use heads or some tag if you use tags.'
        )
        stringParam(
                'MANDREL_VERSION_SUBSTRING',
                '20.3-SNAPSHOT',
                'It must not contain spaces as it is used in tarball name too.'
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
            branches('refs/tags/5.273.0')
            extensions {
                localBranch('5.273.0')
                relativeTargetDirectory('mx')
            }
        }
    }
    triggers {
        scm('H H/2 * * *')
    }
    steps {
        batchFile('echo MANDREL_VERSION_SUBSTRING=%MANDREL_VERSION_SUBSTRING%')
        buildDescription(/MANDREL_VERSION_SUBSTRING=([^\s]*)/, '\\1')
        batchFile('''
            pushd mandrel
            git remote add upstream https://github.com/oracle/graal.git
            git fetch upstream release/graal-vm/20.3
            git merge upstream/release/graal-vm/20.3 --no-edit
            popd
        ''')
        batchFile('cmd /C jenkins\\jobs\\scripts\\mandrel_windows_build.bat')
    }
    publishers {
        archiveArtifacts('*.zip,MANDREL.md,*.sha1,*.sha256')
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
            trigger(['mandrel-windows-quarkus-tests', 'mandrel-windows-integration-tests']) {
                condition('SUCCESS')
                parameters {
                    currentBuild()
                    matrixSubset('(MANDREL_VERSION=="graal-vm-20.3" && LABEL=="w2k19")')
                }
            }
        }
    }
}
