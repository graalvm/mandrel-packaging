matrixJob('mandrel-linux-quarkus-tests') {
    axes {
        text('MANDREL_VERSION',
                '20.3',
                '21.1',
                'master'
        )
        text('QUARKUS_VERSION',
                '1.11.6.Final',
                '2.0.0.CR2',
                'main'
        )
        labelExpression('LABEL', ['el8'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Linux :: Quarkus TS')
    logRotator {
        numToKeep(3)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(720)
        }
    }
    combinationFilter(
            ' (MANDREL_VERSION=="20.3" && QUARKUS_VERSION=="1.11.6.Final") ||' +
            ' (MANDREL_VERSION=="20.3" && QUARKUS_VERSION=="2.0.0.CR2") ||' +
            ' ((MANDREL_VERSION=="21.1" || MANDREL_VERSION=="master") && (QUARKUS_VERSION=="main" || QUARKUS_VERSION=="2.0.0.CR2"))')
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
    }
    steps {
        shell('''
            # Prepare Mandrel
            case $MANDREL_VERSION in
                20.3)    BUILD_JOB='mandrel-20.3-linux-build';;
                21.1)    BUILD_JOB='mandrel-21.1-linux-build';;
                master)  BUILD_JOB='mandrel-master-linux-build';;
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
            popd
            if [[ ! -e "${JAVA_HOME}/bin/native-image" ]]; then
                echo "Cannot find native-image tool. Quitting..."
                exit 1
            fi
            
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
    }
}
