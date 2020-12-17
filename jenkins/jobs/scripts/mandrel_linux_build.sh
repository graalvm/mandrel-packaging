#!/bin/bash

pushd "${WORKSPACE}" || exit 1
export MAVEN_REPO=${HOME}/.m2/repository
export MANDREL_REPO=${WORKSPACE}/mandrel
export JAVA_HOME=/usr/java/${OPENJDK}
export MX_HOME=${WORKSPACE}/mx
export PATH=${JAVA_HOME}/bin:${MX_HOME}:${PATH}
pushd mandrel
export MANDREL_VERSION="${MANDREL_VERSION_SUBSTRING} $( git log --pretty=format:%h -n1)"
export MANDREL_VERSION_UNTIL_SPACE="$( echo ${MANDREL_VERSION} | sed -e 's/\([^ ]*\).*/\1/;t' )"
export JAVA_VERSION="$(java --version | sed -e 's/.*build \([^) ]*\)).*/\1/;t;d' )"
export MANDREL_HOME=${WORKSPACE}/mandrel-java11-${MANDREL_VERSION_UNTIL_SPACE}
popd
${JAVA_HOME}/bin/java -ea build.java --maven-local-repository ${MAVEN_REPO} \
--mandrel-repo ${MANDREL_REPO} --mx-home ${MX_HOME} \
--mandrel-version ${MANDREL_VERSION} --mandrel-home ${MANDREL_HOME} \
--archive-suffix tar.gz
TAR_NAME="$( ls mandrel-*.tar.gz )"
sha1sum ${TAR_NAME}>${TAR_NAME}.sha1
sha256sum ${TAR_NAME}>${TAR_NAME}.sha256
cat >./MANDREL.md <<EOL
This is a dev build of Mandrel from https://github.com/graalvm/mandrel.
Mandrel ${MANDREL_VERSION}
OpenJDK used: ${JAVA_VERSION}
EOL
if [[ ! -e "${MANDREL_HOME}/bin/native-image" ]]; then
  echo "Cannot find native-image tool. Quitting..."
  exit 1
fi
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
