matrixJob('mandrel-linux-quarkus-release-tests') {
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
                '2.4.1.Final'
        )
        labelExpression('LABEL', ['el8_aarch64', 'el8'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Linux :: Quarkus TS RELEASE')
    logRotator {
        numToKeep(30)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(720)
        }
    }
    combinationFilter(
            '!(JDK_VERSION.contains("17") && QUARKUS_VERSION.contains("2.2"))'
    )
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
        stringParam('MANDREL_21_3_BUILD_NUM', '7', 'Build number of the Final build: https://ci.modcluster.io/job/mandrel-21.3-linux-build-matrix/')
    }
    steps {
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
            export JAVA_HOME="$( pwd )/$( echo mandrel-java1*-*/ )"
            export GRAALVM_HOME="${JAVA_HOME}"
            export PATH="${JAVA_HOME}/bin:${PATH}"
            popd
            if [[ ! -e "${JAVA_HOME}/bin/native-image" ]]; then
                echo "Cannot find native-image tool. Quitting..."
                exit 1
            fi
            native-image --version
            # Prepare Quarkus
            git clone --depth 1 --branch ${QUARKUS_VERSION} ${QUARKUS_REPO}
            cd quarkus
            
            # Build Quarkus
            ./mvnw install -Dquickly
            
            # Test Quarkus
            export MODULES="-pl \\
!bouncycastle-fips-jsse,\\
!devtools,\\
!google-cloud-functions,\\
!google-cloud-functions-http,\\
!kubernetes-client,\\
!kubernetes/maven-invoker-way,\\
!maven"
            ./mvnw verify -f integration-tests/pom.xml --fail-at-end --batch-mode -DfailIfNoTests=false -Dnative ${MODULES}
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
        archiveArtifacts('**/target/*-reports/*.xml')
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