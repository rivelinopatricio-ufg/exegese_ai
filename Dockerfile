# ==============================================================================
# Exegese AI - Multi-Stage Production Dockerfile
# Stage 1: Build the application JAR with the committed Maven Wrapper (JDK 25) and
#          extract it into Spring Boot layers (dependencies first, application last)
# Stage 2: Hardened, non-root JRE 25 runtime (no shell wrapper, JVM options via JAVA_TOOL_OPTIONS)
# The Tailwind CSS (src/main/resources/static/css/tailwind.css) is committed, so no Node stage is needed.
# ==============================================================================

# Stage 1: Builder
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /workspace

# unzip lets the Maven Wrapper download the .zip distribution whose SHA-256 is pinned in
# .mvn/wrapper/maven-wrapper.properties (without unzip it would switch to the .tar.gz and fail the checksum)
RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip && \
    rm -rf /var/lib/apt/lists/*

# Dependency cache layer: rebuilt only when the wrapper or pom.xml change
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

# Copy source code and build the production package (tests run in CI: ./mvnw -B verify)
COPY src ./src
RUN ./mvnw -B package -DskipTests

# Extract the executable JAR into layers (spring-boot-jarmode-tools): application/app.jar plus
# dependencies/lib/*.jar, so a code change only rebuilds the small application layer
RUN cp target/exegese-ai-*.jar app.jar && \
    java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Stage 2: Production Runtime
FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

# Install curl for container HEALTHCHECK
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Create unprivileged system group and user. The upload/storage directories are created here so the
# named volumes mounted on them are initialized with this ownership (the root filesystem is read-only in compose).
RUN groupadd -g 10001 appgroup && \
    useradd -u 10001 -g appgroup -s /usr/sbin/nologin -d /app appuser && \
    mkdir -p /app/uploads /app/storage && \
    chown -R appuser:appgroup /app/uploads /app/storage

# Spring Boot layers, from the least to the most frequently changed (read-only for the app user)
COPY --from=builder /workspace/extracted/dependencies/ ./
COPY --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/extracted/application/ ./

# Run as non-root user
USER 10001:10001

# Production JVM configuration (read by the JVM itself, so the exec-form ENTRYPOINT needs no shell).
# Generational ZGC is the only ZGC mode since JDK 24 (JEP 490), so -XX:+ZGenerational is no longer used.
# Heap sized from the container memory limit; temporary files (multipart uploads, PDFBox stream cache
# and font cache) go to /tmp, which compose mounts as tmpfs on top of the read-only root filesystem.
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp -Dpdfbox.fontcache=/tmp"
ENV SPRING_PROFILES_ACTIVE=prod
ENV PORT=8080

EXPOSE 8080

# Container health monitoring
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
