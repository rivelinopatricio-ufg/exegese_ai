-- ==============================================================================
-- EXEGESE AI - V2: chunk uniqueness per document
-- The same text (same chunk hash) may now be indexed in several documents: the global UNIQUE on
-- exegese_chunk.chunk_hash_sha256 is replaced by UNIQUE (document_id, chunk_hash_sha256).
-- Idempotent: the old constraint/index may have been created by init-schema.sql (exegese_chunk_chunk_hash_sha256_key),
-- by Hibernate ddl-auto=update (generated "uk..." name) or not exist at all.
-- ==============================================================================

DO $$
DECLARE
    hash_attnum SMALLINT;
    item RECORD;
BEGIN
    SELECT a.attnum INTO hash_attnum
    FROM pg_attribute a
    WHERE a.attrelid = 'exegese_chunk'::regclass
      AND a.attname = 'chunk_hash_sha256'
      AND NOT a.attisdropped;

    -- Single-column UNIQUE constraints on chunk_hash_sha256 (whatever their generated name)
    FOR item IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.conrelid = 'exegese_chunk'::regclass
          AND con.contype = 'u'
          AND con.conkey = ARRAY[hash_attnum]
    LOOP
        EXECUTE format('ALTER TABLE exegese_chunk DROP CONSTRAINT %I', item.conname);
    END LOOP;

    -- Standalone single-column unique indexes on chunk_hash_sha256 (not backing a constraint)
    FOR item IN
        SELECT i.indexrelid::regclass::text AS index_name
        FROM pg_index i
        WHERE i.indrelid = 'exegese_chunk'::regclass
          AND i.indisunique
          AND NOT i.indisprimary
          AND i.indnkeyatts = 1
          AND i.indkey[0] = hash_attnum
          AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)
    LOOP
        EXECUTE format('DROP INDEX %s', item.index_name);
    END LOOP;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'exegese_chunk'::regclass
          AND conname = 'uk_exegese_chunk_document_hash'
    ) THEN
        ALTER TABLE exegese_chunk
            ADD CONSTRAINT uk_exegese_chunk_document_hash UNIQUE (document_id, chunk_hash_sha256);
    END IF;
END $$;
