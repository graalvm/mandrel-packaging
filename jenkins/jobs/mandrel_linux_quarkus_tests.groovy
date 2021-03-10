matrixJob('mandrel-linux-quarkus-tests') {
    axes {
        text('MANDREL_VERSION',
                '20.1',
                '20.3',
                '21.0',
                'master'
        )
        text('QUARKUS_VERSION',
                '1.11.6.Final',
                '1.7.6.Final',
                'master'
        )
        labelExpression('LABEL', ['el8', 'el7||just_smoke_test'])
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
            absolute(360)
        }
    }
    combinationFilter('(MANDREL_VERSION=="20.1" && QUARKUS_VERSION=="1.7.6.Final") ||' +
            ' (MANDREL_VERSION=="20.3" && QUARKUS_VERSION=="1.11.6.Final") ||' +
            ' ((MANDREL_VERSION=="21.0" || MANDREL_VERSION=="master") && QUARKUS_VERSION=="master")')
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
    }
    steps {
        shell('''
            # Prepare Mandrel
            case $MANDREL_VERSION in
                20.1)    BUILD_JOB='mandrel-20.1-linux-build';;
                20.3)    BUILD_JOB='mandrel-20.3-linux-build';;
                21.0)    BUILD_JOB='mandrel-21.0-linux-build';;
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
            ./mvnw clean install -DskipTests -pl '!docs'
            
            # Test Quarkus
             if [[ "${LABEL}" =~ "el8" ]]; then
                export MODULES="-pl \\
!google-cloud-functions,\\
!google-cloud-functions-http,\\
!kubernetes/maven-invoker-way"
             else
                # The rest runs a subset, el7 VM has just 8G RAM
                export MODULES="-pl \\
bootstrap-config,\\
class-transformer,\\
common-jpa-entities,\\
elytron-resteasy,\\
jackson,\\
logging-gelf,\\
packaging,\\
quartz,\\
rest-client,\\
resteasy-jackson,\\
resteasy-mutiny,\\
shared-library,\\
test-extension,\\
vertx-graphql,\\
virtual-http-resteasy"
             fi
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
