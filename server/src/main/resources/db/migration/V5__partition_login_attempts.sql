-- Adopted from flowcatalyst-go internal/migrate/sql/049_partition_login_attempts.sql
-- Range-partitions iam_login_attempts by quarter on attempted_at: builds the
-- partitioned replacement, copies every existing row into it, then swaps
-- names in. Idempotent: guarded by a check that the table is not already
-- partitioned, so it is a no-op on a Go database that already applied it.

DO $migration049$
DECLARE
    already_partitioned boolean;
    min_ts       TIMESTAMPTZ;
    upper_bound  TIMESTAMPTZ;
    q_start      TIMESTAMPTZ;
    partition_name TEXT;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM pg_partitioned_table pt
        JOIN pg_class c ON c.oid = pt.partrelid
        WHERE c.relname = 'iam_login_attempts'
    ) INTO already_partitioned;

    IF already_partitioned THEN
        RAISE NOTICE 'Migration 049: iam_login_attempts is already partitioned; skipping.';
        RETURN;
    END IF;

    -- ─── Build the partitioned replacement ─────────────────────────────────
    CREATE TABLE iam_login_attempts_new (
        id             VARCHAR(17)  NOT NULL,
        attempt_type   VARCHAR(30)  NOT NULL,
        outcome        VARCHAR(20)  NOT NULL,
        failure_reason VARCHAR(100),
        identifier     VARCHAR(255),
        principal_id   VARCHAR(17),
        ip_address     VARCHAR(45),
        user_agent     TEXT,
        attempted_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (id, attempted_at)
    ) PARTITION BY RANGE (attempted_at);

    -- Working names to avoid colliding with the old table's indexes of the
    -- same name; renamed to the canonical names below once the old table
    -- (and its indexes) are gone. Index creation on a partitioned parent
    -- auto-propagates to every partition — existing and future.
    CREATE INDEX idx_iam_login_attempts_np_identifier_at
        ON iam_login_attempts_new (identifier, attempted_at);
    CREATE INDEX idx_iam_login_attempts_np_identifier_ip_at
        ON iam_login_attempts_new (identifier, ip_address, attempted_at);
    CREATE INDEX idx_iam_login_attempts_np_type
        ON iam_login_attempts_new (attempt_type);
    CREATE INDEX idx_iam_login_attempts_np_outcome
        ON iam_login_attempts_new (outcome);
    CREATE INDEX idx_iam_login_attempts_np_principal
        ON iam_login_attempts_new (principal_id);
    CREATE INDEX idx_iam_login_attempts_np_failure_throttle
        ON iam_login_attempts_new (identifier, attempted_at) WHERE outcome = 'FAILURE';
    -- attempted_at alone, for the admin list's unfiltered ORDER BY
    -- attempted_at DESC / DateFrom-DateTo range scan (loginattempt.FindPage).
    -- The bare identifier-only index from migration 008 is dropped as
    -- redundant — it's now a strict prefix of the composite above, same
    -- reasoning migration 037 used for the messaging-table singles.
    CREATE INDEX idx_iam_login_attempts_np_at
        ON iam_login_attempts_new (attempted_at);

    -- ─── Quarterly partitions: earliest existing row through the current
    --     quarter + the next one ─────────────────────────────────────────
    SELECT date_trunc('quarter', COALESCE(MIN(attempted_at), NOW()))
      INTO min_ts
      FROM iam_login_attempts;

    upper_bound := date_trunc('quarter', NOW()) + INTERVAL '6 months'; -- exclusive end of "next quarter"
    q_start := min_ts;
    WHILE q_start < upper_bound LOOP
        partition_name := 'iam_login_attempts_' || to_char(q_start, 'YYYY') || '_q' || to_char(q_start, 'Q');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF iam_login_attempts_new FOR VALUES FROM (%L) TO (%L)',
            partition_name, q_start, q_start + INTERVAL '3 months'
        );
        q_start := q_start + INTERVAL '3 months';
    END LOOP;

    CREATE TABLE IF NOT EXISTS iam_login_attempts_default
        PARTITION OF iam_login_attempts_new DEFAULT;

    -- ─── Copy every existing row, then swap names in ───────────────────────
    INSERT INTO iam_login_attempts_new
        (id, attempt_type, outcome, failure_reason, identifier, principal_id,
         ip_address, user_agent, attempted_at)
    SELECT id, attempt_type, outcome, failure_reason, identifier, principal_id,
           ip_address, user_agent, attempted_at
      FROM iam_login_attempts;

    ALTER TABLE iam_login_attempts RENAME TO iam_login_attempts_old;
    ALTER TABLE iam_login_attempts_new RENAME TO iam_login_attempts;

    -- Renaming a table does NOT rename its indexes — iam_login_attempts_old
    -- still holds idx_iam_login_attempts_type/outcome/principal/at/
    -- failure_throttle at this point, AND the schema-global index name
    -- iam_login_attempts_pkey backing its primary key. So the old table must
    -- be gone before the working-named indexes above (and the new PK
    -- constraint, whose rename renames its backing index) can claim the
    -- canonical names.
    DROP TABLE iam_login_attempts_old;

    ALTER TABLE iam_login_attempts RENAME CONSTRAINT iam_login_attempts_new_pkey TO iam_login_attempts_pkey;

    ALTER INDEX idx_iam_login_attempts_np_identifier_at RENAME TO idx_iam_login_attempts_identifier_at;
    ALTER INDEX idx_iam_login_attempts_np_identifier_ip_at RENAME TO idx_iam_login_attempts_identifier_ip_at;
    ALTER INDEX idx_iam_login_attempts_np_type RENAME TO idx_iam_login_attempts_type;
    ALTER INDEX idx_iam_login_attempts_np_outcome RENAME TO idx_iam_login_attempts_outcome;
    ALTER INDEX idx_iam_login_attempts_np_principal RENAME TO idx_iam_login_attempts_principal;
    ALTER INDEX idx_iam_login_attempts_np_failure_throttle RENAME TO idx_iam_login_attempts_failure_throttle;
    ALTER INDEX idx_iam_login_attempts_np_at RENAME TO idx_iam_login_attempts_at;
END
$migration049$;
