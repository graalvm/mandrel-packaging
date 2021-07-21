#!/bin/bash

pushd "${WORKSPACE}" || exit 1
export MAVEN_REPO=${HOME}/.m2/repository
export MANDREL_REPO=${WORKSPACE}/mandrel
export JAVA_HOME=/usr/java/${OPENJDK}
export MX_HOME=${WORKSPACE}/mx
export PATH=${JAVA_HOME}/bin:${MX_HOME}:${PATH}
pushd mandrel
export JAVA_VERSION="$(java --version | sed -e 's/.*build \([^) ]*\)).*/\1/;t;d' )"
popd
${JAVA_HOME}/bin/java -ea build.java --maven-local-repository ${MAVEN_REPO} \
--mandrel-repo ${MANDREL_REPO} --mx-home ${MX_HOME} \
--archive-suffix tar.gz \
--verbose
TAR_NAME="$( ls mandrel-*.tar.gz )"
sha1sum ${TAR_NAME}>${TAR_NAME}.sha1
sha256sum ${TAR_NAME}>${TAR_NAME}.sha256
export MANDREL_HOME="$( find -name 'mandrel-*' -type d )"
if [[ ! -e "${MANDREL_HOME}/bin/native-image" ]]; then
  echo "Cannot find native-image tool. Quitting..."
  exit 1
fi
export MANDREL_VERSION="$( ${MANDREL_HOME}/bin/native-image --version | cut -d ' ' -f 3 )"
cat >./MANDREL.md <<EOL
This is a dev build of Mandrel from https://github.com/graalvm/mandrel.
Mandrel ${MANDREL_VERSION}
OpenJDK used: ${JAVA_VERSION}
EOL
cat >./Hello.java <<EOL
public class Hello {
public static void main(String[] args) {
    System.out.println("Hello.");
}
}
EOL
export JAVA_HOME=${MANDREL_HOME}
export PATH=${JAVA_HOME}/bin:${PATH}
javac Hello.java
native-image Hello
if [[ "`./hello`" == "Hello." ]]; then echo Done; else echo Native image fail;exit 1;fi
