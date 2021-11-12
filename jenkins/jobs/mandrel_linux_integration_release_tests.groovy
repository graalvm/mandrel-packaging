matrixJob('mandrel-linux-integration-release-tests') {
    axes {
        text('JDK_VERSION',
                'jdk11',
                'jdk17'
        )
        text('MANDREL_VERSION',
                '21.3'
        )
        text('QUARKUS_VERSION',
                '2.2.3.Final',
                '2.4.1.Final',
                '2.5.0.CR1'
        )
        labelExpression('LABEL', ['el8_aarch64', 'el8'])
    }
    description('Run Mandrel integration release tests')
    displayName('Linux :: Integration RELEASE tests')
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
    combinationFilter(
        '!(JDK_VERSION.contains("17") && QUARKUS_VERSION.contains("2.2"))'
    )
    parameters {
        stringParam('MANDREL_INTEGRATION_TESTS_REPO', 'https://github.com/Karm/mandrel-integration-tests.git', 'Test suite repository.')
        choiceParam(
                'MANDREL_INTEGRATION_TESTS_REF_TYPE',
                ['heads', 'tags'],
                'Choose "heads" if you want to build from a branch, or "tags" if you want to build from a tag.'
        )
        stringParam('MANDREL_INTEGRATION_TESTS_REF', 'master', 'Branch or tag.')
        stringParam('MANDREL_21_3_BUILD_NUM', '7', 'Build number of the Final build: https://ci.modcluster.io/job/mandrel-21.3-linux-build-matrix/')
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
            case ${MANDREL_VERSION} in
                21.3)
                    export MANDREL_BUILD_NUM="${MANDREL_21_3_BUILD_NUM}";;
                *)
                    echo "UNKNOWN Mandrel version: $MANDREL_VERSION"
                    exit 1
            esac
            wget "https://ci.modcluster.io/view/Mandrel/job/mandrel-${MANDREL_VERSION}-linux-build-matrix/JDK_VERSION=${JDK_VERSION},LABEL=${LABEL}/${MANDREL_BUILD_NUM}/artifact/*zip*/archive.zip"
            if [[ ! -f "archive.zip" ]]; then 
                echo "Download failed. Quitting..."
                exit 1
            fi
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
