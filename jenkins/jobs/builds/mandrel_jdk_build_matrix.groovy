package jenkins.jobs.builds

final Class Constants = new GroovyClassLoader(getClass().getClassLoader())
        .parseClass(readFileFromWorkspace("jenkins/jobs/builds/Constants.groovy"))
matrixJob('mandrel-jdk-build-matrix') {
    displayName('JDK Matrix Build :: Multi-Platform')
    description('Builds and tests JDK across Linux (amd64, aarch64), macOS (aarch64), and Windows (amd64) with JTreg')
    parameters {
        stringParam('BOOT_JDK_VERSION', '24', 'Boot JDK version (e.g., 24)')
        stringParam('BOOT_JDK_RELEASE', 'ea', 'Boot JDK release type (e.g., ea, ga)')
        stringParam('BOOT_JDK_PROVIDER', 'eclipse', 'Boot JDK provider (e.g., eclipse)')
        stringParam('JTREG_REPO', 'https://github.com/openjdk/jtreg.git', 'JTreg Git repository URL')
        stringParam('JTREG_TAG', 'master', 'JTreg Git tag or branch to checkout')
        stringParam('JDK_REPO', 'https://github.com/Karm/jdk', 'JDK Git repository URL to build')
        stringParam('JDK_BRANCH', 'JDK-8349099', 'JDK Git branch to checkout')
        stringParam('JDK_FEATURE_VERSION', '25', 'JDK feature version (e.g., 25)')
        stringParam('JDK_UPDATE_VERSION', '0', 'JDK feature version (e.g., 0)')
        stringParam('JTREG_TEST_DIR', 'test/jdk/java/awt/Headless', 'JTreg test directory to run')
    }
    logRotator {
        numToKeep(5)
    }
    axes {
        labelExpression('LABEL', ['el8', 'el8_aarch64', 'macos_aarch64', 'w2k19'])
    }
    steps {
        conditionalSteps {
            condition {
                stringsMatch('${LABEL}', 'el8_aarch64', false)
            }
            steps {
                shell {
                    command(Constants.LINUX_JDK_BUILD_CMD)
                    unstableReturn(1)
                }
            }
        }
        conditionalSteps {
            condition {
                stringsMatch('${LABEL}', 'el8', false)
            }
            steps {
                shell {
                    command(Constants.LINUX_JDK_BUILD_CMD)
                    unstableReturn(1)
                }
            }
        }
        conditionalSteps {
            condition {
                stringsMatch('${LABEL}', 'macos_aarch64', false)
            }
            steps {
                shell {
                    command(Constants.MACOS_JDK_BUILD_CMD)
                    unstableReturn(1)
                }
            }
        }
        conditionalSteps {
            condition {
                stringsMatch('${LABEL}', 'w2k19', false)
            }
            steps {
                batchFile {
                    command(Constants.WINDOWS_JDK_BUILD_CMD)
                    unstableReturn(1)
                }
            }
        }
    }
    publishers {
        archiveArtifacts {
            pattern('jdk/jtreg_results/**')
            pattern('graal-builder-jdk.tar.xz')
            pattern('graal-builder-jdk.zip')
            allowEmpty(false)
        }
        wsCleanup()
    }
}
