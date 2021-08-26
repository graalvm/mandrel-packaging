matrixJob('mandrel-linux-integration-tests') {
    axes {
        text('MANDREL_VERSION',
                'graal-vm-20.3',
                '20.3',
                '21.2',
                'master',
                'master-jdk17'
        )
        text('QUARKUS_VERSION',
                '1.11.7.Final',
                '2.1.3.Final',
                '2.2.0.Final'
        )
        labelExpression('LABEL', ['el8'])
    }
    description('Run Mandrel integration tests')
    displayName('Linux :: Integration tests')
    logRotator {
        numToKeep(5)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(120)
        }
    }
    combinationFilter(
            ' (MANDREL_VERSION.contains("20.3") && QUARKUS_VERSION.startsWith("1.")) ||' +
            ' (MANDREL_VERSION.contains("20.3") && QUARKUS_VERSION.startsWith("2.")) ||' +
            ' ((MANDREL_VERSION.contains("21.2") || MANDREL_VERSION.contains("master")) && QUARKUS_VERSION.startsWith("2."))')
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
        shell('echo DESCRIPTION_STRING=Q:${QUARKUS_VERSION},M:${MANDREL_VERSION}')
        buildDescription(/DESCRIPTION_STRING=([^\s]*)/, '\\1')
        shell('''
            # Prepare Mandrel
            case $MANDREL_VERSION in
                graal-vm-20.3)  BUILD_JOB='mandrel-graal-vm-20.3-linux-build';;
                20.3)           BUILD_JOB='mandrel-20.3-linux-build';;
                21.2)           BUILD_JOB='mandrel-21.2-linux-build';;
                master)         BUILD_JOB='mandrel-master-linux-build';;
                master-jdk17)   BUILD_JOB='mandrel-master-jdk17-linux-build';;
                *)
                    echo "UNKNOWN Mandrel version: $MANDREL_VERSION"
                    exit 1
            esac
            wget "https://ci.modcluster.io/view/Mandrel/job/${BUILD_JOB}/lastSuccessfulBuild/artifact/*zip*/archive.zip" 
            unzip archive.zip
            pushd archive
            MANDREL_TAR=`ls -1 *.tar.gz`
            tar -xvf "${MANDREL_TAR}"
            source /etc/profile.d/jdks.sh
            export JAVA_HOME="$( pwd )/$( echo mandrel-java1*-*/ )"
            export GRAALVM_HOME="${JAVA_HOME}"
            export PATH="${JAVA_HOME}/bin:${PATH}"
            if [[ ! -e "${JAVA_HOME}/bin/native-image" ]]; then
                echo "Cannot find native-image tool. Quitting..."
                exit 1
            fi
            native-image --version
            popd
            mvn clean verify -Ptestsuite -Dquarkus.version=${QUARKUS_VERSION}
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
