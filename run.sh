kill -9 `cat scaleguard.pid`
nohup java -DadminUser=scaleguard -DadminPassword=Scaleguard123$ -jar target/scaleguard-1.0-SNAPSHOT.jar > scaleguard.log & echo $! > scaleguard.pid