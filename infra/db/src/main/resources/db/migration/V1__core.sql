-- Core schema and pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    tsv tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title,'')), 'A') ||
        setweight(to_tsvector('simple', coalesce(body,'')), 'B')
    ) STORED
);

CREATE TABLE IF NOT EXISTS doc_embeddings (
    doc_id UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    vec vector(1536) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_documents_tsv ON documents USING gin(tsv);
CREATE INDEX IF NOT EXISTS idx_embeddings_vec ON doc_embeddings USING ivfflat (vec vector_cosine_ops)
    WITH (lists = 200);

