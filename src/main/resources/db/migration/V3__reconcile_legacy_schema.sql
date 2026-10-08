-- ==============================================================================
-- EXEGESE AI - V3: reconcile databases created before Flyway
-- Databases created by docker/postgres/init-schema.sql and/or Hibernate ddl-auto=update (plus the former
-- DatabaseSchemaInitializer) differ from V1 in a few points that matter to the application. Every statement
-- is idempotent, so on a database created by V1 this migration changes nothing.
-- ==============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- Vector and full-text columns are not mapped by JPA: a Hibernate-created table may lack them
ALTER TABLE exegese_chunk ADD COLUMN IF NOT EXISTS embedding VECTOR(768);
ALTER TABLE exegese_chunk ADD COLUMN IF NOT EXISTS tsv TSVECTOR GENERATED ALWAYS AS (
    to_tsvector('portuguese', coalesce(title, '') || ' ' || content)
) STORED;

-- init-schema.sql declared the vector NOT NULL; it must accept NULL until ingestion/reindex computes it
ALTER TABLE exegese_chunk ALTER COLUMN embedding DROP NOT NULL;

-- Not mapped by JPA either (Hibernate-created join table)
ALTER TABLE document_subject ADD COLUMN IF NOT EXISTS tagged_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- Hibernate (ddl-auto) generated CHECK constraints listing the enum values known at that time; they would
-- reject values added later (for example a new LLM provider), so the application enums are the only check
ALTER TABLE exegese_user DROP CONSTRAINT IF EXISTS exegese_user_role_check;
ALTER TABLE ai_model_config DROP CONSTRAINT IF EXISTS ai_model_config_provider_check;
ALTER TABLE exegese_document DROP CONSTRAINT IF EXISTS exegese_document_segmentation_strategy_check;

-- Specialized indexes (also created by init-schema.sql / the former DatabaseSchemaInitializer)
CREATE INDEX IF NOT EXISTS idx_exegese_chunk_hnsw
ON exegese_chunk USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_exegese_chunk_tsv
ON exegese_chunk USING gin (tsv);

CREATE INDEX IF NOT EXISTS idx_exegese_chunk_metadata
ON exegese_chunk USING gin (metadata jsonb_path_ops);

CREATE INDEX IF NOT EXISTS idx_exegese_chunk_doc
ON exegese_chunk (document_id);

CREATE INDEX IF NOT EXISTS idx_doc_subject_subject
ON document_subject (subject_id);

-- Foreign-key lookups used by the chat history and the admin screens
CREATE INDEX IF NOT EXISTS idx_chat_session_user
ON chat_session (user_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_chat_message_session
ON chat_message (session_id, created_at);

CREATE INDEX IF NOT EXISTS idx_user_subject_permission_subject
ON user_subject_permission (subject_id);
