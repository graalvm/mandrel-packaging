# Mandrel release scripts

This folder contains a number of scripts targetting to ease the process of
creating new Mandrel releases.

## Step 0 (Prerequisities)

1. [Install jbang](https://github.com/jbangdev/jbang#installation)
2. Create a `~/.github` [property files](https://github-api.kohsuke.org/#Property_file) or set [environmental variables](https://github-api.kohsuke.org/#Environmental_variables)

We suggest [creating a personal access token](https://docs.github.com/en/github/authenticating-to-github/creating-a-personal-access-token) and using the [property file approach](https://github-api.kohsuke.org/#Property_file) by creating a file `~/.github` with content `oauth=YOURTOKEN` 

## Step 1

First we invoke `./mandrel-release.java prepare -m <path/to/mandrel> -f <username/mandrel-fork>` which is responsible for:

1. Marking the suite.py files as releases
2. Creating a new branch and commiting the changes there
3. Pushing the changes to a fork
4. Openning a PR

For usage information please run `./mandrel-release.java -h`

## Step 2

After the changes get approved and merged someone needs to tag the new commit
like this:

```
git checkout mandrel/20.1
git pull upstream mandrel/20.1
git tag -a mandrel-20.1.0.2.Final -m "mandrel-20.1.0.2.Final" -s
git push upstream mandrel-20.1.0.2.Final
```

NOTE: This can't be integrated into `mandrel-release.java` yet due to
https://bugs.eclipse.org/bugs/show_bug.cgi?id=386908

## Step 3

Then `./mandrel-release.java release -m <path/to/mandrel> -f <username/mandrel-fork>` must be invoked which is responsible for:

1. Creating a new GitHub release for the new tag including a changelog
2. Marking the suite.py files as non-releases
3. Bumping the version in suite.py files
4. Creating a new branch and commiting the changes there
5. Pushing the changes to a fork
6. Openning a PR
6. TODO Open PR for a new image on quarkus-images based on the new release
7. TODO send out emails to relevant lists 
8. TODO? Send a Slack message

For usage information please run `./mandrel-release.java -h`

### Changelog creation

The changelog is a list of merged PRs in the GH milestone of the version being
released that are also marked with the label `backport` or
`release/noteworthy-feature`.
