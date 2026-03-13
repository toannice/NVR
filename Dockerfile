# 1. Build Frontend
FROM node:22-alpine AS frontend-builder
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm install
COPY frontend/ .
RUN npm run build

# 2. Build Backend (Kotlin/Ktor)
FROM eclipse-temurin:21-jdk-alpine AS backend-builder
WORKDIR /app/backend
# Copy gradle wrapper and config first for caching
COPY backend/gradlew backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
# Setup wrapper permissions and pre-fetch dependencies (optional optimization)
RUN apk add --no-cache bash
RUN chmod +x gradlew
# Now copy source code and build
COPY backend/src ./src
RUN ./gradlew installDist --no-daemon

# 3. Final Runtime Image (JRE only to save space)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install tools for healthchecks and operations
RUN apk add --no-cache bash docker-cli sqlite curl wget

# Copy Frontend Build from the frontend-builder stage
COPY --from=frontend-builder /app/frontend/dist ./frontend/dist

# Copy Backend fat distribution from the backend-builder stage
COPY --from=backend-builder /app/backend/build/install/nvr-wrapper-backend ./backend

EXPOSE 3000

# Run the Ktor application
CMD ["./backend/bin/nvr-wrapper-backend"]
