# ─────────────────────────────────────────────────────────────
# ScaleGuard Dockerfile
# Supports two build modes:
#
#   1. JVM mode (default):
#      docker build -t scaleguard .
#
#   2. GraalVM Native Image (fast startup, low memory):
#      docker build --build-arg BUILD_MODE=native -t scaleguard-native .
# ─────────────────────────────────────────────────────────────

ARG BUILD_MODE=jvm

# ── Stage 1: Build ───────────────────────────────────────────
FROM ghcr.io/graalvm/graalvm-community:21 AS build

WORKDIR /build

# Install Maven
RUN microdnf install -y maven findutils && microdnf clean all

COPY pom.xml .
COPY src ./src

ARG BUILD_MODE

RUN if [ "$BUILD_MODE" = "native" ]; then \
      echo "Building native image (this takes ~5 minutes)..." && \
      mvn -Pnative -DskipTests clean package -B; \
    else \
      echo "Building JVM fat JAR..." && \
      mvn -DskipTests clean package -B; \
    fi

# ── Stage 2a: JVM Runtime ────────────────────────────────────
FROM ghcr.io/graalvm/graalvm-community:21 AS jvm-runtime

LABEL maintainer="dhaneeshtnair@gmail.com"
LABEL description="ScaleGuard - High-performance load balancer (JVM mode, JDK 21 + virtual threads)"

WORKDIR /usr/app
COPY --from=build /build/target/scaleguard-1.0-SNAPSHOT.jar scaleguard.jar

EXPOSE 80 443 443/udp 2222

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:80/healthz || exit 1

ENV SCALEGUARD_ADMIN_USER=""
ENV SCALEGUARD_ADMIN_PASSWORD=""

ENTRYPOINT ["java", \
  "-jar", \
  # Enable virtual thread carrier thread pinning diagnostics
  "-Djdk.tracePinnedThreads=short", \
  # Use G1GC for low-latency GC pauses
  "-XX:+UseG1GC", \
  # Reduce GC pause target
  "-XX:MaxGCPauseMillis=50", \
  # Tune for high concurrency
  "-Dio.netty.leakDetection.level=SIMPLE", \
  "-Djava.security.egd=file:///dev/./urandom", \
  "-Dport=80", \
  "-Xms512M", \
  "-Xmx2G", \
  "scaleguard.jar"]

# ── Stage 2b: Native Runtime ─────────────────────────────────
FROM ubuntu:22.04 AS native-runtime

LABEL maintainer="dhaneeshtnair@gmail.com"
LABEL description="ScaleGuard - High-performance load balancer (GraalVM native image)"

RUN apt-get update && apt-get install -y curl libz-dev && rm -rf /var/lib/apt/lists/*

WORKDIR /usr/app
COPY --from=build /build/target/scaleguard ./scaleguard

EXPOSE 80 443 443/udp 2222

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:80/healthz || exit 1

ENV SCALEGUARD_ADMIN_USER=""
ENV SCALEGUARD_ADMIN_PASSWORD=""

# Native binary: minimal flags needed — no JVM startup overhead
ENTRYPOINT ["./scaleguard", \
  "-Dport=80", \
  "-Djava.security.egd=file:///dev/./urandom"]

# ── Final stage: select JVM or native based on BUILD_MODE ────
FROM ${BUILD_MODE}-runtime AS final