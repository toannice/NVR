# syntax=docker/dockerfile:1
# BuildKit cache mounts (the `--mount=type=cache` lines below) persist npm/gradle
# download caches BETWEEN builds, so deps aren't re-fetched every time. Requires
# BuildKit (default on modern Docker). See CLAUDE.md "Build & Deploy" for the fast loop.

# 1. Build Frontend
FROM node:22-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
# Cache ~/.npm so re-installs reuse downloaded packages.
RUN --mount=type=cache,target=/root/.npm npm install
COPY frontend/ .
RUN npm run build

# 2. Build Backend (Kotlin/Ktor)
FROM eclipse-temurin:21-jdk-alpine AS backend-builder
WORKDIR /app/backend
# Copy gradle wrapper and config first for caching
COPY backend/gradlew backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
RUN apk add --no-cache bash
RUN chmod +x gradlew
# Now copy source code and build.
# Cache /root/.gradle (dependency downloads + build cache) across builds → only changed
# Kotlin recompiles instead of re-resolving every dependency. --no-daemon: no persistent
# daemon inside the ephemeral build container (the cache mount is what gives the speedup).
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew installDist --no-daemon

# 3. Final Runtime Image (JRE only to save space)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Build-time version labels (injected by GitHub Actions)
ARG DEPLOY_VERSION=dev
ARG APP_VERSION=1.2.0
ENV DEPLOY_VERSION=${DEPLOY_VERSION}
ENV APP_VERSION=${APP_VERSION}

# Install tools for healthchecks and operations
RUN apk add --no-cache bash docker-cli sqlite curl wget

# Copy Frontend Build from the frontend-builder stage
COPY --from=frontend-builder /app/frontend/dist ./frontend/dist

# Copy Backend fat distribution from the backend-builder stage
COPY --from=backend-builder /app/backend/build/install/nvr-wrapper-backend ./backend

EXPOSE 3000

# Run the Ktor application
CMD ["./backend/bin/nvr-wrapper-backend"]
