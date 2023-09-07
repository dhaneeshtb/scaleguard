$MAVEN_HOME/bin/mvn clean install -DskipTests
mkdir -p build
cp -r conf/prod/*.properties build/
cp target/reverse-proxy-1.0-SNAPSHOT.jar build/scaleguard.jar

