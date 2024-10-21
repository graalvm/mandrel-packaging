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
@echo off
powershell -Command "Remove-Item -Recurse -Force 'C:\\Users\\Administrator\\AppData\\Local\\Temp\\*'" || echo Failed to delete Temp folder, continuing...
powershell -Command "Remove-Item -Recurse -Force 'C:\\workspace\\workspace\\*'" || echo Failed to delete Workspace folder, continuing...
powershell -Command "Remove-Item -Recurse -Force 'C:\\tmp\\*'" || echo Failed to delete tmp folder, continuing...
powershell -Command "Remove-Item -Recurse -Force 'C:\\Users\\Administrator\\.dotnet\\*'" || echo Failed to delete .dotnet folder, continuing...
powershell -Command "Remove-Item -Recurse -Force 'C:\\Users\\Administrator\\.gradle\\*'" || echo Failed to delete .gradle folder, continuing...
powershell -Command "Remove-Item -Recurse -Force 'C:\\Users\\Administrator\\.redhat\\*'" || echo Failed to delete .redhat folder, continuing...
powershell -Command "Remove-Item -Recurse -Force 'C:\\Users\\Administrator\\.quarkus\\*'" || echo Failed to delete .quarkus folder, continuing...
powershell -Command "Remove-Item -Recurse -Force 'C:\\Users\\Administrator\\.m2\\*'" || echo Failed to delete .m2 folder, continuing...
            ''')
            unstableReturn(1)
        }
    }
}
