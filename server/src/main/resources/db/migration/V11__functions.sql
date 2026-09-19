-- Function registry, work package A (docs/spec/function-registry.md §2), reshaped by
-- docs/spec/function-invocation.md §3-§4 (I1). Java-only: there is no Go for the
-- function service (spec §0), so these eight fn_ tables are never read or written by
-- Go and carry no goose counterpart. Additive, like V8's mail_outbox:
-- SchemaFingerprintTest.JAVA_ONLY_TABLES hides them from the Go fingerprint comparison
-- (spec §2.1). The one exception is chk_msg_subscriptions_source below, widened on the
-- Go-shared msg_subscriptions table to admit the new FUNCTION source (invocation spec
-- §4.1) — SchemaFingerprintTest and GoAdoptionTest carry a named, exact allowance for
-- that one line, not a blanket exclusion.
--
-- Every constraint and index below is named explicitly, starting with the table
-- name (constraints) or `idx_<table>_` (indexes), because the fingerprint filter
-- depends on that naming rule to recognise Java-only objects.
--
-- fn_functions: one row per app.service.name address (§3.2). application_code is
-- a copy of the owning application's immutable code, so a lookup by address can
-- check all three segments and avoid a join; no FK leaves the fn_ family, exactly
-- as portal_apps.client_id carries no FK to tnt_clients. client_id is nullable:
-- null means a platform-owned function (ruling R2, spec §6.1 FunctionOwner).
CREATE TABLE IF NOT EXISTS fn_functions (
    id VARCHAR(17) NOT NULL,
    application_id VARCHAR(17) NOT NULL,
    application_code VARCHAR(63) NOT NULL,
    service_name VARCHAR(63) NOT NULL,
    name VARCHAR(63) NOT NULL,
    client_id VARCHAR(17),
    runtime VARCHAR(10) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fn_functions_pkey PRIMARY KEY (id),
    CONSTRAINT fn_functions_application_code_check CHECK (application_code ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT fn_functions_service_name_check CHECK (service_name ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT fn_functions_name_check CHECK (name ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT fn_functions_runtime_check CHECK (runtime IN ('JVM', 'WASM')),
    CONSTRAINT fn_functions_status_check CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fn_functions_application_id_service_name_name_key UNIQUE (application_id, service_name, name),
    CONSTRAINT fn_functions_application_code_service_name_name_key UNIQUE (application_code, service_name, name)
);

-- fn_versions: an immutable published artifact of a function (§4, §6.2). The
-- signer/bundle columns are nullable because fcdev runs with signatures off
-- (design §8); whether production may ever leave them null is package C's rule.
-- function_id cascades: deleting a function deletes all its versions (ruling R4).
CREATE TABLE IF NOT EXISTS fn_versions (
    id VARCHAR(17) NOT NULL,
    function_id VARCHAR(17) NOT NULL,
    version INT NOT NULL,
    artifact_ref VARCHAR(1000) NOT NULL,
    digest VARCHAR(71) NOT NULL,
    signature_bundle TEXT,
    signature_bundle_ref VARCHAR(1000),
    signer_issuer VARCHAR(500),
    signer_subject VARCHAR(1000),
    manifest JSONB NOT NULL,
    state VARCHAR(20) NOT NULL,
    published_by VARCHAR(17) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ready_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    CONSTRAINT fn_versions_pkey PRIMARY KEY (id),
    CONSTRAINT fn_versions_function_id_fkey FOREIGN KEY (function_id) REFERENCES fn_functions (id) ON DELETE CASCADE,
    CONSTRAINT fn_versions_version_check CHECK (version > 0),
    CONSTRAINT fn_versions_digest_check CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT fn_versions_state_check CHECK (state IN ('PUBLISHED', 'READY', 'RETIRED')),
    CONSTRAINT fn_versions_ready_at_check CHECK (state <> 'READY' OR ready_at IS NOT NULL),
    CONSTRAINT fn_versions_retired_at_check CHECK (state <> 'RETIRED' OR retired_at IS NOT NULL),
    CONSTRAINT fn_versions_function_id_version_key UNIQUE (function_id, version),
    CONSTRAINT fn_versions_function_id_digest_key UNIQUE (function_id, digest)
);

CREATE INDEX IF NOT EXISTS idx_fn_versions_function_id_state ON fn_versions (function_id, state);

-- fn_aliases: a mutable named pointer (e.g. `live`) from a function to one of its
-- versions (§6.1). Natural key, no TSID. Both FKs cascade: deleting a function
-- deletes its aliases directly, and deleting a version (which only happens via
-- its function's cascade, §6.2) deletes any alias still pointing at it in the
-- same transaction (ruling R4).
CREATE TABLE IF NOT EXISTS fn_aliases (
    function_id VARCHAR(17) NOT NULL,
    alias VARCHAR(63) NOT NULL,
    version_id VARCHAR(17) NOT NULL,
    updated_by VARCHAR(17) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fn_aliases_pkey PRIMARY KEY (function_id, alias),
    CONSTRAINT fn_aliases_function_id_fkey FOREIGN KEY (function_id) REFERENCES fn_functions (id) ON DELETE CASCADE,
    CONSTRAINT fn_aliases_alias_check CHECK (alias ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT fn_aliases_version_id_fkey FOREIGN KEY (version_id) REFERENCES fn_versions (id) ON DELETE CASCADE
);

-- fn_hosts: a running function host self-registers under its own id (§6.3).
-- Natural key, no TSID.
CREATE TABLE IF NOT EXISTS fn_hosts (
    id VARCHAR(100) NOT NULL,
    pool VARCHAR(63) NOT NULL,
    state VARCHAR(20) NOT NULL,
    loaded JSONB NOT NULL DEFAULT '[]',
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_heartbeat TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fn_hosts_pkey PRIMARY KEY (id),
    CONSTRAINT fn_hosts_pool_check CHECK (pool ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT fn_hosts_state_check CHECK (state IN ('ACTIVE', 'DRAINING'))
);

CREATE INDEX IF NOT EXISTS idx_fn_hosts_pool_last_heartbeat ON fn_hosts (pool, last_heartbeat);

-- fn_client_policies: one row per client — allowed signers and per-client ceilings
-- (§6.4, `fn_signer_policies` renamed per the workplan's own suggestion, §0).
-- Natural key, no TSID. client_id also accepts the reserved value 'PLATFORM',
-- which carries the platform-owned functions' signer policy and ceilings
-- (ruling R2) — a primary key cannot be null, and no TSID is ever 'PLATFORM';
-- ClientPolicyRepository is the only code that spells that constant, mapping it
-- to/from FunctionOwner.Platform (spec §6.1, §6.4).
CREATE TABLE IF NOT EXISTS fn_client_policies (
    client_id VARCHAR(17) NOT NULL,
    signers JSONB NOT NULL DEFAULT '[]',
    max_duration_ms INT,
    max_concurrency INT,
    max_wasm_memory_mb INT,
    max_db_pool_size INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fn_client_policies_pkey PRIMARY KEY (client_id),
    CONSTRAINT fn_client_policies_max_duration_ms_check CHECK (max_duration_ms IS NULL OR max_duration_ms > 0),
    CONSTRAINT fn_client_policies_max_concurrency_check CHECK (max_concurrency IS NULL OR max_concurrency > 0),
    CONSTRAINT fn_client_policies_max_wasm_memory_mb_check CHECK (max_wasm_memory_mb IS NULL OR max_wasm_memory_mb > 0),
    CONSTRAINT fn_client_policies_max_db_pool_size_check CHECK (max_db_pool_size IS NULL OR max_db_pool_size > 0)
);

-- fn_domains: a client-verified hostname that may carry public fn_routes (§6.5).
-- client_id is nullable: null means the platform's domain (ruling R2).
CREATE TABLE IF NOT EXISTS fn_domains (
    id VARCHAR(17) NOT NULL,
    client_id VARCHAR(17),
    hostname VARCHAR(253) NOT NULL,
    verification_token VARCHAR(64) NOT NULL,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fn_domains_pkey PRIMARY KEY (id),
    CONSTRAINT fn_domains_hostname_check CHECK (hostname = lower(hostname)),
    CONSTRAINT fn_domains_hostname_key UNIQUE (hostname)
);

CREATE INDEX IF NOT EXISTS idx_fn_domains_client_id ON fn_domains (client_id);

-- fn_routes: public (hostname, path_prefix) pairs and the function they resolve to
-- (invocation spec §3, §6.6 as amended). Auth mode, methods, CORS, body cap and
-- timeout stay in the manifest, so this table names nothing else; a private call
-- (`/functions/{address}/...`) needs no route row at all, so hostname is NOT NULL
-- here — unlike the registry spec's original private-route design, superseded by
-- invocation spec §2/§3. Unique across every function: the conflict the design
-- wants rejected (two functions cannot claim the same public prefix).
CREATE TABLE IF NOT EXISTS fn_routes (
    id VARCHAR(17) NOT NULL,
    function_id VARCHAR(17) NOT NULL,
    hostname VARCHAR(253) NOT NULL,
    path_prefix VARCHAR(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fn_routes_pkey PRIMARY KEY (id),
    CONSTRAINT fn_routes_function_id_fkey FOREIGN KEY (function_id) REFERENCES fn_functions (id) ON DELETE CASCADE,
    CONSTRAINT fn_routes_hostname_path_prefix_key UNIQUE (hostname, path_prefix)
);

CREATE INDEX IF NOT EXISTS idx_fn_routes_function_id ON fn_routes (function_id);

-- fn_trigger_objects: the platform-managed objects (dispatch pool, subscriptions,
-- scheduled jobs) a function's live manifest created at promote (invocation spec
-- §4), so a later promote can reconcile (create / update-if-different / delete what
-- the new manifest no longer lists) and the two generic SDK syncs
-- (SyncDispatchPools, SyncScheduledJobs — invocation spec §4.2) can tell a
-- function-owned object apart from an ordinary application-owned one without either
-- operation knowing anything about functions. kind is one of POOL, SUBSCRIPTION,
-- SCHEDULED_JOB (TriggerObjectKind); trigger_key is the manifest-derived key the pk
-- is scoped by (a function has at most one pool, but many subscriptions/schedules);
-- object_id is the fn_-external row's id (msg_subscriptions.id, dsp_pools.id,
-- sched_jobs.id) — unique per kind, so "is this object linked to any function"
-- (cleanup by id) is one lookup.
CREATE TABLE IF NOT EXISTS fn_trigger_objects (
    function_id VARCHAR(17) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    object_id VARCHAR(17) NOT NULL,
    trigger_key VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fn_trigger_objects_pkey PRIMARY KEY (function_id, kind, trigger_key),
    CONSTRAINT fn_trigger_objects_function_id_fkey FOREIGN KEY (function_id) REFERENCES fn_functions (id) ON DELETE CASCADE,
    CONSTRAINT fn_trigger_objects_kind_check CHECK (kind IN ('POOL', 'SUBSCRIPTION', 'SCHEDULED_JOB')),
    CONSTRAINT fn_trigger_objects_kind_object_id_key UNIQUE (kind, object_id)
);

CREATE INDEX IF NOT EXISTS idx_fn_trigger_objects_function_id ON fn_trigger_objects (function_id);

-- msg_subscriptions.source (V6 chk_msg_subscriptions_source, subscription.Source in
-- Go) widened to admit the new FUNCTION source (invocation spec §4.1, ruling R6): a
-- function's subscription is never touched by an application SDK's
-- removeUnlisted sync (SubscriptionSource.isSyncManaged() is false for it). This is
-- a deliberate, documented divergence from Go on a table Go shares — see
-- docs/cutover.md §4.1: functions are a post-cutover feature while Go reads the same
-- database, and rollback to Go after the first function subscription needs those
-- rows deleted first. Idempotent like V6's original DO block: drop-then-add so a
-- second run (or a Go-adopted database that never had the narrower V6 constraint
-- applied under this name) still ends up with exactly the widened definition.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_msg_subscriptions_source'
    ) THEN
        ALTER TABLE msg_subscriptions DROP CONSTRAINT chk_msg_subscriptions_source;
    END IF;
    ALTER TABLE msg_subscriptions
        ADD CONSTRAINT chk_msg_subscriptions_source
        CHECK (source IN ('CODE', 'API', 'UI', 'FUNCTION'));
END $$;

