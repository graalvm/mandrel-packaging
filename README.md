# Building mandrelJDK locally

```shell
JAVA_HOME=/opt/jvms/openjdk-11.0.8+4/ MX_HOME=~/code/mx MANDREL_REPO=~/code/mandrel  MANDREL_JDK=./mandrelJDK  ./buildJDK.sh
```

where:
* `JAVA_HOME` is the path to the OpenJDK you want to use for building mandrel
* `MX_HOME` is the path where you cloned https://github.com/graalvm/mx
* `MANDREL_REPO` is the path where you cloned https://github.com/graalvm/mandrel
* `MANDREL_JDK` is the path where you want mandrel to be installed, after completion you will be
 able to use this as `JAVA_HOME` or/and `GRAALVM_HOME` in your projects (e.g. quarkus)
 
You can also add `VERBOSE=true` to see the commands run by the script

# Building maven artifacts using a container

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
