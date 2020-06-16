# Mandrel Packaging

This repo contains all the necessary scripts and tools to build [Mandrel](https://github.com/graalvm/mandrel).

## Building mandrelJDK locally

```shell
JAVA_HOME=/opt/jvms/openjdk-11.0.8+4/ MX_HOME=~/code/mx MANDREL_REPO=~/code/mandrel MANDREL_HOME=./mandrelJDK  ./buildJDK.sh
```

where:
* `JAVA_HOME` is the path to the OpenJDK you want to use for building mandrel. Defaults to `/opt/jdk`.
* `MX_HOME` is the path where you cloned https://github.com/graalvm/mx. Defaults to `/opt/mx`.
* `MANDREL_REPO` is the path where you cloned https://github.com/graalvm/mandrel. Defaults to `/tmp/mandrel`.
* `MANDREL_HOME` is the path where you want mandrel to be installed, after completion you will be
 able to use this as `JAVA_HOME` or/and `GRAALVM_HOME` in your projects (e.g. quarkus). Defaults to `/opt/mandrelJDK`

You can also add `VERBOSE=true` to see the commands run by the script and `MANDREL_VERSION` to define the version to be shown when running `native-image --version` (e.g. 20.1.0). Defaults to the result of `git describe` in `MANDREL_REPO`.

## Building mandrelJDK using a container or a VM

### Requirements

* docker or podman or root access to a Fedora/CentOS/RHEL VM
* ansible

### Description

**TLDR**:

``` bash
git clone https://github.com/mandrel/mandrel-packaging
cd mandrel-packaging
make
```

By default `make`:

1. Creates a docker image named `mandrel-packaging`
2. Starts the newly created image

### Customization and more advanced use

The `Makefile` includes two targets:

1. `build-image` is used to create the `mandrel-packaging` docker image
2. `run-image` is used to start the `mandrel-packaging` image

These two targets can be further customized through the following environment variables:

* `DOCKER` defines the command to be used instead of `docker`, e.g. `podman` (defaults to `docker`)
* `IMAGE_NAME` defines the name of the docker image (defaults to `mandrel-packaging`)
* `BOOT_CONTAINER` defines the name of the container used to bootstrap the image in `make build-image` (defaults to `$(IMAGE_NAME)-boot`)
* `PLAYBOOK` defines the ansible playbook to be used by `make build-image` to setup the image (defaults to `ansible/playbook.yml`)
* `PLAYBOOK_CONF` defines the configuration to be used by the ansible playbook (defaults to `mandrel20.1-openjdk`)
* `DOCKER_RUN_OPTIONS` defines additional options to be passed to `docker run` through `make run-image` (by default it's empty)
* `AT` defines whether commands executed by `make` will be printed out or not (defaults to `@`, i.e suppress output), set to empty to show commands

#### Mounting volumes

When trying out changes in different parts of mandrel we want a fast way to test them out in the `mandrel-packaging` image.
To achieve this we can use docker volumes.

To use a local copy of mandrel source code:
``` bash
export DOCKER_RUN_OPTIONS="-v path/to/mandrel/repo:/tmp/mandrel:Z ${DOCKER_RUN_OPTIONS}"
make run-image
```

To use a local copy of mx:
``` bash
export DOCKER_RUN_OPTIONS="-v path/to/mx/repo:/opt/mx:Z ${DOCKER_RUN_OPTIONS}"
make run-image
```

To use a different JDK:
``` bash
export DOCKER_RUN_OPTIONS="-v path/to/jdk:/opt/jdk:Z ${DOCKER_RUN_OPTIONS}"
make run-image
```

To use a local copy of mandrel-packaging scripts:
``` bash
export DOCKER_RUN_OPTIONS="-v $pwd:/root:Z ${DOCKER_RUN_OPTIONS}"
make run-image
```

To use the local m2 repository:
``` bash
export DOCKER_RUN_OPTIONS="-v $HOME/.m2:/root/.m2:Z ${DOCKER_RUN_OPTIONS}"
make run-image
```

To use the local mx cache:
``` bash
export DOCKER_RUN_OPTIONS="-v $HOME/.mx:/root/.mx:Z ${DOCKER_RUN_OPTIONS}"
make run-image
```

#### Editing the ansible playbook

To avoid building everything from scratch when editing the ansible playbook `make refresh-image` can be used.
This will:
1. Start a container named `mandrel-packaging-boot` from the `mandrel-packaging` image
2. Run the ansible playbook on `mandrel-packaging-boot`
3. Commit the changes to the `mandrel-packaging` image
4. Stop and remove the `mandrel-packaging-boot` container 

### Set up a VM instead of a docker image:

Instead of using containers it's also possible to use Fedora/CentOS/RHEL VMs using: 

``` bash
ansible-playbook -i root@example.com, ansible/playbook.yml
```

#### Using different configurations

The ansible playbook supports different configurations (found under `ansible/configurations`).
To use a different configuration than the default (e.g. `mandrel20.1-labsjdk`) issue:
``` bash
ansible-playbook -i root@example.com, ansible/playbook.yml -e configuration=mandrel20.1-labsjdk
```

To create a new configuration just copy an existing one and edit the values to your needs.
