job('mandrel-jobs') {
    description('Update all Mandrel Jenkins jobs from https://github.com/graalvm/mandrel-packaging/tree/master/jenkins')
    label('master')
    logRotator {
        numToKeep(1)
    }
    scm {
        git {
            remote {
                url('https://github.com/graalvm/mandrel-packaging.git')
            }
            branch('master')
        }
    }
    steps {
        dsl {
            external('jenkins/jobs/**/*.groovy')
        }
    }
}
