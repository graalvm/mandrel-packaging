listView('Mandrel') {
    description('Mandrel upstream related jobs')
    filterBuildQueue()
    filterExecutors()
    statusFilter(StatusFilter.ENABLED)
    jobs {
        regex(/mandrel-.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
    }
}
