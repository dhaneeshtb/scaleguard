FROM amazoncorretto:11
COPY scaleguard.jar /usr/app/
WORKDIR /usr/app
EXPOSE 80
ENTRYPOINT ["java", "-jar","-Djava.security.egd=file:///dev/./urandom","-Dport=80","-Xms2G","-Xmx4G", "scaleguard.jar"]