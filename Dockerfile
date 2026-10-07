# ==============================================================================
# Exegese AI - Multi-Stage Production Dockerfile
# Stage 1: Build application JAR using Maven and JDK
# Stage 2: Hardened, Non-Root Lightweight JRE Runtime
# ==============================================================================

# Stage 1: Builder
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /workspace

# Install Maven in build stage
RUN apt-get update && \
    apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/*

# Cache dependency layer
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build production package
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Production Runtime
FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

# Install curl for container HEALTHCHECK
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Create unprivileged system group and user
RUN groupadd -g 10001 appgroup && \
    useradd -u 10001 -g appgroup -s /bin/sh -d /app appuser && \
    mkdir -p /app/uploads /app/storage && \
    chown -R appuser:appgroup /app

# Copy artifact from builder stage
COPY --from=builder --chown=appuser:appgroup /workspace/target/exegese-ai-1.0.0-SNAPSHOT.jar app.jar

# Run as non-root user
USER 10001:10001

# Production JVM configuration
ENV JVM_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8"
ENV PORT=8080

EXPOSE 8080

# Container health monitoring
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JVM_OPTS -jar app.jar"]
