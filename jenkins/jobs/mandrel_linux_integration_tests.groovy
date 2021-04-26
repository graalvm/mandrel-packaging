matrixJob('mandrel-linux-integration-tests') {
    axes {
        text('MANDREL_VERSION',
                '20.3',
                '21.1',
                'master'
        )
        labelExpression('LABEL', ['el8'])
    }
    description('Run Mandrel integration tests')
    displayName('Linux :: Integration tests')
    logRotator {
        numToKeep(3)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(120)
        }
    }
    parameters {
        stringParam('MANDREL_INTEGRATION_TESTS_REPO', 'https://github.com/Karm/mandrel-integration-tests.git', 'Test suite repository.')
    }

    steps {
        shell('''
            case $MANDREL_VERSION in
                20.3)    BUILD_JOB='mandrel-20.3-linux-build' && \
                         git clone --single-branch --branch quarkus-1.11.x-mandrel-20.3.y ${MANDREL_INTEGRATION_TESTS_REPO} .;;
                21.1)    BUILD_JOB='mandrel-21.1-linux-build' && \
                         git clone --single-branch master ${MANDREL_INTEGRATION_TESTS_REPO} .;;
                master)  BUILD_JOB='mandrel-master-linux-build' && \
                         git clone --single-branch master ${MANDREL_INTEGRATION_TESTS_REPO} .;;
                *)
                    echo "UNKNOWN Mandrel version: $MANDREL_VERSION"
                    exit 1
            esac
            
            wget "https://ci.modcluster.io/view/Mandrel/job/${BUILD_JOB}/lastSuccessfulBuild/artifact/*zip*/archive.zip" --no-check-certificate 
            unzip archive.zip
            pushd archive
            MANDREL_TAR=`ls -1 *.tar.gz`
            tar -xvf "${MANDREL_TAR}"
            export JAVA_HOME="$( pwd )/$( echo mandrel-java11*-*/ )"
            export GRAALVM_HOME="${JAVA_HOME}"
            export PATH="${JAVA_HOME}/bin:${PATH}"
            if [[ ! -e "${JAVA_HOME}/bin/native-image" ]]; then
                echo "Cannot find native-image tool. Quitting..."
                exit 1
            fi
            popd
            mvn clean verify -Ptestsuite
        ''')
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
    }
}
