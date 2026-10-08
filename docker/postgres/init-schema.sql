-- ==============================================================================
-- EXEGESE AI - DATABASE INITIALIZATION (PostgreSQL 17 + pgvector)
-- Auto-executed on first container initialization (empty data volume) by the standalone database image
-- (docker/postgres/Dockerfile).
--
-- Only the extensions are created here, because creating them may require superuser privileges.
-- The schema itself (tables, constraints, indexes) is owned by Flyway: the application applies
-- src/main/resources/db/migration/V*.sql on startup. Do not add DDL to this file.
-- ==============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";
