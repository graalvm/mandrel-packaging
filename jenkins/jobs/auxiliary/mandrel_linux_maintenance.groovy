package jenkins.jobs.auxiliary

matrixJob('mandrel-linux-maintenance') {
    axes {
        labelExpression('LABEL', [
            'karm-rhel8-x86-64',
            'rhel8-aarch64-vm-1',
            'rhel8-aarch64-vm-2',
            'rhel8-aarch64-1',
            'rhel8-aarch64-2',
            'rhel8-upstream-1',
            'rhel8-upstream-2',
            'rhel8-upstream-3',
            'rhel8-upstream-4'
        ])
    }
    logRotator {
        numToKeep(5)
    }
    triggers {
        cron {
            spec('H H * * *')
        }
    }
    wrappers {
        timeout {
            absolute(180)
        }
    }
    steps {
        shell {
            command('''
            set +e
            sudo podman stop $(sudo podman ps -a -q)
            sudo podman rm $(sudo podman ps -a -q)
            yes | sudo podman system prune
            rm -rf /tmp/run-1000/libpod/tmp/pause.pid
            podman stop $(podman ps -a -q)
            podman rm $(podman ps -a -q)
            yes | podman system prune
            rm -rf /home/tester/jenkins/workspace/*
            ''')
            unstableReturn(0)
        }
    }
}
