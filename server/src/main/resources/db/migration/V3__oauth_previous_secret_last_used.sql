-- Adopted from flowcatalyst-go internal/migrate/sql/047_oauth_previous_secret_last_used.sql
-- Observability for the secret-rotation overlap (V2): previous_secret_last_used_at
-- is stamped when a client authenticates with the superseded secret, so it is
-- possible to tell whether it is still safe to revoke early. Additive and nullable.

ALTER TABLE oauth_clients
    ADD COLUMN IF NOT EXISTS previous_secret_last_used_at TIMESTAMPTZ;
