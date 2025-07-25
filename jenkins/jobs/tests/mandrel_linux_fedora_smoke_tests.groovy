package jenkins.jobs.tests

final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/tests/Constants.groovy"))
matrixJob('mandrel-linux-fedora-smoke-tests') {
    axes {
        text('BUILDER_IMAGE',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-17',
                'quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-17',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21',
                'quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-22',
                'quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-22',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-23',
                'quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-23',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-24',
                'quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-24',
                'quay.io/quarkus/ubi-quarkus-mandrel-builder-image:dev',
                'quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:dev'
        )
        labelExpression('LABEL', ['fedora_aarch64', 'fedora_amd64', 'el8_aarch64', 'el8'])
    }
    combinationFilter('!(BUILDER_IMAGE.contains("ubi9")) || LABEL in ["fedora_aarch64", "fedora_amd64"]')
    description('Run Mandrel smoke test, vanilla Fedora vs. RHEL 8')
    displayName('Linux/Fedora :: Container smoke test')
    logRotator {
        numToKeep(10)
    }
    childCustomWorkspace('${SHORT_COMBINATION}')
    wrappers {
        preBuildCleanup()
        timestamps()
        timeout {
            absolute(120)
        }
    }
    triggers {
        cron {
            spec('0 1 * * 3,6')
        }
    }
    steps {
        shell {
            command('''
            #!/bin/bash
            getenforce
            podman info
            uname -a
            echo 'public class Main{public static void main(String[] args){System.out.println("Hello.");}}' > Main.java
            podman pull ${BUILDER_IMAGE}
            podman run -u 0:0 -t --entrypoint javac -v $(pwd):/project:z ${BUILDER_IMAGE} Main.java
            podman run -u 0:0 -t -v $(pwd):/project:z ${BUILDER_IMAGE} --no-fallback --link-at-build-time -O3 -march=native Main
            if [ "$(./main)" == "Hello." ]; then
                echo Done.
                exit 0
            else
                echo Error.
                exit 666
            fi
            '''.stripIndent()
            )
            unstableReturn(1)
        }
    }
    publishers {
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
