Requirements:

* [`CEKit`](https://github.com/cekit/cekit)

Build image:

```bash
$ make build-image
```

Run builder image and invoke build manually:

```bash
$ make run-image
[mandrel@daddd4779ded ~]$ ./run.sh
```

For quicker turnaround when testing changes,
an `.env` file can be added to the root of the repository with links to local Git clones, e.g.

```bash
PACKAGING_CLONE=<...>
MANDREL_CLONE=<...>
```

These clones are linked via volumes to the image,
so local changes are picked up immediately,
without the need to rebuild the image.

Maven artifacts created by the build can be installed locally,
by providing a local Maven repository location as parameter,
or part of the `.env` file, e.g.

```bash
MAVEN_REPOSITORY=<...>
```
