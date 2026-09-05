-- Adopted from flowcatalyst-go internal/migrate/sql/046_oauth_client_secret_grace.sql
-- Adds an overlap window for OAuth client secret rotation: previous_secret_ref
-- and previous_secret_expires_at let verification accept the superseded
-- secret until it lapses, instead of a hard cutover. Additive and nullable.

ALTER TABLE oauth_clients
    ADD COLUMN IF NOT EXISTS previous_secret_ref TEXT,
    ADD COLUMN IF NOT EXISTS previous_secret_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_oauth_clients_previous_secret_expires_at
    ON oauth_clients (previous_secret_expires_at)
    WHERE previous_secret_ref IS NOT NULL;
