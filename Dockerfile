# --- Build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
# Build the executable fat jar (target/app.jar).
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/app.jar ./app.jar
COPY frontend ./frontend

ENV SHAREDOC_FRONTEND_DIR=/app/frontend \
    SHAREDOC_DOCUMENT_DIR=/data/documents \
    SHAREDOC_VERSION_DIR=/data/versions \
    SHAREDOC_METADATA_DIR=/data/metadata

# Persist documents, versions and metadata across container restarts.
VOLUME ["/data"]
EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:8082/api/v1/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
