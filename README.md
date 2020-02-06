Build image:

```bash
$ make build-image
```

Run builder image and invoke build manually:

```bash
$ make run-image
[mandrel@daddd4779ded ~]$ ./run.sh
```

For quick development, use local clones:

```bash
JBANG_CLONE=<...>/jbang PACKAGING_CLONE=<...>/mandrel-packaging make
```
