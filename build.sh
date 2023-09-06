$MAVEN_HOME/bin/mvn clean install -DskipTests
mkdir -p build
cp -r *.properties build/
cp target/reverse-proxy-1.0-SNAPSHOT.jar build/scaleguard.jar

