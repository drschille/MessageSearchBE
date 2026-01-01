-- Core schema and pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    language_code TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(body,'')), 'B')
    ) STORED
);

CREATE TABLE IF NOT EXISTS document_paragraphs (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    language_code TEXT NOT NULL,
    position INT NOT NULL,
    heading TEXT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(heading,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(body,'')), 'B')
    ) STORED
);

CREATE TABLE IF NOT EXISTS paragraph_embeddings (
    paragraph_id UUID PRIMARY KEY REFERENCES document_paragraphs(id) ON DELETE CASCADE,
    vec vector(1536) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_documents_tsv ON documents USING gin(tsv);
CREATE INDEX IF NOT EXISTS idx_paragraphs_tsv ON document_paragraphs USING gin(tsv);
CREATE INDEX IF NOT EXISTS idx_paragraph_embeddings_vec ON paragraph_embeddings USING ivfflat (vec vector_cosine_ops)
    WITH (lists = 200);
