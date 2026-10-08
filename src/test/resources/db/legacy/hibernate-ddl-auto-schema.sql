-- ==============================================================================
-- TEST FIXTURE: legacy schema created by Hibernate ddl-auto=update (entities before Flyway) plus the former
-- DatabaseSchemaInitializer (extensions, embedding/tsv columns, specialized indexes).
-- Generated with pg_dump --schema-only from a database created by the application before Flyway; used by
-- FlywayMigrationContainerTest to check that V2+ migrate such databases (generated "uk..." constraint
-- names, enum CHECK constraints, FKs without ON DELETE CASCADE).
-- ==============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE public.ai_model_config (
    id uuid NOT NULL,
    is_active boolean NOT NULL,
    api_key_encrypted character varying(500),
    base_url character varying(255),
    display_name character varying(100) NOT NULL,
    is_default boolean NOT NULL,
    max_tokens integer NOT NULL,
    model_name character varying(100) NOT NULL,
    provider character varying(50) NOT NULL,
    temperature numeric(3,2) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ai_model_config_provider_check CHECK (((provider)::text = ANY ((ARRAY['GEMINI'::character varying, 'CLAUDE'::character varying, 'OPENAI'::character varying, 'NEMOTRON'::character varying, 'DEEPSEEK'::character varying, 'OLLAMA_LOCAL'::character varying, 'CEREBRAS'::character varying])::text[])))
);

CREATE TABLE public.chat_message (
    id uuid NOT NULL,
    applied_subject_ids jsonb,
    citations jsonb,
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    execution_duration_ms integer,
    model_used character varying(100),
    role character varying(20) NOT NULL,
    session_id uuid NOT NULL
);

