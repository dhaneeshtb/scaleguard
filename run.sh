kill -9 `cat scaleguard.pid`
nohup java -DadminUser=scaleguard -DadminPassword=Scaleguard123$ -jar scaleguard.jar > scaleguard.log & echo $! > scaleguard.pid