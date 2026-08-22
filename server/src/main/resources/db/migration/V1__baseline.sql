-- FlowCatalyst baseline schema (V1).
--
-- This is the schema produced by the Go service's goose migrations
-- (flowcatalyst-go/internal/migrate/sql/001..045) captured with
-- `pg_dump --schema-only` from PostgreSQL 18 and cleaned up for Flyway:
--
--   * pg_dump preamble (SET ..., \restrict, set_config), OWNER TO and
--     psql meta-commands removed;
--   * the `goose_db_version` table is NOT part of the baseline. Java never
--     creates it; on a database adopted from Go it already exists and is
--     left untouched so a rollback to the Go service stays possible;
--   * the dated monthly partitions (`<parent>_YYYY_MM`) and their
--     ATTACH PARTITION / ATTACH INDEX statements are replaced by the DO block
--     at the end of this file, which creates month-1 .. month+3 partitions
--     relative to now() — exactly what Go migrations 019/022 do on a fresh
--     install. Forward-rolling and retention are a runtime concern
--     (Go: internal/stream/partition_manager.go; Java: its port).
--
-- Everything else (every table, column, default, constraint, index and
-- sequence) is identical to the Go schema. `IF NOT EXISTS` is used where it
-- is free so an accidental re-run on a Go-shaped database is harmless.
--
-- Rollback-to-Go rule: the Go service must be able to run against any
-- database migrated by this project. Until that rule is lifted, every
-- migration after V1 has to be additive and ignorable by Go (new nullable
-- columns, new tables, new indexes) — never rename/drop/retype anything Go
-- reads or writes, and never touch goose_db_version.


-- ============================================================================
-- Sequences
-- ============================================================================

CREATE SEQUENCE IF NOT EXISTS public.iam_rate_limit_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.msg_dispatch_job_projection_feed_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.msg_event_projection_feed_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.msg_subscription_custom_configs_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.msg_subscription_event_types_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.oauth_identity_provider_allowed_domains_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.oauth_identity_provider_allowed_roles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.tnt_email_domain_mapping_2fa_methods_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.tnt_email_domain_mapping_additional_clients_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.tnt_email_domain_mapping_allowed_roles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE SEQUENCE IF NOT EXISTS public.tnt_email_domain_mapping_granted_clients_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


-- ============================================================================
-- Tables
-- ============================================================================

