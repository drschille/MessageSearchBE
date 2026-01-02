CREATE TABLE IF NOT EXISTS snapshots (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    version BIGINT NOT NULL,
    state TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    language_code TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID NOT NULL,
    source_draft_id UUID NULL,
    source_revision TEXT NULL
);

CREATE INDEX IF NOT EXISTS idx_snapshots_document_created_at ON snapshots(document_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_snapshots_document_version ON snapshots(document_id, version DESC);

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS snapshot_id UUID NULL REFERENCES snapshots(id);

CREATE INDEX IF NOT EXISTS idx_documents_snapshot_id ON documents(snapshot_id);

CREATE TABLE IF NOT EXISTS document_audits (
    audit_id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL,
    action TEXT NOT NULL,
    reason TEXT NULL,
    from_state TEXT NULL,
    to_state TEXT NULL,
    snapshot_id UUID NULL REFERENCES snapshots(id),
    diff_summary TEXT NULL,
    request_id TEXT NULL,
    ip_fingerprint TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_document_audits_document_created_at ON document_audits(document_id, created_at DESC);
