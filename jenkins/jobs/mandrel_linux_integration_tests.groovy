matrixJob('mandrel-linux-integration-tests') {
    axes {
        text('MANDREL_VERSION',
                '20.3',
                '21.1',
                'master'
        )
        text('QUARKUS_VERSION',
                '1.11.7.Final',
                '2.0.0.CR3',
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
    combinationFilter(
            ' (MANDREL_VERSION=="20.3" && QUARKUS_VERSION=="1.11.7.Final") ||' +
            ' (MANDREL_VERSION=="20.3" && QUARKUS_VERSION=="2.0.0.CR3") ||' +
            ' ((MANDREL_VERSION=="21.1" || MANDREL_VERSION=="master") && QUARKUS_VERSION=="2.0.0.CR3")')
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
            wget "https://ci.modcluster.io/view/Mandrel/job/${BUILD_JOB}/lastSuccessfulBuild/artifact/*zip*/archive.zip" 
            unzip archive.zip
            pushd archive
            MANDREL_TAR=`ls -1 *.tar.gz`
            tar -xvf "${MANDREL_TAR}"
            source /etc/profile.d/jdks.sh
            export JAVA_HOME="$( pwd )/$( echo mandrel-java11*-*/ )"
            export GRAALVM_HOME="${JAVA_HOME}"
            export PATH="${JAVA_HOME}/bin:${PATH}"
            if [[ ! -e "${JAVA_HOME}/bin/native-image" ]]; then
                echo "Cannot find native-image tool. Quitting..."
                exit 1
            fi
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
