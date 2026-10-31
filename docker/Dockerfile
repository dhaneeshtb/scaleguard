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

# ── Stage 0: admin UI (served by the JAR at /adminui; the built UI is not kept in git) ──
FROM node:20-alpine AS ui
WORKDIR /ui/admin-ui
COPY admin-ui/package.json admin-ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY admin-ui/ ./
RUN sh build.sh

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

RUN microdnf install -y curl && microdnf clean all

WORKDIR /usr/app
COPY --from=build /build/target/scaleguard-1.0-SNAPSHOT.jar scaleguard.jar
COPY --from=ui /ui/adminui ./adminui
COPY docker/entrypoint.sh /usr/app/entrypoint.sh
RUN chmod +x /usr/app/entrypoint.sh

# 80/443 proxy (443/udp = HTTP/3), 2222 = SafeExpose tunnels, 53 = DNS (only when the DNS server is switched on)
EXPOSE 80 443 443/udp 2222 53/udp 53/tcp

# configuration, certificates, DuckDB cache + analytics
VOLUME /data
ENV SCALEGUARD_DATA=/data

# /info is public and answers as soon as the server is up (/healthz is 503 until a backend exists)
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS "http://localhost:${SCALEGUARD_HTTP_PORT:-80}/info" >/dev/null || exit 1

# Settings come from the environment (see docker/entrypoint.sh and .env.example). The admin password is required.
ENTRYPOINT ["/usr/app/entrypoint.sh"]

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