CREATE TABLE IF NOT EXISTS user_password_resets (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_password_resets_token_hash
    ON user_password_resets (token_hash);

CREATE INDEX IF NOT EXISTS idx_user_password_resets_user_created_at
    ON user_password_resets (user_id, created_at DESC);