CREATE TABLE public.chat_session (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    title character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE public.document_subject (
    document_id uuid NOT NULL,
    subject_id uuid NOT NULL
);

CREATE TABLE public.exegese_chunk (
    id uuid NOT NULL,
    chunk_hash_sha256 character varying(64) NOT NULL,
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    metadata jsonb NOT NULL,
    sequence_number integer NOT NULL,
    title character varying(500),
    document_id uuid NOT NULL,
    embedding public.vector(768),
    tsv tsvector GENERATED ALWAYS AS (to_tsvector('portuguese'::regconfig, (((COALESCE(title, ''::character varying))::text || ' '::text) || content))) STORED
);

CREATE TABLE public.exegese_document (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    error_message text,
    file_hash_sha256 character varying(64) NOT NULL,
    file_size bigint NOT NULL,
    file_type character varying(50) NOT NULL,
    original_file_name character varying(255) NOT NULL,
    segmentation_strategy character varying(50) NOT NULL,
    status character varying(50) NOT NULL,
    storage_path character varying(500) NOT NULL,
    title character varying(255) NOT NULL,
    total_pages integer,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT exegese_document_segmentation_strategy_check CHECK (((segmentation_strategy)::text = ANY ((ARRAY['STRUCTURED_QA'::character varying, 'LEGAL_SECTION'::character varying, 'RECURSIVE'::character varying])::text[])))
);

CREATE TABLE public.exegese_subject (
    id uuid NOT NULL,
    active boolean NOT NULL,
    code character varying(64) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    description text,
    name character varying(255) NOT NULL
);

CREATE TABLE public.exegese_user (
    id uuid NOT NULL,
    active boolean NOT NULL,
    avatar_url character varying(500),
    created_at timestamp(6) with time zone NOT NULL,
    email character varying(255) NOT NULL,
    last_login_at timestamp(6) with time zone,
    name character varying(255) NOT NULL,
    role character varying(50) NOT NULL,
    CONSTRAINT exegese_user_role_check CHECK (((role)::text = ANY ((ARRAY['ROLE_ADMIN'::character varying, 'ROLE_OPERATOR'::character varying, 'ROLE_USER'::character varying])::text[])))
);

CREATE TABLE public.user_subject_permission (
    granted_at timestamp(6) with time zone NOT NULL,
    permission_level character varying(50) NOT NULL,
    subject_id uuid NOT NULL,
    user_id uuid NOT NULL
);

ALTER TABLE ONLY public.ai_model_config
    ADD CONSTRAINT ai_model_config_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT chat_message_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.chat_session
    ADD CONSTRAINT chat_session_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.document_subject
    ADD CONSTRAINT document_subject_pkey PRIMARY KEY (document_id, subject_id);

ALTER TABLE ONLY public.exegese_chunk
    ADD CONSTRAINT exegese_chunk_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.exegese_document
    ADD CONSTRAINT exegese_document_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.exegese_subject
    ADD CONSTRAINT exegese_subject_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.exegese_user
    ADD CONSTRAINT exegese_user_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.exegese_document
    ADD CONSTRAINT uk3fu01ge5doufo7vchkc1cny80 UNIQUE (file_hash_sha256);

ALTER TABLE ONLY public.ai_model_config
    ADD CONSTRAINT uk5nfijg3oa0hydu36wwx2rtf7d UNIQUE (provider);

ALTER TABLE ONLY public.exegese_chunk
    ADD CONSTRAINT uka56d77jqoahij1rev0mttnpdc UNIQUE (chunk_hash_sha256);

ALTER TABLE ONLY public.exegese_user
    ADD CONSTRAINT ukrejkl61hwhs5934wa6h2pocux UNIQUE (email);

ALTER TABLE ONLY public.exegese_subject
    ADD CONSTRAINT uktmdpf9aph8nhngvcp1norjkk3 UNIQUE (code);

ALTER TABLE ONLY public.user_subject_permission
    ADD CONSTRAINT user_subject_permission_pkey PRIMARY KEY (subject_id, user_id);

CREATE INDEX idx_doc_subject_subject ON public.document_subject USING btree (subject_id);

CREATE INDEX idx_exegese_chunk_doc ON public.exegese_chunk USING btree (document_id);

CREATE INDEX idx_exegese_chunk_hnsw ON public.exegese_chunk USING hnsw (embedding public.vector_cosine_ops) WITH (m='16', ef_construction='64');

CREATE INDEX idx_exegese_chunk_metadata ON public.exegese_chunk USING gin (metadata jsonb_path_ops);

CREATE INDEX idx_exegese_chunk_tsv ON public.exegese_chunk USING gin (tsv);

ALTER TABLE ONLY public.user_subject_permission
    ADD CONSTRAINT fk9cddjchfv10tos7ftdd7kckpd FOREIGN KEY (user_id) REFERENCES public.exegese_user(id);

ALTER TABLE ONLY public.chat_message
    ADD CONSTRAINT fkgtjn4wh83q8ohixkwcotpoob9 FOREIGN KEY (session_id) REFERENCES public.chat_session(id);

ALTER TABLE ONLY public.document_subject
    ADD CONSTRAINT fkk31ntskuga40g2g70tllfx5wm FOREIGN KEY (subject_id) REFERENCES public.exegese_subject(id);

ALTER TABLE ONLY public.chat_session
    ADD CONSTRAINT fkn0bse2dknlm59u5yl60ujd3u6 FOREIGN KEY (user_id) REFERENCES public.exegese_user(id);

ALTER TABLE ONLY public.user_subject_permission
    ADD CONSTRAINT fkq0tbmwd5nywtuultslpum4aq7 FOREIGN KEY (subject_id) REFERENCES public.exegese_subject(id);

ALTER TABLE ONLY public.document_subject
    ADD CONSTRAINT fkqjqra2x1vl193heoffggghrme FOREIGN KEY (document_id) REFERENCES public.exegese_document(id);

ALTER TABLE ONLY public.exegese_chunk
    ADD CONSTRAINT fkr6ccx3ee55s5hom8xfsthwg0n FOREIGN KEY (document_id) REFERENCES public.exegese_document(id);

