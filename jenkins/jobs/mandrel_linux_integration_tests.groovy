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
    //combinationFilter('jdk=="jdk-8" || label=="linux"')
    parameters {
        stringParam('MANDREL_INTEGRATION_TESTS_REPO', 'https://github.com/Karm/mandrel-integration-tests.git', 'Test suite repository.')
        choiceParam(
                'MANDREL_INTEGRATION_TESTS_REF_TYPE',
                ['heads', 'tags'],
                'Choose "heads" if you want to build from a branch, or "tags" if you want to build from a tag.'
        )
        stringParam('MANDREL_INTEGRATION_TESTS_REF', 'master', 'Branch or tag.')
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
        shell('''
            case $MANDREL_VERSION in
                20.3)    BUILD_JOB='mandrel-20.3-linux-build' && sed -i 's~<quarkus.version>.*~<quarkus.version>1.11.6.Final</quarkus.version>~g' pom.xml;;
                21.1)    BUILD_JOB='mandrel-21.1-linux-build' && sed -i 's~<quarkus.version>.*~<quarkus.version>2.0.0.Alpha1</quarkus.version>~g' pom.xml;;
                master)  BUILD_JOB='mandrel-master-linux-build' && sed -i 's~<quarkus.version>.*~<quarkus.version>2.0.0.Alpha1</quarkus.version>~g' pom.xml;;
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
