# Build stage
FROM amazoncorretto:11 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN yum install -y maven && mvn clean package -DskipTests -B

# Runtime stage
FROM amazoncorretto:11-alpine
LABEL maintainer="dhaneeshtnair@gmail.com"
LABEL description="ScaleGuard - High-performance load balancer and reverse proxy"

WORKDIR /usr/app

COPY --from=build /build/target/scaleguard-1.0-SNAPSHOT.jar scaleguard.jar

# HTTP, HTTPS, SSH tunnel ports
EXPOSE 80 443 2222

# Health check using the built-in /healthz endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:80/healthz || exit 1

# Admin credentials should be provided via environment variables
ENV SCALEGUARD_ADMIN_USER=""
ENV SCALEGUARD_ADMIN_PASSWORD=""

ENTRYPOINT ["java", \
  "-jar", \
  "-Djava.security.egd=file:///dev/./urandom", \
  "-Dport=80", \
  "-Xms512M", \
  "-Xmx2G", \
  "scaleguard.jar"]