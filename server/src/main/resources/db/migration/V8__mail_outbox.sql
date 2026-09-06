-- Mail leaves the request path (owner ruling 2026-09-06, docs/spec/mail-outbox.md §2).
-- A brand-new table Go never sees — additive per docs/database.md's ground rules,
-- no schema Go reads or writes is touched.
--
-- OutboxMailService inserts one PENDING row per outbound message from inside the
-- request path (a short insert-only transaction); the background MailSender claims
-- due rows FOR UPDATE SKIP LOCKED and delivers them off the request path entirely.
--
-- status: PENDING (queued, retryable) | SENT (delivered) | FAILED (terminal, gave
-- up after the retry ladder). The (status, next_attempt_at) index backs the
-- sender's claim predicate `status = 'PENDING' AND next_attempt_at <= now()`.
CREATE TABLE mail_outbox (
    id text PRIMARY KEY,
    to_addr text NOT NULL,
    subject text NOT NULL,
    html text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz
);

CREATE INDEX idx_mail_outbox_status_next_attempt_at ON mail_outbox (status, next_attempt_at);
