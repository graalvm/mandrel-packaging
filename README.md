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
