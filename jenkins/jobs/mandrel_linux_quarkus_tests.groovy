matrixJob('mandrel-linux-quarkus-tests') {
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
                '2.4.1.Final',
                'main'
        )
        labelExpression('LABEL', ['el8_aarch64', 'el8'])
    }
    description('Run Quarkus TS with Mandrel distros. Quarkus versions differ according to particular Mandrel versions.')
    displayName('Linux :: Quarkus TS')
    logRotator {
        numToKeep(5)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        timestamps()
        timeout {
            absolute(720)
        }
    }
//    combinationFilter(
//            ' (MANDREL_VERSION.contains("20") && QUARKUS_VERSION.startsWith("1.")) ||' +
//            ' (MANDREL_VERSION.contains("20") && QUARKUS_VERSION.startsWith("2.")) ||' +
//            ' ((MANDREL_VERSION.contains("21") || MANDREL_VERSION.contains("master")) && (QUARKUS_VERSION=="main" || QUARKUS_VERSION.startsWith("2.")))')
    parameters {
        stringParam('QUARKUS_REPO', 'https://github.com/quarkusio/quarkus.git', 'Quarkus repository.')
    }
    steps {
        shell('''
            # Prepare Mandrel
            wget "https://ci.modcluster.io/view/Mandrel/job/mandrel-${MANDREL_VERSION}-linux-build-matrix/JDK_VERSION=${JDK_VERSION},label=${LABEL}/lastSuccessfulBuild/artifact/*zip*/archive.zip" 
            unzip archive.zip
            pushd archive
            MANDREL_TAR=`ls -1 *.tar.gz`
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
