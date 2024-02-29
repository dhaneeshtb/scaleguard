$MAVEN_HOME/bin/mvn clean install -DskipTests
mkdir -p build
cp -r conf/$1/*.properties build/
cp target/reverse-proxy-1.0-SNAPSHOT.jar build/scaleguard.jar

