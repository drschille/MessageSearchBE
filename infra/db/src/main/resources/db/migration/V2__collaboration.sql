CREATE TABLE IF NOT EXISTS collab_paragraphs (
    paragraph_id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    language_code TEXT NOT NULL,
    position INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, language_code, position)
);

CREATE TABLE IF NOT EXISTS collab_updates (
    id BIGSERIAL PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    paragraph_id UUID NOT NULL,
    client_id UUID NOT NULL,
    user_id TEXT NOT NULL,
    language_code TEXT NOT NULL,
    seq BIGINT NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, paragraph_id, client_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_collab_updates_document_paragraph_id
    ON collab_updates (document_id, paragraph_id, id);

CREATE INDEX IF NOT EXISTS idx_collab_updates_document_created_at
    ON collab_updates (document_id, created_at);

CREATE TABLE IF NOT EXISTS collab_snapshots (
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    language_code TEXT NOT NULL,
    snapshot_version BIGINT NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, language_code)
);