-- app_application_openapi_specs
CREATE TABLE IF NOT EXISTS public.app_application_openapi_specs (
    id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL,
    version character varying(64) NOT NULL,
    status character varying(20) NOT NULL,
    spec jsonb NOT NULL,
    spec_hash character varying(64) NOT NULL,
    change_notes jsonb,
    change_notes_text text,
    synced_at timestamp with time zone DEFAULT now() NOT NULL,
    synced_by character varying(17),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- app_applications
CREATE TABLE IF NOT EXISTS public.app_applications (
    id character varying(17) NOT NULL,
    type character varying(50) DEFAULT 'APPLICATION'::character varying NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    icon_url character varying(500),
    website character varying(500),
    logo text,
    logo_mime_type character varying(100),
    default_base_url character varying(500),
    service_account_id character varying(17),
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- app_client_configs
CREATE TABLE IF NOT EXISTS public.app_client_configs (
    id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL,
    client_id character varying(17) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- app_docs
CREATE TABLE IF NOT EXISTS public.app_docs (
    id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL,
    slug character varying(120) NOT NULL,
    title character varying(200) NOT NULL,
    content text NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

-- app_platform_config_access
CREATE TABLE IF NOT EXISTS public.app_platform_config_access (
    id character varying(17) NOT NULL,
    application_code character varying(100) NOT NULL,
    role_code character varying(200) NOT NULL,
    can_read boolean DEFAULT true NOT NULL,
    can_write boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- app_platform_configs
CREATE TABLE IF NOT EXISTS public.app_platform_configs (
    id character varying(17) NOT NULL,
    application_code character varying(100) NOT NULL,
    section character varying(100) NOT NULL,
    property character varying(100) NOT NULL,
    scope character varying(20) NOT NULL,
    client_id character varying(17),
    value_type character varying(20) NOT NULL,
    value text NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- aud_logs
CREATE TABLE IF NOT EXISTS public.aud_logs (
    id character varying(17) NOT NULL,
    entity_type character varying(100) NOT NULL,
    entity_id character varying(17) NOT NULL,
    operation character varying(100) NOT NULL,
    operation_json jsonb,
    principal_id character varying(100),
    performed_at timestamp with time zone DEFAULT now() NOT NULL,
    application_id character varying(17),
    client_id character varying(17)
);

-- iam_authorization_codes
CREATE TABLE IF NOT EXISTS public.iam_authorization_codes (
    code character varying(255) NOT NULL,
    client_id character varying(255) NOT NULL,
    principal_id character varying(17) NOT NULL,
    redirect_uri text NOT NULL,
    scope text,
    code_challenge text,
    code_challenge_method character varying(10),
    nonce text,
    state text,
    context_client_id character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used boolean DEFAULT false NOT NULL
);

-- iam_client_access_grants
CREATE TABLE IF NOT EXISTS public.iam_client_access_grants (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    client_id character varying(17) NOT NULL,
    granted_by character varying(17) NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_login_attempts
CREATE TABLE IF NOT EXISTS public.iam_login_attempts (
    id character varying(17) NOT NULL,
    attempt_type character varying(30) NOT NULL,
    outcome character varying(20) NOT NULL,
    failure_reason character varying(100),
    identifier character varying(255),
    principal_id character varying(17),
    ip_address character varying(45),
    user_agent text,
    attempted_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_mfa_email_pins
CREATE TABLE IF NOT EXISTS public.iam_mfa_email_pins (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    purpose character varying(20) DEFAULT 'login'::character varying NOT NULL,
    pin_hash character varying(64) NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_mfa_trusted_devices
CREATE TABLE IF NOT EXISTS public.iam_mfa_trusted_devices (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    token_hash character varying(64) NOT NULL,
    label character varying(255),
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone
);

-- iam_oidc_login_states
CREATE TABLE IF NOT EXISTS public.iam_oidc_login_states (
    state character varying(255) NOT NULL,
    email_domain character varying(255) NOT NULL,
    auth_config_id character varying(17) NOT NULL,
    nonce text NOT NULL,
    code_verifier text NOT NULL,
    return_url text,
    oauth_client_id character varying(255),
    oauth_redirect_uri text,
    oauth_scope text,
    oauth_state text,
    oauth_code_challenge text,
    oauth_code_challenge_method character varying(10),
    oauth_nonce text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL
);

-- iam_password_reset_tokens
CREATE TABLE IF NOT EXISTS public.iam_password_reset_tokens (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    purpose character varying(20) DEFAULT 'reset'::character varying NOT NULL,
    reset_2fa boolean DEFAULT false NOT NULL,
    requires_factor boolean DEFAULT false NOT NULL,
    factor_attempts integer DEFAULT 0 NOT NULL,
    redirect_uri character varying(2000)
);

-- iam_permissions
CREATE TABLE IF NOT EXISTS public.iam_permissions (
    id character varying(17) NOT NULL,
    code character varying(255) NOT NULL,
    subdomain character varying(50) NOT NULL,
    context character varying(50) NOT NULL,
    aggregate character varying(50) NOT NULL,
    action character varying(50) NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_principal_application_access
CREATE TABLE IF NOT EXISTS public.iam_principal_application_access (
    principal_id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_principal_roles
CREATE TABLE IF NOT EXISTS public.iam_principal_roles (
    principal_id character varying(17) NOT NULL,
    role_name character varying(100) NOT NULL,
    assignment_source character varying(50),
    assigned_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_principals
CREATE TABLE IF NOT EXISTS public.iam_principals (
    id character varying(17) NOT NULL,
    type character varying(20) NOT NULL,
    scope character varying(20),
    client_id character varying(17),
    application_id character varying(17),
    name character varying(255) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    email character varying(255),
    email_domain character varying(100),
    idp_type character varying(50),
    external_idp_id character varying(255),
    password_hash character varying(255),
    last_login_at timestamp with time zone,
    service_account_id character varying(17),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    all_applications boolean DEFAULT true NOT NULL,
    dev_client_secret_ref text,
    dev_client_secret_updated_at timestamp with time zone
);

-- iam_rate_limit_events
CREATE TABLE IF NOT EXISTS public.iam_rate_limit_events (
    id bigint NOT NULL,
    bucket character varying(64) NOT NULL,
    key text NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_refresh_tokens
CREATE TABLE IF NOT EXISTS public.iam_refresh_tokens (
    id character varying(17) NOT NULL,
    token_hash character varying(255) NOT NULL,
    principal_id character varying(17) NOT NULL,
    oauth_client_id character varying(255),
    scopes text,
    accessible_clients text,
    revoked boolean DEFAULT false NOT NULL,
    revoked_at timestamp with time zone,
    token_family character varying(255),
    replaced_by character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    last_used_at timestamp with time zone,
    created_from_ip character varying(45),
    user_agent text
);

-- iam_reset_approval_requests
CREATE TABLE IF NOT EXISTS public.iam_reset_approval_requests (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    client_id character varying(17),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    reset_2fa boolean DEFAULT true NOT NULL,
    note character varying(255),
    decided_by character varying(17),
    decided_at timestamp with time zone,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_role_permissions
CREATE TABLE IF NOT EXISTS public.iam_role_permissions (
    role_id character varying(17) NOT NULL,
    permission character varying(255) NOT NULL
);

-- iam_roles
CREATE TABLE IF NOT EXISTS public.iam_roles (
    id character varying(17) NOT NULL,
    application_id character varying(17),
    application_code character varying(50),
    name character varying(255) NOT NULL,
    display_name character varying(255) NOT NULL,
    description text,
    source character varying(50) DEFAULT 'DATABASE'::character varying NOT NULL,
    client_managed boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_service_accounts
CREATE TABLE IF NOT EXISTS public.iam_service_accounts (
    id character varying(17) NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(200) NOT NULL,
    description character varying(500),
    application_id character varying(17),
    active boolean DEFAULT true NOT NULL,
    wh_auth_type character varying(50),
    wh_auth_token_ref character varying(500),
    wh_signing_secret_ref character varying(500),
    wh_signing_algorithm character varying(50),
    wh_credentials_created_at timestamp with time zone,
    wh_credentials_regenerated_at timestamp with time zone,
    last_used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    scope character varying(20),
    client_ids text[]
);

-- iam_user_mfa_methods
CREATE TABLE IF NOT EXISTS public.iam_user_mfa_methods (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    method character varying(20) NOT NULL,
    secret_encrypted text,
    confirmed_at timestamp with time zone,
    last_used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- iam_user_mfa_recovery_codes
CREATE TABLE IF NOT EXISTS public.iam_user_mfa_recovery_codes (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    code_hash character varying(64) NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- msg_connections
CREATE TABLE IF NOT EXISTS public.msg_connections (
    id character varying(17) NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(500),
    external_id character varying(100),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    service_account_id character varying(17) NOT NULL,
    client_id character varying(17),
    client_identifier character varying(100),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- msg_dispatch_job_attempts
CREATE TABLE IF NOT EXISTS public.msg_dispatch_job_attempts (
    id character varying(13) NOT NULL,
    dispatch_job_id character varying(13) NOT NULL,
    attempt_number integer,
    status character varying(20),
    response_code integer,
    response_body text,
    error_message text,
    error_stack_trace text,
    error_type character varying(20),
    duration_millis bigint,
    attempted_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
)
PARTITION BY RANGE (created_at);

-- msg_dispatch_job_projection_feed
CREATE TABLE IF NOT EXISTS public.msg_dispatch_job_projection_feed (
    id bigint NOT NULL,
    dispatch_job_id character varying(13) NOT NULL,
    operation character varying(10) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    processed smallint DEFAULT 0 NOT NULL,
    processed_at timestamp with time zone,
    error_message text
);

-- msg_dispatch_jobs
CREATE TABLE IF NOT EXISTS public.msg_dispatch_jobs (
    id character varying(13) NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) DEFAULT 'EVENT'::character varying NOT NULL,
    code character varying(200) NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    metadata jsonb DEFAULT '[]'::jsonb,
    target_url character varying(500) NOT NULL,
    protocol character varying(30) DEFAULT 'HTTP_WEBHOOK'::character varying NOT NULL,
    payload text,
    payload_content_type character varying(100) DEFAULT 'application/json'::character varying,
    data_only boolean DEFAULT true NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    mode character varying(30) DEFAULT 'IMMEDIATE'::character varying NOT NULL,
    dispatch_pool_id character varying(17),
    message_group character varying(200),
    sequence integer DEFAULT 99 NOT NULL,
    timeout_seconds integer DEFAULT 30 NOT NULL,
    schema_id character varying(17),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    max_retries integer DEFAULT 3 NOT NULL,
    retry_strategy character varying(50) DEFAULT 'exponential'::character varying,
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 NOT NULL,
    last_attempt_at timestamp with time zone,
    completed_at timestamp with time zone,
    duration_millis bigint,
    last_error text,
    idempotency_key character varying(100),
    queued_at timestamp with time zone,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
)
PARTITION BY RANGE (created_at);

-- msg_dispatch_jobs_read
CREATE TABLE IF NOT EXISTS public.msg_dispatch_jobs_read (
    id character varying(13) NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) NOT NULL,
    code character varying(200) NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    target_url character varying(500) NOT NULL,
    protocol character varying(30) NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    dispatch_pool_id character varying(17),
    mode character varying(30) NOT NULL,
    message_group character varying(200),
    sequence integer DEFAULT 99,
    timeout_seconds integer DEFAULT 30,
    status character varying(20) NOT NULL,
    max_retries integer NOT NULL,
    retry_strategy character varying(50),
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 NOT NULL,
    last_attempt_at timestamp with time zone,
    completed_at timestamp with time zone,
    duration_millis bigint,
    last_error text,
    idempotency_key character varying(100),
    is_completed boolean,
    is_terminal boolean,
    application character varying(100),
    subdomain character varying(100),
    aggregate character varying(100),
    updated_at timestamp with time zone NOT NULL,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
)
PARTITION BY RANGE (created_at);

-- msg_dispatch_pools
CREATE TABLE IF NOT EXISTS public.msg_dispatch_pools (
    id character varying(17) NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(500),
    rate_limit integer,
    concurrency integer DEFAULT 10 NOT NULL,
    client_id character varying(17),
    client_identifier character varying(100),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- msg_event_projection_feed
CREATE TABLE IF NOT EXISTS public.msg_event_projection_feed (
    id bigint NOT NULL,
    event_id character varying(13) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    processed smallint DEFAULT 0 NOT NULL,
    processed_at timestamp with time zone,
    error_message text
);

-- msg_event_type_spec_versions
CREATE TABLE IF NOT EXISTS public.msg_event_type_spec_versions (
    id character varying(17) NOT NULL,
    event_type_id character varying(17) NOT NULL,
    version character varying(20) NOT NULL,
    mime_type character varying(100) NOT NULL,
    schema_content jsonb,
    schema_type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'FINALISING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- msg_event_types
CREATE TABLE IF NOT EXISTS public.msg_event_types (
    id character varying(17) NOT NULL,
    code character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    status character varying(20) DEFAULT 'CURRENT'::character varying NOT NULL,
    source character varying(20) DEFAULT 'UI'::character varying NOT NULL,
    client_scoped boolean DEFAULT false NOT NULL,
    application character varying(100) NOT NULL,
    subdomain character varying(100) NOT NULL,
    aggregate character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(17)
);

-- msg_events
CREATE TABLE IF NOT EXISTS public.msg_events (
    id character varying(13) NOT NULL,
    spec_version character varying(20) DEFAULT '1.0'::character varying,
    type character varying(200) NOT NULL,
    source character varying(500) NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone NOT NULL,
    data jsonb,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    context_data jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    projected_at timestamp with time zone,
    fanned_out_at timestamp with time zone
)
PARTITION BY RANGE (created_at);

-- msg_events_read
CREATE TABLE IF NOT EXISTS public.msg_events_read (
    id character varying(13) NOT NULL,
    spec_version character varying(20),
    type character varying(200) NOT NULL,
    source character varying(500) NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone NOT NULL,
    data text,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    application character varying(100),
    subdomain character varying(100),
    aggregate character varying(100),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    projected_at timestamp with time zone DEFAULT now() NOT NULL
)
PARTITION BY RANGE (created_at);

-- msg_processes
CREATE TABLE IF NOT EXISTS public.msg_processes (
    id character varying(17) NOT NULL,
    code character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    status character varying(20) DEFAULT 'CURRENT'::character varying NOT NULL,
    source character varying(20) DEFAULT 'UI'::character varying NOT NULL,
    application character varying(100) NOT NULL,
    subdomain character varying(100) NOT NULL,
    process_name character varying(100) NOT NULL,
    body text DEFAULT ''::text NOT NULL,
    diagram_type character varying(20) DEFAULT 'mermaid'::character varying NOT NULL,
    tags text[] DEFAULT ARRAY[]::text[] NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- msg_scheduled_job_instance_logs
CREATE TABLE IF NOT EXISTS public.msg_scheduled_job_instance_logs (
    id character varying(17) NOT NULL,
    instance_id character varying(17) NOT NULL,
    scheduled_job_id character varying(17),
    client_id character varying(17),
    level character varying(10) DEFAULT 'INFO'::character varying NOT NULL,
    message text NOT NULL,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
)
PARTITION BY RANGE (created_at);

-- msg_scheduled_job_instances
CREATE TABLE IF NOT EXISTS public.msg_scheduled_job_instances (
    id character varying(17) NOT NULL,
    scheduled_job_id character varying(17) NOT NULL,
    client_id character varying(17),
    job_code character varying(200) NOT NULL,
    trigger_kind character varying(20) DEFAULT 'CRON'::character varying NOT NULL,
    scheduled_for timestamp with time zone,
    fired_at timestamp with time zone DEFAULT now() NOT NULL,
    delivered_at timestamp with time zone,
    completed_at timestamp with time zone,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    delivery_attempts integer DEFAULT 0 NOT NULL,
    delivery_error text,
    completion_status character varying(20),
    completion_result jsonb,
    correlation_id character varying(100),
    created_at timestamp with time zone DEFAULT now() NOT NULL
)
PARTITION BY RANGE (created_at);

-- msg_scheduled_jobs
CREATE TABLE IF NOT EXISTS public.msg_scheduled_jobs (
    id character varying(17) NOT NULL,
    client_id character varying(17),
    code character varying(200) NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    crons text[] NOT NULL,
    timezone character varying(64) DEFAULT 'UTC'::character varying NOT NULL,
    payload jsonb,
    concurrent boolean DEFAULT false NOT NULL,
    tracks_completion boolean DEFAULT false NOT NULL,
    timeout_seconds integer,
    delivery_max_attempts integer DEFAULT 3 NOT NULL,
    target_url character varying(500),
    last_fired_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(17),
    updated_by character varying(17),
    version integer DEFAULT 1 NOT NULL,
    application_id character varying(17)
);

-- msg_subscription_custom_configs
CREATE TABLE IF NOT EXISTS public.msg_subscription_custom_configs (
    id integer NOT NULL,
    subscription_id character varying(17) NOT NULL,
    config_key character varying(100) NOT NULL,
    config_value character varying(1000) NOT NULL
);

-- msg_subscription_event_types
CREATE TABLE IF NOT EXISTS public.msg_subscription_event_types (
    id integer NOT NULL,
    subscription_id character varying(17) NOT NULL,
    event_type_id character varying(17),
    event_type_code character varying(255) NOT NULL,
    spec_version character varying(50)
);

-- msg_subscriptions
CREATE TABLE IF NOT EXISTS public.msg_subscriptions (
    id character varying(17) NOT NULL,
    code character varying(100) NOT NULL,
    application_code character varying(100),
    name character varying(255) NOT NULL,
    description text,
    client_id character varying(17),
    client_identifier character varying(100),
    client_scoped boolean DEFAULT false NOT NULL,
    target character varying(500) NOT NULL,
    queue character varying(255),
    source character varying(20) DEFAULT 'UI'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    max_age_seconds integer DEFAULT 86400 NOT NULL,
    dispatch_pool_id character varying(17),
    dispatch_pool_code character varying(100),
    delay_seconds integer DEFAULT 0 NOT NULL,
    sequence integer DEFAULT 99 NOT NULL,
    mode character varying(20) DEFAULT 'IMMEDIATE'::character varying NOT NULL,
    timeout_seconds integer DEFAULT 30 NOT NULL,
    max_retries integer DEFAULT 3 NOT NULL,
    service_account_id character varying(17),
    data_only boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    connection_id character varying(17),
    created_by character varying(17)
);

-- oauth_client_allowed_origins
CREATE TABLE IF NOT EXISTS public.oauth_client_allowed_origins (
    oauth_client_id character varying(17) NOT NULL,
    allowed_origin character varying(200) NOT NULL
);

-- oauth_client_application_ids
CREATE TABLE IF NOT EXISTS public.oauth_client_application_ids (
    oauth_client_id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL
);

-- oauth_client_grant_types
CREATE TABLE IF NOT EXISTS public.oauth_client_grant_types (
    oauth_client_id character varying(17) NOT NULL,
    grant_type character varying(50) NOT NULL
);

-- oauth_client_post_logout_redirect_uris
CREATE TABLE IF NOT EXISTS public.oauth_client_post_logout_redirect_uris (
    oauth_client_id character varying(17) NOT NULL,
    post_logout_redirect_uri text CONSTRAINT oauth_client_post_logout_redi_post_logout_redirect_uri_not_null NOT NULL
);

-- oauth_client_redirect_uris
CREATE TABLE IF NOT EXISTS public.oauth_client_redirect_uris (
    oauth_client_id character varying(17) NOT NULL,
    redirect_uri character varying(500) NOT NULL
);

-- oauth_clients
CREATE TABLE IF NOT EXISTS public.oauth_clients (
    id character varying(17) NOT NULL,
    client_id character varying(100) NOT NULL,
    client_name character varying(255) NOT NULL,
    client_type character varying(20) DEFAULT 'PUBLIC'::character varying NOT NULL,
    client_secret_ref character varying(500),
    default_scopes character varying(500),
    pkce_required boolean DEFAULT true NOT NULL,
    service_account_principal_id character varying(17),
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    portal_client_id character varying(17),
    api_access boolean DEFAULT false NOT NULL
);

-- oauth_identity_provider_allowed_domains
CREATE TABLE IF NOT EXISTS public.oauth_identity_provider_allowed_domains (
    id integer NOT NULL,
    identity_provider_id character varying(17) CONSTRAINT oauth_identity_provider_allowed_d_identity_provider_id_not_null NOT NULL,
    email_domain character varying(255) NOT NULL
);

-- oauth_identity_provider_allowed_roles
CREATE TABLE IF NOT EXISTS public.oauth_identity_provider_allowed_roles (
    id integer NOT NULL,
    identity_provider_id character varying(17) CONSTRAINT oauth_identity_provider_allowed_r_identity_provider_id_not_null NOT NULL,
    role_id character varying(17) NOT NULL
);

-- oauth_identity_providers
CREATE TABLE IF NOT EXISTS public.oauth_identity_providers (
    id character varying(17) NOT NULL,
    code character varying(50) NOT NULL,
    name character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    oidc_issuer_url character varying(500),
    oidc_client_id character varying(200),
    oidc_client_secret_ref character varying(500),
    oidc_multi_tenant boolean DEFAULT false NOT NULL,
    oidc_issuer_pattern character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    sync_roles_from_idp boolean DEFAULT false NOT NULL
);

-- oauth_idp_role_mappings
CREATE TABLE IF NOT EXISTS public.oauth_idp_role_mappings (
    id character varying(17) NOT NULL,
    idp_role_name character varying(200) NOT NULL,
    internal_role_name character varying(200) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    idp_type character varying(50)
);

-- oauth_oidc_login_states
CREATE TABLE IF NOT EXISTS public.oauth_oidc_login_states (
    state character varying(200) NOT NULL,
    email_domain character varying(255) NOT NULL,
    identity_provider_id character varying(17) NOT NULL,
    email_domain_mapping_id character varying(17) NOT NULL,
    nonce character varying(200) NOT NULL,
    code_verifier character varying(200) NOT NULL,
    return_url character varying(2000),
    oauth_client_id character varying(200),
    oauth_redirect_uri character varying(2000),
    oauth_scope character varying(500),
    oauth_state character varying(500),
    oauth_code_challenge character varying(500),
    oauth_code_challenge_method character varying(20),
    oauth_nonce character varying(500),
    interaction_uid character varying(200),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    portal_client_id character varying(17)
);

-- oauth_oidc_payloads
CREATE TABLE IF NOT EXISTS public.oauth_oidc_payloads (
    id character varying(128) NOT NULL,
    type character varying(64) NOT NULL,
    payload jsonb NOT NULL,
    grant_id character varying(128),
    user_code character varying(128),
    uid character varying(128),
    expires_at timestamp with time zone,
    consumed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

-- portal_identities
CREATE TABLE IF NOT EXISTS public.portal_identities (
    id character varying(17) NOT NULL,
    client_id character varying(17) NOT NULL,
    email character varying(255) NOT NULL,
    name character varying(255),
    password_hash character varying(255),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    source character varying(20) NOT NULL,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- portal_login_flows
CREATE TABLE IF NOT EXISTS public.portal_login_flows (
    id character varying(64) NOT NULL,
    oauth_client_id character varying(100) NOT NULL,
    portal_client_id character varying(17) NOT NULL,
    redirect_uri character varying(2000) NOT NULL,
    scope character varying(500),
    state character varying(500) NOT NULL,
    nonce character varying(500),
    code_challenge character varying(200),
    code_challenge_method character varying(10),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL
);

-- tnt_anchor_domains
CREATE TABLE IF NOT EXISTS public.tnt_anchor_domains (
    id character varying(17) NOT NULL,
    domain character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- tnt_client_auth_configs
CREATE TABLE IF NOT EXISTS public.tnt_client_auth_configs (
    id character varying(17) NOT NULL,
    email_domain character varying(255) NOT NULL,
    config_type character varying(50) NOT NULL,
    primary_client_id character varying(17),
    additional_client_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    granted_client_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    auth_provider character varying(50) NOT NULL,
    oidc_issuer_url character varying(500),
    oidc_client_id character varying(255),
    oidc_multi_tenant boolean DEFAULT false NOT NULL,
    oidc_issuer_pattern character varying(500),
    oidc_client_secret_ref character varying(1000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- tnt_clients
CREATE TABLE IF NOT EXISTS public.tnt_clients (
    id character varying(17) NOT NULL,
    name character varying(255) NOT NULL,
    identifier character varying(100) NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    status_reason character varying(255),
    status_changed_at timestamp with time zone,
    notes jsonb DEFAULT '[]'::jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- tnt_cors_allowed_origins
CREATE TABLE IF NOT EXISTS public.tnt_cors_allowed_origins (
    id character varying(17) NOT NULL,
    origin character varying(500) NOT NULL,
    description text,
    created_by character varying(17),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

-- tnt_email_domain_mapping_2fa_methods
CREATE TABLE IF NOT EXISTS public.tnt_email_domain_mapping_2fa_methods (
    id integer NOT NULL,
    email_domain_mapping_id character varying(17) CONSTRAINT tnt_email_domain_mapping_2fa_m_email_domain_mapping_id_not_null NOT NULL,
    method character varying(20) NOT NULL
);

-- tnt_email_domain_mapping_additional_clients
CREATE TABLE IF NOT EXISTS public.tnt_email_domain_mapping_additional_clients (
    id integer NOT NULL,
    email_domain_mapping_id character varying(17) CONSTRAINT tnt_email_domain_mapping_addit_email_domain_mapping_id_not_null NOT NULL,
    client_id character varying(17) NOT NULL
);

-- tnt_email_domain_mapping_allowed_roles
CREATE TABLE IF NOT EXISTS public.tnt_email_domain_mapping_allowed_roles (
    id integer NOT NULL,
    email_domain_mapping_id character varying(17) CONSTRAINT tnt_email_domain_mapping_allow_email_domain_mapping_id_not_null NOT NULL,
    role_id character varying(17) NOT NULL
);

-- tnt_email_domain_mapping_granted_clients
CREATE TABLE IF NOT EXISTS public.tnt_email_domain_mapping_granted_clients (
    id integer NOT NULL,
    email_domain_mapping_id character varying(17) CONSTRAINT tnt_email_domain_mapping_grant_email_domain_mapping_id_not_null NOT NULL,
    client_id character varying(17) NOT NULL
);

-- tnt_email_domain_mappings
CREATE TABLE IF NOT EXISTS public.tnt_email_domain_mappings (
    id character varying(17) NOT NULL,
    email_domain character varying(255) NOT NULL,
    identity_provider_id character varying(17) NOT NULL,
    scope_type character varying(20) NOT NULL,
    primary_client_id character varying(17),
    required_oidc_tenant_id character varying(100),
    sync_roles_from_idp boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    require_2fa boolean DEFAULT false NOT NULL,
    remember_device_enabled boolean DEFAULT false NOT NULL,
    remember_device_days integer DEFAULT 30 NOT NULL
);

-- webauthn_credentials
CREATE TABLE IF NOT EXISTS public.webauthn_credentials (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    credential_id bytea NOT NULL,
    passkey_data jsonb NOT NULL,
    name character varying(120),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone
);


-- ============================================================================
-- Sequence ownership
-- ============================================================================

ALTER SEQUENCE public.iam_rate_limit_events_id_seq OWNED BY public.iam_rate_limit_events.id;

ALTER SEQUENCE public.msg_dispatch_job_projection_feed_id_seq OWNED BY public.msg_dispatch_job_projection_feed.id;

ALTER SEQUENCE public.msg_event_projection_feed_id_seq OWNED BY public.msg_event_projection_feed.id;

ALTER SEQUENCE public.msg_subscription_custom_configs_id_seq OWNED BY public.msg_subscription_custom_configs.id;

ALTER SEQUENCE public.msg_subscription_event_types_id_seq OWNED BY public.msg_subscription_event_types.id;

ALTER SEQUENCE public.oauth_identity_provider_allowed_domains_id_seq OWNED BY public.oauth_identity_provider_allowed_domains.id;

ALTER SEQUENCE public.oauth_identity_provider_allowed_roles_id_seq OWNED BY public.oauth_identity_provider_allowed_roles.id;

ALTER SEQUENCE public.tnt_email_domain_mapping_2fa_methods_id_seq OWNED BY public.tnt_email_domain_mapping_2fa_methods.id;

ALTER SEQUENCE public.tnt_email_domain_mapping_additional_clients_id_seq OWNED BY public.tnt_email_domain_mapping_additional_clients.id;

ALTER SEQUENCE public.tnt_email_domain_mapping_allowed_roles_id_seq OWNED BY public.tnt_email_domain_mapping_allowed_roles.id;

ALTER SEQUENCE public.tnt_email_domain_mapping_granted_clients_id_seq OWNED BY public.tnt_email_domain_mapping_granted_clients.id;


-- ============================================================================
-- Column defaults backed by sequences
-- ============================================================================

ALTER TABLE public.iam_rate_limit_events ALTER COLUMN id SET DEFAULT nextval('public.iam_rate_limit_events_id_seq'::regclass);

ALTER TABLE public.msg_dispatch_job_projection_feed ALTER COLUMN id SET DEFAULT nextval('public.msg_dispatch_job_projection_feed_id_seq'::regclass);

ALTER TABLE public.msg_event_projection_feed ALTER COLUMN id SET DEFAULT nextval('public.msg_event_projection_feed_id_seq'::regclass);

ALTER TABLE public.msg_subscription_custom_configs ALTER COLUMN id SET DEFAULT nextval('public.msg_subscription_custom_configs_id_seq'::regclass);

ALTER TABLE public.msg_subscription_event_types ALTER COLUMN id SET DEFAULT nextval('public.msg_subscription_event_types_id_seq'::regclass);

ALTER TABLE public.oauth_identity_provider_allowed_domains ALTER COLUMN id SET DEFAULT nextval('public.oauth_identity_provider_allowed_domains_id_seq'::regclass);

ALTER TABLE public.oauth_identity_provider_allowed_roles ALTER COLUMN id SET DEFAULT nextval('public.oauth_identity_provider_allowed_roles_id_seq'::regclass);

ALTER TABLE public.tnt_email_domain_mapping_2fa_methods ALTER COLUMN id SET DEFAULT nextval('public.tnt_email_domain_mapping_2fa_methods_id_seq'::regclass);

ALTER TABLE public.tnt_email_domain_mapping_additional_clients ALTER COLUMN id SET DEFAULT nextval('public.tnt_email_domain_mapping_additional_clients_id_seq'::regclass);

ALTER TABLE public.tnt_email_domain_mapping_allowed_roles ALTER COLUMN id SET DEFAULT nextval('public.tnt_email_domain_mapping_allowed_roles_id_seq'::regclass);

ALTER TABLE public.tnt_email_domain_mapping_granted_clients ALTER COLUMN id SET DEFAULT nextval('public.tnt_email_domain_mapping_granted_clients_id_seq'::regclass);


-- ============================================================================
-- Primary keys and unique constraints
-- ============================================================================

ALTER TABLE public.app_application_openapi_specs
    ADD CONSTRAINT app_application_openapi_specs_application_id_version_key UNIQUE (application_id, version);

ALTER TABLE public.app_application_openapi_specs
    ADD CONSTRAINT app_application_openapi_specs_pkey PRIMARY KEY (id);

ALTER TABLE public.app_applications
    ADD CONSTRAINT app_applications_code_key UNIQUE (code);

ALTER TABLE public.app_applications
    ADD CONSTRAINT app_applications_pkey PRIMARY KEY (id);

ALTER TABLE public.app_client_configs
    ADD CONSTRAINT app_client_configs_pkey PRIMARY KEY (id);

ALTER TABLE public.app_docs
    ADD CONSTRAINT app_docs_application_id_slug_key UNIQUE (application_id, slug);

ALTER TABLE public.app_docs
    ADD CONSTRAINT app_docs_pkey PRIMARY KEY (id);

ALTER TABLE public.app_platform_config_access
    ADD CONSTRAINT app_platform_config_access_pkey PRIMARY KEY (id);

ALTER TABLE public.app_platform_configs
    ADD CONSTRAINT app_platform_configs_pkey PRIMARY KEY (id);

ALTER TABLE public.aud_logs
    ADD CONSTRAINT aud_logs_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_authorization_codes
    ADD CONSTRAINT iam_authorization_codes_pkey PRIMARY KEY (code);

ALTER TABLE public.iam_client_access_grants
    ADD CONSTRAINT iam_client_access_grants_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_login_attempts
    ADD CONSTRAINT iam_login_attempts_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_mfa_email_pins
    ADD CONSTRAINT iam_mfa_email_pins_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_mfa_trusted_devices
    ADD CONSTRAINT iam_mfa_trusted_devices_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_mfa_trusted_devices
    ADD CONSTRAINT iam_mfa_trusted_devices_token_hash_key UNIQUE (token_hash);

ALTER TABLE public.iam_oidc_login_states
    ADD CONSTRAINT iam_oidc_login_states_pkey PRIMARY KEY (state);

ALTER TABLE public.iam_password_reset_tokens
    ADD CONSTRAINT iam_password_reset_tokens_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_password_reset_tokens
    ADD CONSTRAINT iam_password_reset_tokens_token_hash_key UNIQUE (token_hash);

ALTER TABLE public.iam_permissions
    ADD CONSTRAINT iam_permissions_code_key UNIQUE (code);

ALTER TABLE public.iam_permissions
    ADD CONSTRAINT iam_permissions_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_principal_application_access
    ADD CONSTRAINT iam_principal_application_access_pkey PRIMARY KEY (principal_id, application_id);

ALTER TABLE public.iam_principal_roles
    ADD CONSTRAINT iam_principal_roles_pkey PRIMARY KEY (principal_id, role_name);

ALTER TABLE public.iam_principals
    ADD CONSTRAINT iam_principals_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_rate_limit_events
    ADD CONSTRAINT iam_rate_limit_events_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_refresh_tokens
    ADD CONSTRAINT iam_refresh_tokens_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_reset_approval_requests
    ADD CONSTRAINT iam_reset_approval_requests_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_role_permissions
    ADD CONSTRAINT iam_role_permissions_pkey PRIMARY KEY (role_id, permission);

ALTER TABLE public.iam_roles
    ADD CONSTRAINT iam_roles_name_key UNIQUE (name);

ALTER TABLE public.iam_roles
    ADD CONSTRAINT iam_roles_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_service_accounts
    ADD CONSTRAINT iam_service_accounts_code_key UNIQUE (code);

ALTER TABLE public.iam_service_accounts
    ADD CONSTRAINT iam_service_accounts_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_user_mfa_methods
    ADD CONSTRAINT iam_user_mfa_methods_pkey PRIMARY KEY (id);

ALTER TABLE public.iam_user_mfa_recovery_codes
    ADD CONSTRAINT iam_user_mfa_recovery_codes_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_connections
    ADD CONSTRAINT msg_connections_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_dispatch_job_attempts
    ADD CONSTRAINT msg_dispatch_job_attempts_pkey PRIMARY KEY (id, created_at);

ALTER TABLE public.msg_dispatch_job_projection_feed
    ADD CONSTRAINT msg_dispatch_job_projection_feed_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_dispatch_jobs
    ADD CONSTRAINT msg_dispatch_jobs_pkey PRIMARY KEY (id, created_at);

ALTER TABLE public.msg_dispatch_jobs_read
    ADD CONSTRAINT msg_dispatch_jobs_read_pkey PRIMARY KEY (id, created_at);

ALTER TABLE public.msg_dispatch_pools
    ADD CONSTRAINT msg_dispatch_pools_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_event_projection_feed
    ADD CONSTRAINT msg_event_projection_feed_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_event_type_spec_versions
    ADD CONSTRAINT msg_event_type_spec_versions_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_event_types
    ADD CONSTRAINT msg_event_types_code_key UNIQUE (code);

ALTER TABLE public.msg_event_types
    ADD CONSTRAINT msg_event_types_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_events
    ADD CONSTRAINT msg_events_pkey PRIMARY KEY (id, created_at);

ALTER TABLE public.msg_events_read
    ADD CONSTRAINT msg_events_read_pkey PRIMARY KEY (id, created_at);

ALTER TABLE public.msg_processes
    ADD CONSTRAINT msg_processes_code_key UNIQUE (code);

ALTER TABLE public.msg_processes
    ADD CONSTRAINT msg_processes_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_scheduled_job_instance_logs
    ADD CONSTRAINT msg_scheduled_job_instance_logs_pkey PRIMARY KEY (id, created_at);

ALTER TABLE public.msg_scheduled_job_instances
    ADD CONSTRAINT msg_scheduled_job_instances_pkey PRIMARY KEY (id, created_at);

ALTER TABLE public.msg_scheduled_jobs
    ADD CONSTRAINT msg_scheduled_jobs_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_subscription_custom_configs
    ADD CONSTRAINT msg_subscription_custom_configs_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_subscription_event_types
    ADD CONSTRAINT msg_subscription_event_types_pkey PRIMARY KEY (id);

ALTER TABLE public.msg_subscriptions
    ADD CONSTRAINT msg_subscriptions_pkey PRIMARY KEY (id);

ALTER TABLE public.oauth_client_allowed_origins
    ADD CONSTRAINT oauth_client_allowed_origins_pkey PRIMARY KEY (oauth_client_id, allowed_origin);

ALTER TABLE public.oauth_client_application_ids
    ADD CONSTRAINT oauth_client_application_ids_pkey PRIMARY KEY (oauth_client_id, application_id);

ALTER TABLE public.oauth_client_grant_types
    ADD CONSTRAINT oauth_client_grant_types_pkey PRIMARY KEY (oauth_client_id, grant_type);

ALTER TABLE public.oauth_client_post_logout_redirect_uris
    ADD CONSTRAINT oauth_client_post_logout_redirect_uris_pkey PRIMARY KEY (oauth_client_id, post_logout_redirect_uri);

ALTER TABLE public.oauth_client_redirect_uris
    ADD CONSTRAINT oauth_client_redirect_uris_pkey PRIMARY KEY (oauth_client_id, redirect_uri);

ALTER TABLE public.oauth_clients
    ADD CONSTRAINT oauth_clients_client_id_key UNIQUE (client_id);

ALTER TABLE public.oauth_clients
    ADD CONSTRAINT oauth_clients_pkey PRIMARY KEY (id);

ALTER TABLE public.oauth_identity_provider_allowed_domains
    ADD CONSTRAINT oauth_identity_provider_allowed_domains_pkey PRIMARY KEY (id);

ALTER TABLE public.oauth_identity_provider_allowed_roles
    ADD CONSTRAINT oauth_identity_provider_allowed_roles_pkey PRIMARY KEY (id);

ALTER TABLE public.oauth_identity_providers
    ADD CONSTRAINT oauth_identity_providers_pkey PRIMARY KEY (id);

ALTER TABLE public.oauth_idp_role_mappings
    ADD CONSTRAINT oauth_idp_role_mappings_pkey PRIMARY KEY (id);

ALTER TABLE public.oauth_oidc_login_states
    ADD CONSTRAINT oauth_oidc_login_states_pkey PRIMARY KEY (state);

ALTER TABLE public.oauth_oidc_payloads
    ADD CONSTRAINT oauth_oidc_payloads_pkey PRIMARY KEY (id);

ALTER TABLE public.portal_identities
    ADD CONSTRAINT portal_identities_pkey PRIMARY KEY (id);

ALTER TABLE public.portal_login_flows
    ADD CONSTRAINT portal_login_flows_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_anchor_domains
    ADD CONSTRAINT tnt_anchor_domains_domain_key UNIQUE (domain);

ALTER TABLE public.tnt_anchor_domains
    ADD CONSTRAINT tnt_anchor_domains_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_client_auth_configs
    ADD CONSTRAINT tnt_client_auth_configs_email_domain_key UNIQUE (email_domain);

ALTER TABLE public.tnt_client_auth_configs
    ADD CONSTRAINT tnt_client_auth_configs_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_clients
    ADD CONSTRAINT tnt_clients_identifier_key UNIQUE (identifier);

ALTER TABLE public.tnt_clients
    ADD CONSTRAINT tnt_clients_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_cors_allowed_origins
    ADD CONSTRAINT tnt_cors_allowed_origins_origin_key UNIQUE (origin);

ALTER TABLE public.tnt_cors_allowed_origins
    ADD CONSTRAINT tnt_cors_allowed_origins_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_email_domain_mapping_2fa_methods
    ADD CONSTRAINT tnt_email_domain_mapping_2fa_methods_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_email_domain_mapping_additional_clients
    ADD CONSTRAINT tnt_email_domain_mapping_additional_clients_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_email_domain_mapping_allowed_roles
    ADD CONSTRAINT tnt_email_domain_mapping_allowed_roles_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_email_domain_mapping_granted_clients
    ADD CONSTRAINT tnt_email_domain_mapping_granted_clients_pkey PRIMARY KEY (id);

ALTER TABLE public.tnt_email_domain_mappings
    ADD CONSTRAINT tnt_email_domain_mappings_pkey PRIMARY KEY (id);

ALTER TABLE public.portal_identities
    ADD CONSTRAINT uq_portal_identities_client_email UNIQUE (client_id, email);

ALTER TABLE public.webauthn_credentials
    ADD CONSTRAINT webauthn_credentials_credential_id_key UNIQUE (credential_id);

ALTER TABLE public.webauthn_credentials
    ADD CONSTRAINT webauthn_credentials_pkey PRIMARY KEY (id);


-- ============================================================================
-- Indexes
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_app_applications_active ON public.app_applications USING btree (active);

CREATE INDEX IF NOT EXISTS idx_app_applications_code ON public.app_applications USING btree (code);

CREATE INDEX IF NOT EXISTS idx_app_applications_service_account_id ON public.app_applications USING btree (service_account_id) WHERE (service_account_id IS NOT NULL);

CREATE INDEX IF NOT EXISTS idx_app_applications_type ON public.app_applications USING btree (type);

CREATE INDEX IF NOT EXISTS idx_app_client_configs_app ON public.app_client_configs USING btree (application_id);

CREATE INDEX IF NOT EXISTS idx_app_client_configs_clt ON public.app_client_configs USING btree (client_id);

CREATE INDEX IF NOT EXISTS idx_app_config_access_app ON public.app_platform_config_access USING btree (application_code);

CREATE INDEX IF NOT EXISTS idx_app_config_access_role ON public.app_platform_config_access USING btree (role_code);

CREATE INDEX IF NOT EXISTS idx_app_docs_application ON public.app_docs USING btree (application_id, "position");

CREATE INDEX IF NOT EXISTS idx_app_openapi_app ON public.app_application_openapi_specs USING btree (application_id, synced_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_openapi_one_current ON public.app_application_openapi_specs USING btree (application_id) WHERE ((status)::text = 'CURRENT'::text);

CREATE INDEX IF NOT EXISTS idx_app_platform_configs_app_section ON public.app_platform_configs USING btree (application_code, section);

CREATE INDEX IF NOT EXISTS idx_app_platform_configs_lookup ON public.app_platform_configs USING btree (application_code, section, scope, client_id);

CREATE INDEX IF NOT EXISTS idx_aud_logs_application_id ON public.aud_logs USING btree (application_id);

CREATE INDEX IF NOT EXISTS idx_aud_logs_client_id ON public.aud_logs USING btree (client_id);

CREATE INDEX IF NOT EXISTS idx_aud_logs_entity ON public.aud_logs USING btree (entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_aud_logs_operation ON public.aud_logs USING btree (operation);

CREATE INDEX IF NOT EXISTS idx_aud_logs_performed ON public.aud_logs USING btree (performed_at);

CREATE INDEX IF NOT EXISTS idx_aud_logs_principal ON public.aud_logs USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_dispatch_jobs_blocked_groups ON public.msg_dispatch_jobs USING btree (message_group, status) WHERE ((status)::text = ANY ((ARRAY['FAILED'::character varying, 'ERROR'::character varying])::text[]));

CREATE INDEX IF NOT EXISTS idx_dispatch_jobs_pending_poll ON public.msg_dispatch_jobs USING btree (message_group, sequence, created_at) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX IF NOT EXISTS idx_dispatch_jobs_stale_queued ON public.msg_dispatch_jobs USING btree (queued_at) WHERE ((status)::text = 'QUEUED'::text);

CREATE INDEX IF NOT EXISTS idx_iam_auth_codes_client ON public.iam_authorization_codes USING btree (client_id);

CREATE INDEX IF NOT EXISTS idx_iam_auth_codes_expires ON public.iam_authorization_codes USING btree (expires_at);

CREATE INDEX IF NOT EXISTS idx_iam_auth_codes_principal ON public.iam_authorization_codes USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_iam_client_access_grants_client ON public.iam_client_access_grants USING btree (client_id);

CREATE INDEX IF NOT EXISTS idx_iam_client_access_grants_principal ON public.iam_client_access_grants USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_iam_login_attempts_at ON public.iam_login_attempts USING btree (attempted_at);

CREATE INDEX IF NOT EXISTS idx_iam_login_attempts_failure_throttle ON public.iam_login_attempts USING btree (identifier, attempted_at) WHERE ((outcome)::text = 'FAILURE'::text);

CREATE INDEX IF NOT EXISTS idx_iam_login_attempts_identifier ON public.iam_login_attempts USING btree (identifier);

CREATE INDEX IF NOT EXISTS idx_iam_login_attempts_outcome ON public.iam_login_attempts USING btree (outcome);

CREATE INDEX IF NOT EXISTS idx_iam_login_attempts_principal ON public.iam_login_attempts USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_iam_login_attempts_type ON public.iam_login_attempts USING btree (attempt_type);

CREATE INDEX IF NOT EXISTS idx_iam_mfa_email_pins_expires ON public.iam_mfa_email_pins USING btree (expires_at);

CREATE INDEX IF NOT EXISTS idx_iam_mfa_email_pins_principal ON public.iam_mfa_email_pins USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_iam_mfa_trusted_devices_hash ON public.iam_mfa_trusted_devices USING btree (token_hash);

CREATE INDEX IF NOT EXISTS idx_iam_mfa_trusted_devices_principal ON public.iam_mfa_trusted_devices USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_iam_oidc_login_states_expires ON public.iam_oidc_login_states USING btree (expires_at);

CREATE INDEX IF NOT EXISTS idx_iam_password_reset_principal ON public.iam_password_reset_tokens USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_iam_password_reset_token_hash ON public.iam_password_reset_tokens USING btree (token_hash);

CREATE INDEX IF NOT EXISTS idx_iam_permissions_code ON public.iam_permissions USING btree (code);

CREATE INDEX IF NOT EXISTS idx_iam_permissions_context ON public.iam_permissions USING btree (context);

CREATE INDEX IF NOT EXISTS idx_iam_permissions_subdomain ON public.iam_permissions USING btree (subdomain);

CREATE INDEX IF NOT EXISTS idx_iam_principal_app_access_app_id ON public.iam_principal_application_access USING btree (application_id);

CREATE INDEX IF NOT EXISTS idx_iam_principal_roles_assigned_at ON public.iam_principal_roles USING btree (assigned_at);

CREATE INDEX IF NOT EXISTS idx_iam_principal_roles_role_name ON public.iam_principal_roles USING btree (role_name);

CREATE INDEX IF NOT EXISTS idx_iam_principals_active ON public.iam_principals USING btree (active);

CREATE INDEX IF NOT EXISTS idx_iam_principals_client_id ON public.iam_principals USING btree (client_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_iam_principals_email ON public.iam_principals USING btree (email);

CREATE INDEX IF NOT EXISTS idx_iam_principals_email_domain ON public.iam_principals USING btree (email_domain);

CREATE UNIQUE INDEX IF NOT EXISTS idx_iam_principals_service_account_id ON public.iam_principals USING btree (service_account_id);

CREATE INDEX IF NOT EXISTS idx_iam_principals_type ON public.iam_principals USING btree (type);

CREATE INDEX IF NOT EXISTS idx_iam_rate_limit_events_lookup ON public.iam_rate_limit_events USING btree (bucket, key, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_iam_rate_limit_events_occurred_at ON public.iam_rate_limit_events USING btree (occurred_at);

CREATE INDEX IF NOT EXISTS idx_iam_refresh_tokens_expires ON public.iam_refresh_tokens USING btree (expires_at);

CREATE INDEX IF NOT EXISTS idx_iam_refresh_tokens_family ON public.iam_refresh_tokens USING btree (token_family);

CREATE INDEX IF NOT EXISTS idx_iam_refresh_tokens_hash ON public.iam_refresh_tokens USING btree (token_hash);

CREATE INDEX IF NOT EXISTS idx_iam_refresh_tokens_principal ON public.iam_refresh_tokens USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_iam_refresh_tokens_revoked ON public.iam_refresh_tokens USING btree (revoked);

CREATE INDEX IF NOT EXISTS idx_iam_reset_approval_client_status ON public.iam_reset_approval_requests USING btree (client_id, status);

CREATE INDEX IF NOT EXISTS idx_iam_reset_approval_principal ON public.iam_reset_approval_requests USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_iam_role_permissions_role_id ON public.iam_role_permissions USING btree (role_id);

CREATE INDEX IF NOT EXISTS idx_iam_roles_application_code ON public.iam_roles USING btree (application_code);

CREATE INDEX IF NOT EXISTS idx_iam_roles_application_id ON public.iam_roles USING btree (application_id);

CREATE INDEX IF NOT EXISTS idx_iam_roles_client_managed ON public.iam_roles USING btree (client_managed);

CREATE INDEX IF NOT EXISTS idx_iam_roles_name ON public.iam_roles USING btree (name);

CREATE INDEX IF NOT EXISTS idx_iam_roles_source ON public.iam_roles USING btree (source);

CREATE INDEX IF NOT EXISTS idx_iam_service_accounts_active ON public.iam_service_accounts USING btree (active);

CREATE INDEX IF NOT EXISTS idx_iam_service_accounts_application_id ON public.iam_service_accounts USING btree (application_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_iam_service_accounts_code ON public.iam_service_accounts USING btree (code);

CREATE INDEX IF NOT EXISTS idx_iam_user_mfa_methods_principal ON public.iam_user_mfa_methods USING btree (principal_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_iam_user_mfa_methods_principal_method ON public.iam_user_mfa_methods USING btree (principal_id, method);

CREATE INDEX IF NOT EXISTS idx_iam_user_mfa_recovery_codes_hash ON public.iam_user_mfa_recovery_codes USING btree (code_hash);

CREATE INDEX IF NOT EXISTS idx_iam_user_mfa_recovery_codes_principal ON public.iam_user_mfa_recovery_codes USING btree (principal_id);

CREATE INDEX IF NOT EXISTS idx_msg_connections_client_id ON public.msg_connections USING btree (client_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_connections_code_client ON public.msg_connections USING btree (code, client_id);

CREATE INDEX IF NOT EXISTS idx_msg_connections_service_account ON public.msg_connections USING btree (service_account_id);

CREATE INDEX IF NOT EXISTS idx_msg_connections_status ON public.msg_connections USING btree (status);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_job_attempts_job ON public.msg_dispatch_job_attempts USING btree (dispatch_job_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_dispatch_job_attempts_job_number ON public.msg_dispatch_job_attempts USING btree (dispatch_job_id, attempt_number, created_at);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_dirty ON public.msg_dispatch_jobs USING btree (created_at) WHERE ((projected_at IS NULL) OR (updated_at > projected_at));

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_application ON public.msg_dispatch_jobs_read USING btree (application);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_client_created ON public.msg_dispatch_jobs_read USING btree (client_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_code ON public.msg_dispatch_jobs_read USING btree (code);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_created_at ON public.msg_dispatch_jobs_read USING btree (created_at);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_dispatch_pool_id ON public.msg_dispatch_jobs_read USING btree (dispatch_pool_id);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_event_id ON public.msg_dispatch_jobs_read USING btree (event_id);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_message_group ON public.msg_dispatch_jobs_read USING btree (message_group);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_status_created ON public.msg_dispatch_jobs_read USING btree (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_jobs_read_subscription_id ON public.msg_dispatch_jobs_read USING btree (subscription_id);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_pools_client_id ON public.msg_dispatch_pools USING btree (client_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_dispatch_pools_code_client ON public.msg_dispatch_pools USING btree (code, client_id);

CREATE INDEX IF NOT EXISTS idx_msg_dispatch_pools_status ON public.msg_dispatch_pools USING btree (status);

CREATE INDEX IF NOT EXISTS idx_msg_dj_projection_feed_in_progress ON public.msg_dispatch_job_projection_feed USING btree (id) WHERE (processed = 9);

CREATE INDEX IF NOT EXISTS idx_msg_dj_projection_feed_processed_at ON public.msg_dispatch_job_projection_feed USING btree (processed_at) WHERE (processed = 1);

CREATE INDEX IF NOT EXISTS idx_msg_dj_projection_feed_unprocessed ON public.msg_dispatch_job_projection_feed USING btree (dispatch_job_id, id) WHERE (processed = 0);

CREATE INDEX IF NOT EXISTS idx_msg_event_projection_feed_in_progress ON public.msg_event_projection_feed USING btree (id) WHERE (processed = 9);

CREATE INDEX IF NOT EXISTS idx_msg_event_projection_feed_unprocessed ON public.msg_event_projection_feed USING btree (id) WHERE (processed = 0);

CREATE INDEX IF NOT EXISTS idx_msg_event_types_aggregate ON public.msg_event_types USING btree (aggregate);

CREATE INDEX IF NOT EXISTS idx_msg_event_types_application ON public.msg_event_types USING btree (application);

CREATE INDEX IF NOT EXISTS idx_msg_event_types_code ON public.msg_event_types USING btree (code);

CREATE INDEX IF NOT EXISTS idx_msg_event_types_source ON public.msg_event_types USING btree (source);

CREATE INDEX IF NOT EXISTS idx_msg_event_types_status ON public.msg_event_types USING btree (status);

CREATE INDEX IF NOT EXISTS idx_msg_event_types_subdomain ON public.msg_event_types USING btree (subdomain);

CREATE INDEX IF NOT EXISTS idx_msg_events_client_id ON public.msg_events USING btree (client_id);

CREATE INDEX IF NOT EXISTS idx_msg_events_created_at ON public.msg_events USING btree (created_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_events_deduplication ON public.msg_events USING btree (deduplication_id, created_at);

CREATE INDEX IF NOT EXISTS idx_msg_events_read_aggregate ON public.msg_events_read USING btree (aggregate);

CREATE INDEX IF NOT EXISTS idx_msg_events_read_application ON public.msg_events_read USING btree (application);

CREATE INDEX IF NOT EXISTS idx_msg_events_read_client_created ON public.msg_events_read USING btree (client_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_msg_events_read_correlation_id ON public.msg_events_read USING btree (correlation_id);

CREATE INDEX IF NOT EXISTS idx_msg_events_read_created_at ON public.msg_events_read USING btree (created_at);

CREATE INDEX IF NOT EXISTS idx_msg_events_read_subdomain ON public.msg_events_read USING btree (subdomain);

CREATE INDEX IF NOT EXISTS idx_msg_events_read_type_created ON public.msg_events_read USING btree (type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_msg_events_unfanned ON public.msg_events USING btree (created_at) WHERE (fanned_out_at IS NULL);

CREATE INDEX IF NOT EXISTS idx_msg_events_unprojected ON public.msg_events USING btree (created_at) WHERE (projected_at IS NULL);

CREATE INDEX IF NOT EXISTS idx_msg_processes_application ON public.msg_processes USING btree (application);

CREATE INDEX IF NOT EXISTS idx_msg_processes_source ON public.msg_processes USING btree (source);

CREATE INDEX IF NOT EXISTS idx_msg_processes_status ON public.msg_processes USING btree (status);

CREATE INDEX IF NOT EXISTS idx_msg_processes_subdomain ON public.msg_processes USING btree (subdomain);

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_job_instance_logs_instance ON public.msg_scheduled_job_instance_logs USING btree (instance_id, created_at);

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_job_instance_logs_job ON public.msg_scheduled_job_instance_logs USING btree (scheduled_job_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_job_instances_active ON public.msg_scheduled_job_instances USING btree (scheduled_job_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying])::text[]));

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_job_instances_client ON public.msg_scheduled_job_instances USING btree (client_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_job_instances_job ON public.msg_scheduled_job_instances USING btree (scheduled_job_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_job_instances_status ON public.msg_scheduled_job_instances USING btree (status, created_at);

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_jobs_active_poll ON public.msg_scheduled_jobs USING btree (last_fired_at NULLS FIRST) WHERE ((status)::text = 'ACTIVE'::text);

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_jobs_application_id ON public.msg_scheduled_jobs USING btree (application_id);

CREATE INDEX IF NOT EXISTS idx_msg_scheduled_jobs_client_id ON public.msg_scheduled_jobs USING btree (client_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_scheduled_jobs_code_per_client ON public.msg_scheduled_jobs USING btree (client_id, code) WHERE (client_id IS NOT NULL);

CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_scheduled_jobs_code_platform ON public.msg_scheduled_jobs USING btree (code) WHERE (client_id IS NULL);

CREATE INDEX IF NOT EXISTS idx_msg_spec_versions_event_type ON public.msg_event_type_spec_versions USING btree (event_type_id);

CREATE INDEX IF NOT EXISTS idx_msg_spec_versions_status ON public.msg_event_type_spec_versions USING btree (status);

CREATE INDEX IF NOT EXISTS idx_msg_sub_configs_subscription ON public.msg_subscription_custom_configs USING btree (subscription_id);

CREATE INDEX IF NOT EXISTS idx_msg_sub_event_types_event_type ON public.msg_subscription_event_types USING btree (event_type_id);

CREATE INDEX IF NOT EXISTS idx_msg_sub_event_types_subscription ON public.msg_subscription_event_types USING btree (subscription_id);

CREATE INDEX IF NOT EXISTS idx_msg_subscriptions_client_id ON public.msg_subscriptions USING btree (client_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_subscriptions_code_client ON public.msg_subscriptions USING btree (code, client_id);

CREATE INDEX IF NOT EXISTS idx_msg_subscriptions_connection_id ON public.msg_subscriptions USING btree (connection_id);

CREATE INDEX IF NOT EXISTS idx_msg_subscriptions_dispatch_pool ON public.msg_subscriptions USING btree (dispatch_pool_id);

CREATE INDEX IF NOT EXISTS idx_msg_subscriptions_source ON public.msg_subscriptions USING btree (source);

CREATE INDEX IF NOT EXISTS idx_msg_subscriptions_status ON public.msg_subscriptions USING btree (status);

CREATE INDEX IF NOT EXISTS idx_oauth_client_allowed_origins_client ON public.oauth_client_allowed_origins USING btree (oauth_client_id);

CREATE INDEX IF NOT EXISTS idx_oauth_client_allowed_origins_origin ON public.oauth_client_allowed_origins USING btree (allowed_origin);

CREATE INDEX IF NOT EXISTS idx_oauth_client_application_ids_client ON public.oauth_client_application_ids USING btree (oauth_client_id);

CREATE INDEX IF NOT EXISTS idx_oauth_client_grant_types_client ON public.oauth_client_grant_types USING btree (oauth_client_id);

CREATE INDEX IF NOT EXISTS idx_oauth_client_post_logout_redirect_uris_client ON public.oauth_client_post_logout_redirect_uris USING btree (oauth_client_id);

CREATE INDEX IF NOT EXISTS idx_oauth_client_redirect_uris_client ON public.oauth_client_redirect_uris USING btree (oauth_client_id);

CREATE INDEX IF NOT EXISTS idx_oauth_clients_service_account_principal ON public.oauth_clients USING btree (service_account_principal_id) WHERE (service_account_principal_id IS NOT NULL);

CREATE UNIQUE INDEX IF NOT EXISTS idx_oauth_identity_providers_code ON public.oauth_identity_providers USING btree (code);

CREATE INDEX IF NOT EXISTS idx_oauth_idp_allowed_domains_idp ON public.oauth_identity_provider_allowed_domains USING btree (identity_provider_id);

CREATE INDEX IF NOT EXISTS idx_oauth_idp_allowed_roles_idp ON public.oauth_identity_provider_allowed_roles USING btree (identity_provider_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_oauth_idp_role_mappings_idp_role_name ON public.oauth_idp_role_mappings USING btree (idp_role_name);

CREATE INDEX IF NOT EXISTS idx_oauth_oidc_login_states_expires ON public.oauth_oidc_login_states USING btree (expires_at);

CREATE INDEX IF NOT EXISTS idx_portal_identities_email ON public.portal_identities USING btree (email);

CREATE INDEX IF NOT EXISTS idx_tnt_clients_identifier ON public.tnt_clients USING btree (identifier);

CREATE INDEX IF NOT EXISTS idx_tnt_clients_status ON public.tnt_clients USING btree (status);

CREATE INDEX IF NOT EXISTS idx_tnt_edm_2fa_methods_mapping ON public.tnt_email_domain_mapping_2fa_methods USING btree (email_domain_mapping_id);

CREATE INDEX IF NOT EXISTS idx_tnt_edm_additional_clients_mapping ON public.tnt_email_domain_mapping_additional_clients USING btree (email_domain_mapping_id);

CREATE INDEX IF NOT EXISTS idx_tnt_edm_allowed_roles_mapping ON public.tnt_email_domain_mapping_allowed_roles USING btree (email_domain_mapping_id);

CREATE INDEX IF NOT EXISTS idx_tnt_edm_granted_clients_mapping ON public.tnt_email_domain_mapping_granted_clients USING btree (email_domain_mapping_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_tnt_email_domain_mappings_domain ON public.tnt_email_domain_mappings USING btree (email_domain);

CREATE INDEX IF NOT EXISTS idx_tnt_email_domain_mappings_idp ON public.tnt_email_domain_mappings USING btree (identity_provider_id);

CREATE INDEX IF NOT EXISTS idx_tnt_email_domain_mappings_scope ON public.tnt_email_domain_mappings USING btree (scope_type);

CREATE INDEX IF NOT EXISTS idx_webauthn_credentials_principal ON public.webauthn_credentials USING btree (principal_id);

CREATE INDEX IF NOT EXISTS oauth_clients_active_idx ON public.oauth_clients USING btree (active);

CREATE INDEX IF NOT EXISTS oauth_clients_client_id_idx ON public.oauth_clients USING btree (client_id);

CREATE INDEX IF NOT EXISTS oauth_oidc_payloads_expires_at_idx ON public.oauth_oidc_payloads USING btree (expires_at);

CREATE INDEX IF NOT EXISTS oauth_oidc_payloads_grant_id_idx ON public.oauth_oidc_payloads USING btree (grant_id);

CREATE INDEX IF NOT EXISTS oauth_oidc_payloads_type_idx ON public.oauth_oidc_payloads USING btree (type);

CREATE INDEX IF NOT EXISTS oauth_oidc_payloads_uid_idx ON public.oauth_oidc_payloads USING btree (uid);

CREATE INDEX IF NOT EXISTS oauth_oidc_payloads_user_code_idx ON public.oauth_oidc_payloads USING btree (user_code);

CREATE INDEX IF NOT EXISTS tnt_anchor_domains_domain_idx ON public.tnt_anchor_domains USING btree (domain);

CREATE INDEX IF NOT EXISTS tnt_client_auth_configs_config_type_idx ON public.tnt_client_auth_configs USING btree (config_type);

CREATE INDEX IF NOT EXISTS tnt_client_auth_configs_email_domain_idx ON public.tnt_client_auth_configs USING btree (email_domain);

CREATE INDEX IF NOT EXISTS tnt_client_auth_configs_primary_client_id_idx ON public.tnt_client_auth_configs USING btree (primary_client_id);

CREATE INDEX IF NOT EXISTS tnt_cors_allowed_origins_origin_idx ON public.tnt_cors_allowed_origins USING btree (origin);

CREATE UNIQUE INDEX IF NOT EXISTS uq_app_client_configs_app_clt ON public.app_client_configs USING btree (application_id, client_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_app_config_access_role ON public.app_platform_config_access USING btree (application_code, role_code);

CREATE UNIQUE INDEX IF NOT EXISTS uq_app_platform_config_key ON public.app_platform_configs USING btree (application_code, section, property, scope, client_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_iam_client_access_grants_principal_client ON public.iam_client_access_grants USING btree (principal_id, client_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_msg_spec_versions_event_type_version ON public.msg_event_type_spec_versions USING btree (event_type_id, version);


-- ============================================================================
-- Foreign keys
-- ============================================================================

ALTER TABLE public.app_application_openapi_specs
    ADD CONSTRAINT app_application_openapi_specs_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.app_applications(id) ON DELETE CASCADE;

ALTER TABLE public.app_applications
    ADD CONSTRAINT app_applications_service_account_fk FOREIGN KEY (service_account_id) REFERENCES public.iam_principals(id) ON DELETE SET NULL;

ALTER TABLE public.oauth_client_post_logout_redirect_uris
    ADD CONSTRAINT fk_oauth_client_post_logout_redirect_uris_client FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;

ALTER TABLE public.iam_mfa_email_pins
    ADD CONSTRAINT iam_mfa_email_pins_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;

ALTER TABLE public.iam_mfa_trusted_devices
    ADD CONSTRAINT iam_mfa_trusted_devices_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;

ALTER TABLE public.iam_principal_roles
    ADD CONSTRAINT iam_principal_roles_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;

ALTER TABLE public.iam_reset_approval_requests
    ADD CONSTRAINT iam_reset_approval_requests_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;

ALTER TABLE public.iam_role_permissions
    ADD CONSTRAINT iam_role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.iam_roles(id) ON DELETE CASCADE;

ALTER TABLE public.iam_user_mfa_methods
    ADD CONSTRAINT iam_user_mfa_methods_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;

ALTER TABLE public.iam_user_mfa_recovery_codes
    ADD CONSTRAINT iam_user_mfa_recovery_codes_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;

ALTER TABLE public.oauth_client_allowed_origins
    ADD CONSTRAINT oauth_client_allowed_origins_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;

ALTER TABLE public.oauth_client_application_ids
    ADD CONSTRAINT oauth_client_application_ids_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;

ALTER TABLE public.oauth_client_grant_types
    ADD CONSTRAINT oauth_client_grant_types_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;

ALTER TABLE public.oauth_client_redirect_uris
    ADD CONSTRAINT oauth_client_redirect_uris_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;

ALTER TABLE public.oauth_clients
    ADD CONSTRAINT oauth_clients_service_account_fk FOREIGN KEY (service_account_principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;

ALTER TABLE public.webauthn_credentials
    ADD CONSTRAINT webauthn_credentials_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--


-- ============================================================================
-- Initial monthly partitions (port of Go migrations 019 + 022)
-- ============================================================================
-- Creates `<parent>_YYYY_MM` RANGE partitions covering (this month - 1)
-- through (this month + 3) for each partitioned parent. Idempotent.
-- Note: month boundaries are computed in the session time zone, as in Go.

DO $partitions$
DECLARE
    parent_table TEXT;
    parents TEXT[] := ARRAY[
        'msg_events',
        'msg_events_read',
        'msg_dispatch_jobs',
        'msg_dispatch_jobs_read',
        'msg_dispatch_job_attempts',
        'msg_scheduled_job_instances',
        'msg_scheduled_job_instance_logs'
    ];
    m INTEGER;
    months_back CONSTANT INTEGER := 1;
    months_forward CONSTANT INTEGER := 3;
    start_ts TIMESTAMPTZ;
    end_ts TIMESTAMPTZ;
    partition_name TEXT;
BEGIN
    FOREACH parent_table IN ARRAY parents LOOP
        FOR m IN -months_back..months_forward LOOP
            start_ts := date_trunc('month', NOW()) + (m || ' months')::INTERVAL;
            end_ts := start_ts + INTERVAL '1 month';
            partition_name := parent_table || '_' || to_char(start_ts, 'YYYY_MM');

            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.%I FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                parent_table,
                start_ts,
                end_ts
            );
        END LOOP;
    END LOOP;
END
$partitions$;
