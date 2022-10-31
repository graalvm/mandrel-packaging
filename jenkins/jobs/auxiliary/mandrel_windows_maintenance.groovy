package jenkins.jobs.auxiliary

matrixJob('mandrel-windows-maintenance') {
    axes {
        labelExpression('LABEL', [
            'w2k19',
            'w2k19-upstream-1',
            'w2k19-upstream-2',
            'w2k19-upstream-3',
            'w2k19-upstream-4'
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
        batchFile {
            command('''
            powershell -c 'Remove-Item -Recurse -Force "C:\\Users\\Administrator\\AppData\\Local\\Temp\\*"'
            powershell -c 'Remove-Item -Recurse -Force "C:\\workspace\\workspace\\*"'
            ''')
            unstableReturn(1)
        }
    }
}
