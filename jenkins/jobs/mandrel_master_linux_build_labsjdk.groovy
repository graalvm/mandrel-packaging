job('mandrel-master-linux-build-labsjdk') {
    label 'el8'
    displayName('Linux Build :: master labsjdk')
    description('''
Linux build for master branch with LabsJDK
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
                'graal/master',
                'e.g. your PR branch or a specific tag.'
        )
        choiceParam(
                'OPENJDK',
                [
                        'labsjdk-ce-11',
                        'openjdk-11.0.11_9',
                        'openjdk-11.0.11_8',
                        'openjdk-11-ea',
                        'openjdk-11'

                ],
                'OpenJDK including Static libs'
        )
        choiceParam(
                'PACKAGING_REPOSITORY',
                [
                        'https://github.com/mandrel/mandrel-packaging.git',
                        'https://github.com/zakkak/mandrel-packaging.git',
                        'https://github.com/Karm/mandrel-packaging.git'

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
                'master',
                'e.g. master if you use heads or some tag if you use tags.'
        )
        stringParam(
                'MANDREL_VERSION_SUBSTRING',
                'master-SNAPSHOT',
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
        shell('''
export JAVA_HOME=/usr/java/${OPENJDK}
export MX_HOME=${WORKSPACE}/mx
export PATH=${JAVA_HOME}/bin:${MX_HOME}:${PATH}
pushd mandrel
mx --primary-suite-path substratevm --components='Native Image' --native-images=lib:native-image-agent clean
mx --primary-suite-path substratevm --components='Native Image' --native-images=lib:native-image-agent build
BUILD=$(mx --primary-suite substratevm --components='Native Image' --native-images=lib:native-image-agent graalvm-home)
MANDREL_DIR=mandrel-labsjava11-${MANDREL_VERSION_SUBSTRING}-SNAPSHOT
mv ${BUILD} ${MANDREL_DIR}
export MANDREL_HOME="$(pwd)/${MANDREL_DIR}"
TAR_NAME=mandrel-labsjava11-linux-amd64-${MANDREL_VERSION_SUBSTRING}-SNAPSHOT.tar.gz
tar -czf ${TAR_NAME} ${MANDREL_DIR}
sha1sum ${TAR_NAME}>${TAR_NAME}.sha1
echo "This is not a proper Mandrel build, this is an experiment with LabsJDK" >> README.md
if [[ ! -e "${MANDREL_HOME}/bin/native-image" ]]; then
  echo "Cannot find native-image tool. Quitting..."
  exit 1
fi
cat >./Hello.java <<EOL
public class Hello {
public static void main(String[] args) {
    System.out.println("Hello.");
}
}
EOL
export JAVA_HOME=${MANDREL_HOME}
export PATH=${JAVA_HOME}/bin:${PATH}
javac Hello.java
native-image Hello
if [[ "`./hello`" == "Hello." ]]; then echo Done; else echo Native image fail;exit 1;fi
                 ''')
    }
    publishers {
        archiveArtifacts('mandrel/*.tar.gz,MANDREL.md,*.sha1,*.sha256')
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
                    matrixSubset('(MANDREL_VERSION=="master" && LABEL=="el8")')
                }
            }
        }
    }
}
