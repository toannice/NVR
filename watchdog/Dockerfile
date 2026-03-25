# ─── Stage 1: Build the fat JAR ───────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

RUN apk add --no-cache bash

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew

# Copy source and build
COPY src ./src
RUN ./gradlew jar --no-daemon

# ─── Stage 2: Minimal runtime image ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install docker-cli (to call docker commands on the host via mounted socket)
RUN apk add --no-cache docker-cli curl tzdata

COPY --from=builder /app/build/libs/*.jar nvr-watchdog.jar

# Default data directories
RUN mkdir -p /data/reports /logs

ENTRYPOINT ["java", "-jar", "/app/nvr-watchdog.jar"]
