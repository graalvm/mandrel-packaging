matrixJob('mandrel-linux-integration-tests') {
    axes {
        text('JDK_VERSION',
                'jdk11',
                'jdk17'
        )
        text('MANDREL_VERSION',
                'graal-vm-21.3',
                '21.3',
                'master'
        )
        text('QUARKUS_VERSION',
                '2.2.3.Final',
                '2.4.1.Final'
        )
        labelExpression('LABEL', ['el8_aarch64', 'el8'])
    }
    description('Run Mandrel integration tests')
    displayName('Linux :: Integration tests')
    logRotator {
        numToKeep(30)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(120)
        }
    }
//    combinationFilter(
//            ' (MANDREL_VERSION.contains("20") && QUARKUS_VERSION.startsWith("1.")) ||' +
//            ' (MANDREL_VERSION.contains("20") && QUARKUS_VERSION.startsWith("2.")) ||' +
//            ' ((MANDREL_VERSION.contains("21") || MANDREL_VERSION.contains("master")) && (QUARKUS_VERSION=="main" || QUARKUS_VERSION.startsWith("2.")))')
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
        shell('echo DESCRIPTION_STRING=Q:${QUARKUS_VERSION},M:${MANDREL_VERSION},J:${JDK_VERSION}')
        buildDescription(/DESCRIPTION_STRING=([^\s]*)/, '\\1')
        shell('''
            # Prepare Mandrel
            wget "https://ci.modcluster.io/view/Mandrel/job/mandrel-${MANDREL_VERSION}-linux-build-matrix/JDK_VERSION=${JDK_VERSION},LABEL=${LABEL}/lastSuccessfulBuild/artifact/*zip*/archive.zip"
            unzip archive.zip
            pushd archive
            export MANDREL_TAR=`ls -1 *.tar.gz`
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
        groovyPostBuild('''
            if(manager.logContains(".*GRAALVM_HOME.*mandrel-java1.*-Final.*")){
                def build = Thread.currentThread()?.executable
                build.rootBuild.keepLog(true)
            }
        ''', Behavior.DoNothing)
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
        postBuildCleanup {
            cleaner {
                psCleaner {
                    killerType('org.jenkinsci.plugins.proccleaner.PsRecursiveKiller')
                }
            }
        }
    }
}
