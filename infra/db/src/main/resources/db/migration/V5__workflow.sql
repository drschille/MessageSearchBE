ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS workflow_state TEXT NOT NULL DEFAULT 'published';

UPDATE documents
SET workflow_state = 'published'
WHERE workflow_state IS NULL;

CREATE TABLE IF NOT EXISTS document_reviews (
    review_id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    status TEXT NOT NULL,
    summary TEXT NOT NULL,
    reviewers TEXT NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_document_reviews_document_id ON document_reviews(document_id);

CREATE TABLE IF NOT EXISTS review_comments (
    comment_id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES document_reviews(review_id) ON DELETE CASCADE,
    author_id UUID NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_review_comments_review_id ON review_comments(review_id);
