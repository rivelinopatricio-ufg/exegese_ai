# ==============================================================================
# Exegese AI - Remote PostgreSQL 17 + pgvector Container
# Base image includes PostgreSQL 17 with the pgvector extension pre-compiled
# ==============================================================================

FROM pgvector/pgvector:pg17

LABEL maintainer="Rivelino Patrício <admin@exegese.ai>"
LABEL description="Exegese AI Database with pgvector, uuid-ossp, and auto-initialization"

# Default environment configuration
ENV POSTGRES_DB=exegese_db \
    POSTGRES_USER=exegese_user \
    POSTGRES_PASSWORD=exegese_password

# Copy initial schema script into Docker's entrypoint initialization directory.
# Scripts in this directory execute automatically on container first startup (when data volume is initialized).
COPY init-schema.sql /docker-entrypoint-initdb.d/01-init-schema.sql

# Expose standard PostgreSQL port to external network
EXPOSE 5432

# Health check to ensure PostgreSQL is accepting connections
HEALTHCHECK --interval=5s --timeout=5s --retries=5 \
    CMD pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" || exit 1
