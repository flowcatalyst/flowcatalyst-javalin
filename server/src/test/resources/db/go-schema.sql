--
-- PostgreSQL database dump
--

\restrict eOHt7xtG85gR3etO6NCuM6pZeybwjREWWazHWdd63iCFKUZTfVkztZvd5f02WtR

-- Dumped from database version 18.4 (Debian 18.4-1.pgdg13+1)
-- Dumped by pg_dump version 18.4 (Debian 18.4-1.pgdg13+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: app_application_openapi_specs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_application_openapi_specs (
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
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_app_application_openapi_specs_status CHECK (((status)::text = ANY ((ARRAY['CURRENT'::character varying, 'ARCHIVED'::character varying])::text[])))
);


--
-- Name: app_applications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_applications (
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
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_app_applications_type CHECK (((type)::text = ANY ((ARRAY['APPLICATION'::character varying, 'INTEGRATION'::character varying])::text[])))
);


--
-- Name: app_client_configs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_client_configs (
    id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL,
    client_id character varying(17) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: app_docs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_docs (
    id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL,
    slug character varying(120) NOT NULL,
    title character varying(200) NOT NULL,
    content text NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: app_platform_config_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_platform_config_access (
    id character varying(17) NOT NULL,
    application_code character varying(100) NOT NULL,
    role_code character varying(200) NOT NULL,
    can_read boolean DEFAULT true NOT NULL,
    can_write boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: app_platform_configs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_platform_configs (
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
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_app_platform_configs_scope CHECK (((scope)::text = ANY ((ARRAY['GLOBAL'::character varying, 'CLIENT'::character varying])::text[]))),
    CONSTRAINT chk_app_platform_configs_value_type CHECK (((value_type)::text = ANY ((ARRAY['PLAIN'::character varying, 'SECRET'::character varying])::text[])))
);


--
-- Name: aud_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.aud_logs (
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


--
-- Name: goose_db_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.goose_db_version (
    id integer NOT NULL,
    version_id bigint NOT NULL,
    is_applied boolean NOT NULL,
    tstamp timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: goose_db_version_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.goose_db_version ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.goose_db_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: iam_authorization_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_authorization_codes (
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


--
-- Name: iam_client_access_grants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_client_access_grants (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    client_id character varying(17) NOT NULL,
    granted_by character varying(17) NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: iam_login_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_login_attempts (
    id character varying(17) CONSTRAINT iam_login_attempts_new_id_not_null NOT NULL,
    attempt_type character varying(30) CONSTRAINT iam_login_attempts_new_attempt_type_not_null NOT NULL,
    outcome character varying(20) CONSTRAINT iam_login_attempts_new_outcome_not_null NOT NULL,
    failure_reason character varying(100),
    identifier character varying(255),
    principal_id character varying(17),
    ip_address character varying(45),
    user_agent text,
    attempted_at timestamp with time zone DEFAULT now() CONSTRAINT iam_login_attempts_new_attempted_at_not_null NOT NULL,
    CONSTRAINT chk_iam_login_attempts_outcome CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying])::text[])))
)
PARTITION BY RANGE (attempted_at);


--
-- Name: iam_login_attempts_2026_q3; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_login_attempts_2026_q3 (
    id character varying(17) CONSTRAINT iam_login_attempts_new_id_not_null NOT NULL,
    attempt_type character varying(30) CONSTRAINT iam_login_attempts_new_attempt_type_not_null NOT NULL,
    outcome character varying(20) CONSTRAINT iam_login_attempts_new_outcome_not_null NOT NULL,
    failure_reason character varying(100),
    identifier character varying(255),
    principal_id character varying(17),
    ip_address character varying(45),
    user_agent text,
    attempted_at timestamp with time zone DEFAULT now() CONSTRAINT iam_login_attempts_new_attempted_at_not_null NOT NULL,
    CONSTRAINT chk_iam_login_attempts_outcome CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying])::text[])))
);


--
-- Name: iam_login_attempts_2026_q4; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_login_attempts_2026_q4 (
    id character varying(17) CONSTRAINT iam_login_attempts_new_id_not_null NOT NULL,
    attempt_type character varying(30) CONSTRAINT iam_login_attempts_new_attempt_type_not_null NOT NULL,
    outcome character varying(20) CONSTRAINT iam_login_attempts_new_outcome_not_null NOT NULL,
    failure_reason character varying(100),
    identifier character varying(255),
    principal_id character varying(17),
    ip_address character varying(45),
    user_agent text,
    attempted_at timestamp with time zone DEFAULT now() CONSTRAINT iam_login_attempts_new_attempted_at_not_null NOT NULL,
    CONSTRAINT chk_iam_login_attempts_outcome CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying])::text[])))
);


--
-- Name: iam_login_attempts_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_login_attempts_default (
    id character varying(17) CONSTRAINT iam_login_attempts_new_id_not_null NOT NULL,
    attempt_type character varying(30) CONSTRAINT iam_login_attempts_new_attempt_type_not_null NOT NULL,
    outcome character varying(20) CONSTRAINT iam_login_attempts_new_outcome_not_null NOT NULL,
    failure_reason character varying(100),
    identifier character varying(255),
    principal_id character varying(17),
    ip_address character varying(45),
    user_agent text,
    attempted_at timestamp with time zone DEFAULT now() CONSTRAINT iam_login_attempts_new_attempted_at_not_null NOT NULL,
    CONSTRAINT chk_iam_login_attempts_outcome CHECK (((outcome)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAILURE'::character varying])::text[])))
);


--
-- Name: iam_mfa_email_pins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_mfa_email_pins (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    purpose character varying(20) DEFAULT 'login'::character varying NOT NULL,
    pin_hash character varying(64) NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: iam_mfa_trusted_devices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_mfa_trusted_devices (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    token_hash character varying(64) NOT NULL,
    label character varying(255),
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone
);


--
-- Name: iam_oidc_login_states; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_oidc_login_states (
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


--
-- Name: iam_password_reset_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_password_reset_tokens (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    purpose character varying(20) DEFAULT 'reset'::character varying NOT NULL,
    reset_2fa boolean DEFAULT false NOT NULL,
    requires_factor boolean DEFAULT false NOT NULL,
    factor_attempts integer DEFAULT 0 NOT NULL,
    redirect_uri character varying(2000),
    CONSTRAINT chk_iam_password_reset_tokens_purpose CHECK (((purpose)::text = ANY ((ARRAY['reset'::character varying, 'invite'::character varying])::text[])))
);


--
-- Name: iam_permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_permissions (
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


--
-- Name: iam_principal_application_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_principal_application_access (
    principal_id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: iam_principal_roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_principal_roles (
    principal_id character varying(17) NOT NULL,
    role_name character varying(100) NOT NULL,
    assignment_source character varying(50),
    assigned_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: iam_principals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_principals (
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
    dev_client_secret_updated_at timestamp with time zone,
    CONSTRAINT chk_iam_principals_scope CHECK (((scope IS NULL) OR ((scope)::text = ANY ((ARRAY['ANCHOR'::character varying, 'PARTNER'::character varying, 'CLIENT'::character varying])::text[])))),
    CONSTRAINT chk_iam_principals_type CHECK (((type)::text = ANY ((ARRAY['USER'::character varying, 'SERVICE'::character varying])::text[])))
);


--
-- Name: iam_rate_limit_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_rate_limit_events (
    id bigint NOT NULL,
    bucket character varying(64) NOT NULL,
    key text NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: iam_rate_limit_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.iam_rate_limit_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: iam_rate_limit_events_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.iam_rate_limit_events_id_seq OWNED BY public.iam_rate_limit_events.id;


--
-- Name: iam_refresh_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_refresh_tokens (
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


--
-- Name: iam_reset_approval_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_reset_approval_requests (
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


--
-- Name: iam_role_permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_role_permissions (
    role_id character varying(17) NOT NULL,
    permission character varying(255) NOT NULL
);


--
-- Name: iam_roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_roles (
    id character varying(17) NOT NULL,
    application_id character varying(17),
    application_code character varying(50),
    name character varying(255) NOT NULL,
    display_name character varying(255) NOT NULL,
    description text,
    source character varying(50) DEFAULT 'DATABASE'::character varying NOT NULL,
    client_managed boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_iam_roles_source CHECK (((source)::text = ANY ((ARRAY['CODE'::character varying, 'DATABASE'::character varying, 'SDK'::character varying])::text[])))
);


--
-- Name: iam_service_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_service_accounts (
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
    client_ids text[],
    CONSTRAINT chk_iam_service_accounts_wh_auth_type CHECK (((wh_auth_type IS NULL) OR ((wh_auth_type)::text = ANY ((ARRAY['NONE'::character varying, 'BEARER_TOKEN'::character varying, 'BASIC_AUTH'::character varying, 'API_KEY'::character varying, 'HMAC_SIGNATURE'::character varying])::text[]))))
);


--
-- Name: iam_user_mfa_methods; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_user_mfa_methods (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    method character varying(20) NOT NULL,
    secret_encrypted text,
    confirmed_at timestamp with time zone,
    last_used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: iam_user_mfa_recovery_codes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.iam_user_mfa_recovery_codes (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    code_hash character varying(64) NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: msg_connections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_connections (
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
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    application_code character varying(100),
    source character varying(20) DEFAULT 'UI'::character varying NOT NULL,
    CONSTRAINT chk_msg_connections_source CHECK (((source)::text = ANY ((ARRAY['CODE'::character varying, 'API'::character varying, 'UI'::character varying])::text[]))),
    CONSTRAINT chk_msg_connections_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAUSED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_job_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_job_attempts (
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_msg_dispatch_job_attempts_error_type CHECK (((error_type IS NULL) OR ((error_type)::text = ANY ((ARRAY['CONNECTION'::character varying, 'TIMEOUT'::character varying, 'HTTP_ERROR'::character varying, 'VALIDATION'::character varying, 'UNKNOWN'::character varying])::text[]))))
)
PARTITION BY RANGE (created_at);


--
-- Name: msg_dispatch_job_attempts_2026_08; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_job_attempts_2026_08 (
    id character varying(13) CONSTRAINT msg_dispatch_job_attempts_id_not_null NOT NULL,
    dispatch_job_id character varying(13) CONSTRAINT msg_dispatch_job_attempts_dispatch_job_id_not_null NOT NULL,
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
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_job_attempts_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_job_attempts_error_type CHECK (((error_type IS NULL) OR ((error_type)::text = ANY ((ARRAY['CONNECTION'::character varying, 'TIMEOUT'::character varying, 'HTTP_ERROR'::character varying, 'VALIDATION'::character varying, 'UNKNOWN'::character varying])::text[]))))
);


--
-- Name: msg_dispatch_job_attempts_2026_09; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_job_attempts_2026_09 (
    id character varying(13) CONSTRAINT msg_dispatch_job_attempts_id_not_null NOT NULL,
    dispatch_job_id character varying(13) CONSTRAINT msg_dispatch_job_attempts_dispatch_job_id_not_null NOT NULL,
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
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_job_attempts_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_job_attempts_error_type CHECK (((error_type IS NULL) OR ((error_type)::text = ANY ((ARRAY['CONNECTION'::character varying, 'TIMEOUT'::character varying, 'HTTP_ERROR'::character varying, 'VALIDATION'::character varying, 'UNKNOWN'::character varying])::text[]))))
);


--
-- Name: msg_dispatch_job_attempts_2026_10; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_job_attempts_2026_10 (
    id character varying(13) CONSTRAINT msg_dispatch_job_attempts_id_not_null NOT NULL,
    dispatch_job_id character varying(13) CONSTRAINT msg_dispatch_job_attempts_dispatch_job_id_not_null NOT NULL,
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
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_job_attempts_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_job_attempts_error_type CHECK (((error_type IS NULL) OR ((error_type)::text = ANY ((ARRAY['CONNECTION'::character varying, 'TIMEOUT'::character varying, 'HTTP_ERROR'::character varying, 'VALIDATION'::character varying, 'UNKNOWN'::character varying])::text[]))))
);


--
-- Name: msg_dispatch_job_attempts_2026_11; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_job_attempts_2026_11 (
    id character varying(13) CONSTRAINT msg_dispatch_job_attempts_id_not_null NOT NULL,
    dispatch_job_id character varying(13) CONSTRAINT msg_dispatch_job_attempts_dispatch_job_id_not_null NOT NULL,
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
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_job_attempts_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_job_attempts_error_type CHECK (((error_type IS NULL) OR ((error_type)::text = ANY ((ARRAY['CONNECTION'::character varying, 'TIMEOUT'::character varying, 'HTTP_ERROR'::character varying, 'VALIDATION'::character varying, 'UNKNOWN'::character varying])::text[]))))
);


--
-- Name: msg_dispatch_job_attempts_2026_12; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_job_attempts_2026_12 (
    id character varying(13) CONSTRAINT msg_dispatch_job_attempts_id_not_null NOT NULL,
    dispatch_job_id character varying(13) CONSTRAINT msg_dispatch_job_attempts_dispatch_job_id_not_null NOT NULL,
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
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_job_attempts_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_job_attempts_error_type CHECK (((error_type IS NULL) OR ((error_type)::text = ANY ((ARRAY['CONNECTION'::character varying, 'TIMEOUT'::character varying, 'HTTP_ERROR'::character varying, 'VALIDATION'::character varying, 'UNKNOWN'::character varying])::text[]))))
);


--
-- Name: msg_dispatch_job_projection_feed; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_job_projection_feed (
    id bigint NOT NULL,
    dispatch_job_id character varying(13) NOT NULL,
    operation character varying(10) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    processed smallint DEFAULT 0 NOT NULL,
    processed_at timestamp with time zone,
    error_message text
);


--
-- Name: msg_dispatch_job_projection_feed_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.msg_dispatch_job_projection_feed_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: msg_dispatch_job_projection_feed_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.msg_dispatch_job_projection_feed_id_seq OWNED BY public.msg_dispatch_job_projection_feed.id;


--
-- Name: msg_dispatch_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs (
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
    mode character varying(30) DEFAULT 'NEXT_ON_ERROR'::character varying NOT NULL,
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
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    queue character varying(255),
    CONSTRAINT chk_msg_dispatch_jobs_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
)
PARTITION BY RANGE (created_at);


--
-- Name: msg_dispatch_jobs_2026_08; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_2026_08 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) DEFAULT 'EVENT'::character varying CONSTRAINT msg_dispatch_jobs_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    metadata jsonb DEFAULT '[]'::jsonb,
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_target_url_not_null NOT NULL,
    protocol character varying(30) DEFAULT 'HTTP_WEBHOOK'::character varying CONSTRAINT msg_dispatch_jobs_protocol_not_null NOT NULL,
    payload text,
    payload_content_type character varying(100) DEFAULT 'application/json'::character varying,
    data_only boolean DEFAULT true CONSTRAINT msg_dispatch_jobs_data_only_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    mode character varying(30) DEFAULT 'NEXT_ON_ERROR'::character varying CONSTRAINT msg_dispatch_jobs_mode_not_null NOT NULL,
    dispatch_pool_id character varying(17),
    message_group character varying(200),
    sequence integer DEFAULT 99 CONSTRAINT msg_dispatch_jobs_sequence_not_null NOT NULL,
    timeout_seconds integer DEFAULT 30 CONSTRAINT msg_dispatch_jobs_timeout_seconds_not_null NOT NULL,
    schema_id character varying(17),
    status character varying(20) DEFAULT 'PENDING'::character varying CONSTRAINT msg_dispatch_jobs_status_not_null NOT NULL,
    max_retries integer DEFAULT 3 CONSTRAINT msg_dispatch_jobs_max_retries_not_null NOT NULL,
    retry_strategy character varying(50) DEFAULT 'exponential'::character varying,
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_attempt_count_not_null NOT NULL,
    last_attempt_at timestamp with time zone,
    completed_at timestamp with time zone,
    duration_millis bigint,
    last_error text,
    idempotency_key character varying(100),
    queued_at timestamp with time zone,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_updated_at_not_null NOT NULL,
    queue character varying(255),
    CONSTRAINT chk_msg_dispatch_jobs_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_2026_09; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_2026_09 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) DEFAULT 'EVENT'::character varying CONSTRAINT msg_dispatch_jobs_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    metadata jsonb DEFAULT '[]'::jsonb,
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_target_url_not_null NOT NULL,
    protocol character varying(30) DEFAULT 'HTTP_WEBHOOK'::character varying CONSTRAINT msg_dispatch_jobs_protocol_not_null NOT NULL,
    payload text,
    payload_content_type character varying(100) DEFAULT 'application/json'::character varying,
    data_only boolean DEFAULT true CONSTRAINT msg_dispatch_jobs_data_only_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    mode character varying(30) DEFAULT 'NEXT_ON_ERROR'::character varying CONSTRAINT msg_dispatch_jobs_mode_not_null NOT NULL,
    dispatch_pool_id character varying(17),
    message_group character varying(200),
    sequence integer DEFAULT 99 CONSTRAINT msg_dispatch_jobs_sequence_not_null NOT NULL,
    timeout_seconds integer DEFAULT 30 CONSTRAINT msg_dispatch_jobs_timeout_seconds_not_null NOT NULL,
    schema_id character varying(17),
    status character varying(20) DEFAULT 'PENDING'::character varying CONSTRAINT msg_dispatch_jobs_status_not_null NOT NULL,
    max_retries integer DEFAULT 3 CONSTRAINT msg_dispatch_jobs_max_retries_not_null NOT NULL,
    retry_strategy character varying(50) DEFAULT 'exponential'::character varying,
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_attempt_count_not_null NOT NULL,
    last_attempt_at timestamp with time zone,
    completed_at timestamp with time zone,
    duration_millis bigint,
    last_error text,
    idempotency_key character varying(100),
    queued_at timestamp with time zone,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_updated_at_not_null NOT NULL,
    queue character varying(255),
    CONSTRAINT chk_msg_dispatch_jobs_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_2026_10; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_2026_10 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) DEFAULT 'EVENT'::character varying CONSTRAINT msg_dispatch_jobs_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    metadata jsonb DEFAULT '[]'::jsonb,
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_target_url_not_null NOT NULL,
    protocol character varying(30) DEFAULT 'HTTP_WEBHOOK'::character varying CONSTRAINT msg_dispatch_jobs_protocol_not_null NOT NULL,
    payload text,
    payload_content_type character varying(100) DEFAULT 'application/json'::character varying,
    data_only boolean DEFAULT true CONSTRAINT msg_dispatch_jobs_data_only_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    mode character varying(30) DEFAULT 'NEXT_ON_ERROR'::character varying CONSTRAINT msg_dispatch_jobs_mode_not_null NOT NULL,
    dispatch_pool_id character varying(17),
    message_group character varying(200),
    sequence integer DEFAULT 99 CONSTRAINT msg_dispatch_jobs_sequence_not_null NOT NULL,
    timeout_seconds integer DEFAULT 30 CONSTRAINT msg_dispatch_jobs_timeout_seconds_not_null NOT NULL,
    schema_id character varying(17),
    status character varying(20) DEFAULT 'PENDING'::character varying CONSTRAINT msg_dispatch_jobs_status_not_null NOT NULL,
    max_retries integer DEFAULT 3 CONSTRAINT msg_dispatch_jobs_max_retries_not_null NOT NULL,
    retry_strategy character varying(50) DEFAULT 'exponential'::character varying,
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_attempt_count_not_null NOT NULL,
    last_attempt_at timestamp with time zone,
    completed_at timestamp with time zone,
    duration_millis bigint,
    last_error text,
    idempotency_key character varying(100),
    queued_at timestamp with time zone,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_updated_at_not_null NOT NULL,
    queue character varying(255),
    CONSTRAINT chk_msg_dispatch_jobs_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_2026_11; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_2026_11 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) DEFAULT 'EVENT'::character varying CONSTRAINT msg_dispatch_jobs_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    metadata jsonb DEFAULT '[]'::jsonb,
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_target_url_not_null NOT NULL,
    protocol character varying(30) DEFAULT 'HTTP_WEBHOOK'::character varying CONSTRAINT msg_dispatch_jobs_protocol_not_null NOT NULL,
    payload text,
    payload_content_type character varying(100) DEFAULT 'application/json'::character varying,
    data_only boolean DEFAULT true CONSTRAINT msg_dispatch_jobs_data_only_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    mode character varying(30) DEFAULT 'NEXT_ON_ERROR'::character varying CONSTRAINT msg_dispatch_jobs_mode_not_null NOT NULL,
    dispatch_pool_id character varying(17),
    message_group character varying(200),
    sequence integer DEFAULT 99 CONSTRAINT msg_dispatch_jobs_sequence_not_null NOT NULL,
    timeout_seconds integer DEFAULT 30 CONSTRAINT msg_dispatch_jobs_timeout_seconds_not_null NOT NULL,
    schema_id character varying(17),
    status character varying(20) DEFAULT 'PENDING'::character varying CONSTRAINT msg_dispatch_jobs_status_not_null NOT NULL,
    max_retries integer DEFAULT 3 CONSTRAINT msg_dispatch_jobs_max_retries_not_null NOT NULL,
    retry_strategy character varying(50) DEFAULT 'exponential'::character varying,
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_attempt_count_not_null NOT NULL,
    last_attempt_at timestamp with time zone,
    completed_at timestamp with time zone,
    duration_millis bigint,
    last_error text,
    idempotency_key character varying(100),
    queued_at timestamp with time zone,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_updated_at_not_null NOT NULL,
    queue character varying(255),
    CONSTRAINT chk_msg_dispatch_jobs_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_2026_12; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_2026_12 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) DEFAULT 'EVENT'::character varying CONSTRAINT msg_dispatch_jobs_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    metadata jsonb DEFAULT '[]'::jsonb,
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_target_url_not_null NOT NULL,
    protocol character varying(30) DEFAULT 'HTTP_WEBHOOK'::character varying CONSTRAINT msg_dispatch_jobs_protocol_not_null NOT NULL,
    payload text,
    payload_content_type character varying(100) DEFAULT 'application/json'::character varying,
    data_only boolean DEFAULT true CONSTRAINT msg_dispatch_jobs_data_only_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    mode character varying(30) DEFAULT 'NEXT_ON_ERROR'::character varying CONSTRAINT msg_dispatch_jobs_mode_not_null NOT NULL,
    dispatch_pool_id character varying(17),
    message_group character varying(200),
    sequence integer DEFAULT 99 CONSTRAINT msg_dispatch_jobs_sequence_not_null NOT NULL,
    timeout_seconds integer DEFAULT 30 CONSTRAINT msg_dispatch_jobs_timeout_seconds_not_null NOT NULL,
    schema_id character varying(17),
    status character varying(20) DEFAULT 'PENDING'::character varying CONSTRAINT msg_dispatch_jobs_status_not_null NOT NULL,
    max_retries integer DEFAULT 3 CONSTRAINT msg_dispatch_jobs_max_retries_not_null NOT NULL,
    retry_strategy character varying(50) DEFAULT 'exponential'::character varying,
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_attempt_count_not_null NOT NULL,
    last_attempt_at timestamp with time zone,
    completed_at timestamp with time zone,
    duration_millis bigint,
    last_error text,
    idempotency_key character varying(100),
    queued_at timestamp with time zone,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_updated_at_not_null NOT NULL,
    queue character varying(255),
    CONSTRAINT chk_msg_dispatch_jobs_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_read; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_read (
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_msg_dispatch_jobs_read_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_read_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_read_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
)
PARTITION BY RANGE (created_at);


--
-- Name: msg_dispatch_jobs_read_2026_08; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_read_2026_08 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_read_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) CONSTRAINT msg_dispatch_jobs_read_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_read_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_read_target_url_not_null NOT NULL,
    protocol character varying(30) CONSTRAINT msg_dispatch_jobs_read_protocol_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    dispatch_pool_id character varying(17),
    mode character varying(30) CONSTRAINT msg_dispatch_jobs_read_mode_not_null NOT NULL,
    message_group character varying(200),
    sequence integer DEFAULT 99,
    timeout_seconds integer DEFAULT 30,
    status character varying(20) CONSTRAINT msg_dispatch_jobs_read_status_not_null NOT NULL,
    max_retries integer CONSTRAINT msg_dispatch_jobs_read_max_retries_not_null NOT NULL,
    retry_strategy character varying(50),
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_read_attempt_count_not_null NOT NULL,
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
    updated_at timestamp with time zone CONSTRAINT msg_dispatch_jobs_read_updated_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_read_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_jobs_read_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_read_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_read_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_read_2026_09; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_read_2026_09 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_read_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) CONSTRAINT msg_dispatch_jobs_read_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_read_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_read_target_url_not_null NOT NULL,
    protocol character varying(30) CONSTRAINT msg_dispatch_jobs_read_protocol_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    dispatch_pool_id character varying(17),
    mode character varying(30) CONSTRAINT msg_dispatch_jobs_read_mode_not_null NOT NULL,
    message_group character varying(200),
    sequence integer DEFAULT 99,
    timeout_seconds integer DEFAULT 30,
    status character varying(20) CONSTRAINT msg_dispatch_jobs_read_status_not_null NOT NULL,
    max_retries integer CONSTRAINT msg_dispatch_jobs_read_max_retries_not_null NOT NULL,
    retry_strategy character varying(50),
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_read_attempt_count_not_null NOT NULL,
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
    updated_at timestamp with time zone CONSTRAINT msg_dispatch_jobs_read_updated_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_read_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_jobs_read_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_read_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_read_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_read_2026_10; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_read_2026_10 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_read_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) CONSTRAINT msg_dispatch_jobs_read_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_read_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_read_target_url_not_null NOT NULL,
    protocol character varying(30) CONSTRAINT msg_dispatch_jobs_read_protocol_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    dispatch_pool_id character varying(17),
    mode character varying(30) CONSTRAINT msg_dispatch_jobs_read_mode_not_null NOT NULL,
    message_group character varying(200),
    sequence integer DEFAULT 99,
    timeout_seconds integer DEFAULT 30,
    status character varying(20) CONSTRAINT msg_dispatch_jobs_read_status_not_null NOT NULL,
    max_retries integer CONSTRAINT msg_dispatch_jobs_read_max_retries_not_null NOT NULL,
    retry_strategy character varying(50),
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_read_attempt_count_not_null NOT NULL,
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
    updated_at timestamp with time zone CONSTRAINT msg_dispatch_jobs_read_updated_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_read_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_jobs_read_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_read_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_read_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_read_2026_11; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_read_2026_11 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_read_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) CONSTRAINT msg_dispatch_jobs_read_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_read_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_read_target_url_not_null NOT NULL,
    protocol character varying(30) CONSTRAINT msg_dispatch_jobs_read_protocol_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    dispatch_pool_id character varying(17),
    mode character varying(30) CONSTRAINT msg_dispatch_jobs_read_mode_not_null NOT NULL,
    message_group character varying(200),
    sequence integer DEFAULT 99,
    timeout_seconds integer DEFAULT 30,
    status character varying(20) CONSTRAINT msg_dispatch_jobs_read_status_not_null NOT NULL,
    max_retries integer CONSTRAINT msg_dispatch_jobs_read_max_retries_not_null NOT NULL,
    retry_strategy character varying(50),
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_read_attempt_count_not_null NOT NULL,
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
    updated_at timestamp with time zone CONSTRAINT msg_dispatch_jobs_read_updated_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_read_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_jobs_read_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_read_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_read_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_jobs_read_2026_12; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_jobs_read_2026_12 (
    id character varying(13) CONSTRAINT msg_dispatch_jobs_read_id_not_null NOT NULL,
    external_id character varying(100),
    source character varying(500),
    kind character varying(20) CONSTRAINT msg_dispatch_jobs_read_kind_not_null NOT NULL,
    code character varying(200) CONSTRAINT msg_dispatch_jobs_read_code_not_null NOT NULL,
    subject character varying(500),
    event_id character varying(13),
    correlation_id character varying(100),
    target_url character varying(500) CONSTRAINT msg_dispatch_jobs_read_target_url_not_null NOT NULL,
    protocol character varying(30) CONSTRAINT msg_dispatch_jobs_read_protocol_not_null NOT NULL,
    service_account_id character varying(17),
    client_id character varying(17),
    subscription_id character varying(17),
    dispatch_pool_id character varying(17),
    mode character varying(30) CONSTRAINT msg_dispatch_jobs_read_mode_not_null NOT NULL,
    message_group character varying(200),
    sequence integer DEFAULT 99,
    timeout_seconds integer DEFAULT 30,
    status character varying(20) CONSTRAINT msg_dispatch_jobs_read_status_not_null NOT NULL,
    max_retries integer CONSTRAINT msg_dispatch_jobs_read_max_retries_not_null NOT NULL,
    retry_strategy character varying(50),
    scheduled_for timestamp with time zone,
    expires_at timestamp with time zone,
    attempt_count integer DEFAULT 0 CONSTRAINT msg_dispatch_jobs_read_attempt_count_not_null NOT NULL,
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
    updated_at timestamp with time zone CONSTRAINT msg_dispatch_jobs_read_updated_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_dispatch_jobs_read_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_dispatch_jobs_read_kind CHECK (((kind)::text = ANY ((ARRAY['EVENT'::character varying, 'TASK'::character varying])::text[]))),
    CONSTRAINT chk_msg_dispatch_jobs_read_retry_strategy CHECK (((retry_strategy IS NULL) OR ((retry_strategy)::text = ANY ((ARRAY['immediate'::character varying, 'IMMEDIATE'::character varying, 'fixed'::character varying, 'FIXED_DELAY'::character varying, 'exponential'::character varying])::text[])))),
    CONSTRAINT chk_msg_dispatch_jobs_read_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'QUEUED'::character varying, 'PROCESSING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: msg_dispatch_pools; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_dispatch_pools (
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
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_msg_dispatch_pools_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'ARCHIVED'::character varying])::text[])))
);


--
-- Name: msg_event_projection_feed; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_event_projection_feed (
    id bigint NOT NULL,
    event_id character varying(13) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    processed smallint DEFAULT 0 NOT NULL,
    processed_at timestamp with time zone,
    error_message text
);


--
-- Name: msg_event_projection_feed_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.msg_event_projection_feed_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: msg_event_projection_feed_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.msg_event_projection_feed_id_seq OWNED BY public.msg_event_projection_feed.id;


--
-- Name: msg_event_type_spec_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_event_type_spec_versions (
    id character varying(17) NOT NULL,
    event_type_id character varying(17) NOT NULL,
    version character varying(20) NOT NULL,
    mime_type character varying(100) NOT NULL,
    schema_content jsonb,
    schema_type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'FINALISING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_msg_event_type_spec_versions_schema_type CHECK (((schema_type)::text = ANY ((ARRAY['JSON_SCHEMA'::character varying, 'XSD'::character varying, 'XML_SCHEMA'::character varying, 'PROTO'::character varying, 'PROTOBUF'::character varying])::text[]))),
    CONSTRAINT chk_msg_event_type_spec_versions_status CHECK (((status)::text = ANY ((ARRAY['FINALISING'::character varying, 'CURRENT'::character varying, 'DEPRECATED'::character varying])::text[])))
);


--
-- Name: msg_event_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_event_types (
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
    created_by character varying(17),
    CONSTRAINT chk_msg_event_types_source CHECK (((source)::text = ANY ((ARRAY['CODE'::character varying, 'API'::character varying, 'UI'::character varying])::text[]))),
    CONSTRAINT chk_msg_event_types_status CHECK (((status)::text = ANY ((ARRAY['CURRENT'::character varying, 'ARCHIVED'::character varying])::text[])))
);


--
-- Name: msg_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events (
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


--
-- Name: msg_events_2026_08; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_2026_08 (
    id character varying(13) CONSTRAINT msg_events_id_not_null NOT NULL,
    spec_version character varying(20) DEFAULT '1.0'::character varying,
    type character varying(200) CONSTRAINT msg_events_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_time_not_null NOT NULL,
    data jsonb,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    context_data jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_created_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    fanned_out_at timestamp with time zone
);


--
-- Name: msg_events_2026_09; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_2026_09 (
    id character varying(13) CONSTRAINT msg_events_id_not_null NOT NULL,
    spec_version character varying(20) DEFAULT '1.0'::character varying,
    type character varying(200) CONSTRAINT msg_events_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_time_not_null NOT NULL,
    data jsonb,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    context_data jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_created_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    fanned_out_at timestamp with time zone
);


--
-- Name: msg_events_2026_10; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_2026_10 (
    id character varying(13) CONSTRAINT msg_events_id_not_null NOT NULL,
    spec_version character varying(20) DEFAULT '1.0'::character varying,
    type character varying(200) CONSTRAINT msg_events_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_time_not_null NOT NULL,
    data jsonb,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    context_data jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_created_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    fanned_out_at timestamp with time zone
);


--
-- Name: msg_events_2026_11; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_2026_11 (
    id character varying(13) CONSTRAINT msg_events_id_not_null NOT NULL,
    spec_version character varying(20) DEFAULT '1.0'::character varying,
    type character varying(200) CONSTRAINT msg_events_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_time_not_null NOT NULL,
    data jsonb,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    context_data jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_created_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    fanned_out_at timestamp with time zone
);


--
-- Name: msg_events_2026_12; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_2026_12 (
    id character varying(13) CONSTRAINT msg_events_id_not_null NOT NULL,
    spec_version character varying(20) DEFAULT '1.0'::character varying,
    type character varying(200) CONSTRAINT msg_events_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_time_not_null NOT NULL,
    data jsonb,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    context_data jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_created_at_not_null NOT NULL,
    projected_at timestamp with time zone,
    fanned_out_at timestamp with time zone
);


--
-- Name: msg_events_read; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_read (
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


--
-- Name: msg_events_read_2026_08; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_read_2026_08 (
    id character varying(13) CONSTRAINT msg_events_read_id_not_null NOT NULL,
    spec_version character varying(20),
    type character varying(200) CONSTRAINT msg_events_read_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_read_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_read_time_not_null NOT NULL,
    data text,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    application character varying(100),
    subdomain character varying(100),
    aggregate character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_created_at_not_null NOT NULL,
    projected_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_projected_at_not_null NOT NULL
);


--
-- Name: msg_events_read_2026_09; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_read_2026_09 (
    id character varying(13) CONSTRAINT msg_events_read_id_not_null NOT NULL,
    spec_version character varying(20),
    type character varying(200) CONSTRAINT msg_events_read_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_read_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_read_time_not_null NOT NULL,
    data text,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    application character varying(100),
    subdomain character varying(100),
    aggregate character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_created_at_not_null NOT NULL,
    projected_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_projected_at_not_null NOT NULL
);


--
-- Name: msg_events_read_2026_10; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_read_2026_10 (
    id character varying(13) CONSTRAINT msg_events_read_id_not_null NOT NULL,
    spec_version character varying(20),
    type character varying(200) CONSTRAINT msg_events_read_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_read_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_read_time_not_null NOT NULL,
    data text,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    application character varying(100),
    subdomain character varying(100),
    aggregate character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_created_at_not_null NOT NULL,
    projected_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_projected_at_not_null NOT NULL
);


--
-- Name: msg_events_read_2026_11; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_read_2026_11 (
    id character varying(13) CONSTRAINT msg_events_read_id_not_null NOT NULL,
    spec_version character varying(20),
    type character varying(200) CONSTRAINT msg_events_read_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_read_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_read_time_not_null NOT NULL,
    data text,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    application character varying(100),
    subdomain character varying(100),
    aggregate character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_created_at_not_null NOT NULL,
    projected_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_projected_at_not_null NOT NULL
);


--
-- Name: msg_events_read_2026_12; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_events_read_2026_12 (
    id character varying(13) CONSTRAINT msg_events_read_id_not_null NOT NULL,
    spec_version character varying(20),
    type character varying(200) CONSTRAINT msg_events_read_type_not_null NOT NULL,
    source character varying(500) CONSTRAINT msg_events_read_source_not_null NOT NULL,
    subject character varying(500),
    "time" timestamp with time zone CONSTRAINT msg_events_read_time_not_null NOT NULL,
    data text,
    correlation_id character varying(100),
    causation_id character varying(100),
    deduplication_id character varying(200),
    message_group character varying(200),
    client_id character varying(17),
    application character varying(100),
    subdomain character varying(100),
    aggregate character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_created_at_not_null NOT NULL,
    projected_at timestamp with time zone DEFAULT now() CONSTRAINT msg_events_read_projected_at_not_null NOT NULL
);


--
-- Name: msg_processes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_processes (
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
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_msg_processes_source CHECK (((source)::text = ANY ((ARRAY['CODE'::character varying, 'API'::character varying, 'UI'::character varying])::text[]))),
    CONSTRAINT chk_msg_processes_status CHECK (((status)::text = ANY ((ARRAY['CURRENT'::character varying, 'ARCHIVED'::character varying])::text[])))
);


--
-- Name: msg_scheduled_job_instance_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instance_logs (
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


--
-- Name: msg_scheduled_job_instance_logs_2026_08; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instance_logs_2026_08 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_id_not_null NOT NULL,
    instance_id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_instance_id_not_null NOT NULL,
    scheduled_job_id character varying(17),
    client_id character varying(17),
    level character varying(10) DEFAULT 'INFO'::character varying CONSTRAINT msg_scheduled_job_instance_logs_level_not_null NOT NULL,
    message text CONSTRAINT msg_scheduled_job_instance_logs_message_not_null NOT NULL,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instance_logs_created_at_not_null NOT NULL
);


--
-- Name: msg_scheduled_job_instance_logs_2026_09; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instance_logs_2026_09 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_id_not_null NOT NULL,
    instance_id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_instance_id_not_null NOT NULL,
    scheduled_job_id character varying(17),
    client_id character varying(17),
    level character varying(10) DEFAULT 'INFO'::character varying CONSTRAINT msg_scheduled_job_instance_logs_level_not_null NOT NULL,
    message text CONSTRAINT msg_scheduled_job_instance_logs_message_not_null NOT NULL,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instance_logs_created_at_not_null NOT NULL
);


--
-- Name: msg_scheduled_job_instance_logs_2026_10; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instance_logs_2026_10 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_id_not_null NOT NULL,
    instance_id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_instance_id_not_null NOT NULL,
    scheduled_job_id character varying(17),
    client_id character varying(17),
    level character varying(10) DEFAULT 'INFO'::character varying CONSTRAINT msg_scheduled_job_instance_logs_level_not_null NOT NULL,
    message text CONSTRAINT msg_scheduled_job_instance_logs_message_not_null NOT NULL,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instance_logs_created_at_not_null NOT NULL
);


--
-- Name: msg_scheduled_job_instance_logs_2026_11; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instance_logs_2026_11 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_id_not_null NOT NULL,
    instance_id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_instance_id_not_null NOT NULL,
    scheduled_job_id character varying(17),
    client_id character varying(17),
    level character varying(10) DEFAULT 'INFO'::character varying CONSTRAINT msg_scheduled_job_instance_logs_level_not_null NOT NULL,
    message text CONSTRAINT msg_scheduled_job_instance_logs_message_not_null NOT NULL,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instance_logs_created_at_not_null NOT NULL
);


--
-- Name: msg_scheduled_job_instance_logs_2026_12; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instance_logs_2026_12 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_id_not_null NOT NULL,
    instance_id character varying(17) CONSTRAINT msg_scheduled_job_instance_logs_instance_id_not_null NOT NULL,
    scheduled_job_id character varying(17),
    client_id character varying(17),
    level character varying(10) DEFAULT 'INFO'::character varying CONSTRAINT msg_scheduled_job_instance_logs_level_not_null NOT NULL,
    message text CONSTRAINT msg_scheduled_job_instance_logs_message_not_null NOT NULL,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instance_logs_created_at_not_null NOT NULL
);


--
-- Name: msg_scheduled_job_instances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instances (
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_msg_scheduled_job_instances_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'DELIVERY_FAILED'::character varying])::text[]))),
    CONSTRAINT chk_msg_scheduled_job_instances_trigger_kind CHECK (((trigger_kind)::text = ANY ((ARRAY['CRON'::character varying, 'MANUAL'::character varying, 'BACKFILL'::character varying])::text[])))
)
PARTITION BY RANGE (created_at);


--
-- Name: msg_scheduled_job_instances_2026_08; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instances_2026_08 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instances_id_not_null NOT NULL,
    scheduled_job_id character varying(17) CONSTRAINT msg_scheduled_job_instances_scheduled_job_id_not_null NOT NULL,
    client_id character varying(17),
    job_code character varying(200) CONSTRAINT msg_scheduled_job_instances_job_code_not_null NOT NULL,
    trigger_kind character varying(20) DEFAULT 'CRON'::character varying CONSTRAINT msg_scheduled_job_instances_trigger_kind_not_null NOT NULL,
    scheduled_for timestamp with time zone,
    fired_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_fired_at_not_null NOT NULL,
    delivered_at timestamp with time zone,
    completed_at timestamp with time zone,
    status character varying(20) DEFAULT 'QUEUED'::character varying CONSTRAINT msg_scheduled_job_instances_status_not_null NOT NULL,
    delivery_attempts integer DEFAULT 0 CONSTRAINT msg_scheduled_job_instances_delivery_attempts_not_null NOT NULL,
    delivery_error text,
    completion_status character varying(20),
    completion_result jsonb,
    correlation_id character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_scheduled_job_instances_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'DELIVERY_FAILED'::character varying])::text[]))),
    CONSTRAINT chk_msg_scheduled_job_instances_trigger_kind CHECK (((trigger_kind)::text = ANY ((ARRAY['CRON'::character varying, 'MANUAL'::character varying, 'BACKFILL'::character varying])::text[])))
);


--
-- Name: msg_scheduled_job_instances_2026_09; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instances_2026_09 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instances_id_not_null NOT NULL,
    scheduled_job_id character varying(17) CONSTRAINT msg_scheduled_job_instances_scheduled_job_id_not_null NOT NULL,
    client_id character varying(17),
    job_code character varying(200) CONSTRAINT msg_scheduled_job_instances_job_code_not_null NOT NULL,
    trigger_kind character varying(20) DEFAULT 'CRON'::character varying CONSTRAINT msg_scheduled_job_instances_trigger_kind_not_null NOT NULL,
    scheduled_for timestamp with time zone,
    fired_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_fired_at_not_null NOT NULL,
    delivered_at timestamp with time zone,
    completed_at timestamp with time zone,
    status character varying(20) DEFAULT 'QUEUED'::character varying CONSTRAINT msg_scheduled_job_instances_status_not_null NOT NULL,
    delivery_attempts integer DEFAULT 0 CONSTRAINT msg_scheduled_job_instances_delivery_attempts_not_null NOT NULL,
    delivery_error text,
    completion_status character varying(20),
    completion_result jsonb,
    correlation_id character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_scheduled_job_instances_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'DELIVERY_FAILED'::character varying])::text[]))),
    CONSTRAINT chk_msg_scheduled_job_instances_trigger_kind CHECK (((trigger_kind)::text = ANY ((ARRAY['CRON'::character varying, 'MANUAL'::character varying, 'BACKFILL'::character varying])::text[])))
);


--
-- Name: msg_scheduled_job_instances_2026_10; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instances_2026_10 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instances_id_not_null NOT NULL,
    scheduled_job_id character varying(17) CONSTRAINT msg_scheduled_job_instances_scheduled_job_id_not_null NOT NULL,
    client_id character varying(17),
    job_code character varying(200) CONSTRAINT msg_scheduled_job_instances_job_code_not_null NOT NULL,
    trigger_kind character varying(20) DEFAULT 'CRON'::character varying CONSTRAINT msg_scheduled_job_instances_trigger_kind_not_null NOT NULL,
    scheduled_for timestamp with time zone,
    fired_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_fired_at_not_null NOT NULL,
    delivered_at timestamp with time zone,
    completed_at timestamp with time zone,
    status character varying(20) DEFAULT 'QUEUED'::character varying CONSTRAINT msg_scheduled_job_instances_status_not_null NOT NULL,
    delivery_attempts integer DEFAULT 0 CONSTRAINT msg_scheduled_job_instances_delivery_attempts_not_null NOT NULL,
    delivery_error text,
    completion_status character varying(20),
    completion_result jsonb,
    correlation_id character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_scheduled_job_instances_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'DELIVERY_FAILED'::character varying])::text[]))),
    CONSTRAINT chk_msg_scheduled_job_instances_trigger_kind CHECK (((trigger_kind)::text = ANY ((ARRAY['CRON'::character varying, 'MANUAL'::character varying, 'BACKFILL'::character varying])::text[])))
);


--
-- Name: msg_scheduled_job_instances_2026_11; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instances_2026_11 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instances_id_not_null NOT NULL,
    scheduled_job_id character varying(17) CONSTRAINT msg_scheduled_job_instances_scheduled_job_id_not_null NOT NULL,
    client_id character varying(17),
    job_code character varying(200) CONSTRAINT msg_scheduled_job_instances_job_code_not_null NOT NULL,
    trigger_kind character varying(20) DEFAULT 'CRON'::character varying CONSTRAINT msg_scheduled_job_instances_trigger_kind_not_null NOT NULL,
    scheduled_for timestamp with time zone,
    fired_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_fired_at_not_null NOT NULL,
    delivered_at timestamp with time zone,
    completed_at timestamp with time zone,
    status character varying(20) DEFAULT 'QUEUED'::character varying CONSTRAINT msg_scheduled_job_instances_status_not_null NOT NULL,
    delivery_attempts integer DEFAULT 0 CONSTRAINT msg_scheduled_job_instances_delivery_attempts_not_null NOT NULL,
    delivery_error text,
    completion_status character varying(20),
    completion_result jsonb,
    correlation_id character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_scheduled_job_instances_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'DELIVERY_FAILED'::character varying])::text[]))),
    CONSTRAINT chk_msg_scheduled_job_instances_trigger_kind CHECK (((trigger_kind)::text = ANY ((ARRAY['CRON'::character varying, 'MANUAL'::character varying, 'BACKFILL'::character varying])::text[])))
);


--
-- Name: msg_scheduled_job_instances_2026_12; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_job_instances_2026_12 (
    id character varying(17) CONSTRAINT msg_scheduled_job_instances_id_not_null NOT NULL,
    scheduled_job_id character varying(17) CONSTRAINT msg_scheduled_job_instances_scheduled_job_id_not_null NOT NULL,
    client_id character varying(17),
    job_code character varying(200) CONSTRAINT msg_scheduled_job_instances_job_code_not_null NOT NULL,
    trigger_kind character varying(20) DEFAULT 'CRON'::character varying CONSTRAINT msg_scheduled_job_instances_trigger_kind_not_null NOT NULL,
    scheduled_for timestamp with time zone,
    fired_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_fired_at_not_null NOT NULL,
    delivered_at timestamp with time zone,
    completed_at timestamp with time zone,
    status character varying(20) DEFAULT 'QUEUED'::character varying CONSTRAINT msg_scheduled_job_instances_status_not_null NOT NULL,
    delivery_attempts integer DEFAULT 0 CONSTRAINT msg_scheduled_job_instances_delivery_attempts_not_null NOT NULL,
    delivery_error text,
    completion_status character varying(20),
    completion_result jsonb,
    correlation_id character varying(100),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT msg_scheduled_job_instances_created_at_not_null NOT NULL,
    CONSTRAINT chk_msg_scheduled_job_instances_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'DELIVERY_FAILED'::character varying])::text[]))),
    CONSTRAINT chk_msg_scheduled_job_instances_trigger_kind CHECK (((trigger_kind)::text = ANY ((ARRAY['CRON'::character varying, 'MANUAL'::character varying, 'BACKFILL'::character varying])::text[])))
);


--
-- Name: msg_scheduled_jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_scheduled_jobs (
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
    application_id character varying(17),
    CONSTRAINT chk_msg_scheduled_jobs_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAUSED'::character varying, 'ARCHIVED'::character varying])::text[])))
);


--
-- Name: msg_subscription_custom_configs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_subscription_custom_configs (
    id integer NOT NULL,
    subscription_id character varying(17) NOT NULL,
    config_key character varying(100) NOT NULL,
    config_value character varying(1000) NOT NULL
);


--
-- Name: msg_subscription_custom_configs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.msg_subscription_custom_configs_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: msg_subscription_custom_configs_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.msg_subscription_custom_configs_id_seq OWNED BY public.msg_subscription_custom_configs.id;


--
-- Name: msg_subscription_event_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_subscription_event_types (
    id integer NOT NULL,
    subscription_id character varying(17) NOT NULL,
    event_type_id character varying(17),
    event_type_code character varying(255) NOT NULL,
    spec_version character varying(50)
);


--
-- Name: msg_subscription_event_types_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.msg_subscription_event_types_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: msg_subscription_event_types_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.msg_subscription_event_types_id_seq OWNED BY public.msg_subscription_event_types.id;


--
-- Name: msg_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.msg_subscriptions (
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
    mode character varying(20) DEFAULT 'NEXT_ON_ERROR'::character varying NOT NULL,
    timeout_seconds integer DEFAULT 30 NOT NULL,
    max_retries integer DEFAULT 3 NOT NULL,
    service_account_id character varying(17),
    data_only boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    connection_id character varying(17),
    created_by character varying(17),
    CONSTRAINT chk_msg_subscriptions_source CHECK (((source)::text = ANY ((ARRAY['CODE'::character varying, 'API'::character varying, 'UI'::character varying])::text[]))),
    CONSTRAINT chk_msg_subscriptions_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAUSED'::character varying])::text[])))
);


--
-- Name: oauth_client_allowed_origins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_client_allowed_origins (
    oauth_client_id character varying(17) NOT NULL,
    allowed_origin character varying(200) NOT NULL
);


--
-- Name: oauth_client_application_ids; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_client_application_ids (
    oauth_client_id character varying(17) NOT NULL,
    application_id character varying(17) NOT NULL
);


--
-- Name: oauth_client_grant_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_client_grant_types (
    oauth_client_id character varying(17) NOT NULL,
    grant_type character varying(50) NOT NULL
);


--
-- Name: oauth_client_post_logout_redirect_uris; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_client_post_logout_redirect_uris (
    oauth_client_id character varying(17) NOT NULL,
    post_logout_redirect_uri text CONSTRAINT oauth_client_post_logout_redi_post_logout_redirect_uri_not_null NOT NULL
);


--
-- Name: oauth_client_redirect_uris; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_client_redirect_uris (
    oauth_client_id character varying(17) NOT NULL,
    redirect_uri character varying(500) NOT NULL
);


--
-- Name: oauth_clients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_clients (
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
    api_access boolean DEFAULT false NOT NULL,
    previous_secret_ref text,
    previous_secret_expires_at timestamp with time zone,
    previous_secret_last_used_at timestamp with time zone,
    portal_app_id character varying(17),
    CONSTRAINT chk_oauth_clients_client_type CHECK (((client_type)::text = ANY ((ARRAY['PUBLIC'::character varying, 'CONFIDENTIAL'::character varying])::text[])))
);


--
-- Name: oauth_identity_provider_allowed_domains; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_identity_provider_allowed_domains (
    id integer NOT NULL,
    identity_provider_id character varying(17) CONSTRAINT oauth_identity_provider_allowed_d_identity_provider_id_not_null NOT NULL,
    email_domain character varying(255) NOT NULL
);


--
-- Name: oauth_identity_provider_allowed_domains_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.oauth_identity_provider_allowed_domains_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: oauth_identity_provider_allowed_domains_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.oauth_identity_provider_allowed_domains_id_seq OWNED BY public.oauth_identity_provider_allowed_domains.id;


--
-- Name: oauth_identity_provider_allowed_roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_identity_provider_allowed_roles (
    id integer NOT NULL,
    identity_provider_id character varying(17) CONSTRAINT oauth_identity_provider_allowed_r_identity_provider_id_not_null NOT NULL,
    role_id character varying(17) NOT NULL
);


--
-- Name: oauth_identity_provider_allowed_roles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.oauth_identity_provider_allowed_roles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: oauth_identity_provider_allowed_roles_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.oauth_identity_provider_allowed_roles_id_seq OWNED BY public.oauth_identity_provider_allowed_roles.id;


--
-- Name: oauth_identity_providers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_identity_providers (
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
    sync_roles_from_idp boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_oauth_identity_providers_type CHECK (((type)::text = ANY ((ARRAY['INTERNAL'::character varying, 'OIDC'::character varying])::text[])))
);


--
-- Name: oauth_idp_role_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_idp_role_mappings (
    id character varying(17) NOT NULL,
    idp_role_name character varying(200) NOT NULL,
    internal_role_name character varying(200) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    idp_type character varying(50)
);


--
-- Name: oauth_oidc_login_states; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_oidc_login_states (
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


--
-- Name: oauth_oidc_payloads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth_oidc_payloads (
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


--
-- Name: portal_apps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portal_apps (
    id character varying(17) NOT NULL,
    client_id character varying(17) NOT NULL,
    code character varying(100) NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(1000),
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: portal_identities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portal_identities (
    id character varying(17) NOT NULL,
    client_id character varying(17) NOT NULL,
    email character varying(255) NOT NULL,
    name character varying(255),
    password_hash character varying(255),
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    source character varying(20) NOT NULL,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    invited_at timestamp with time zone,
    invite_expires_at timestamp with time zone
);


--
-- Name: portal_identity_apps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portal_identity_apps (
    identity_id character varying(17) NOT NULL,
    portal_app_id character varying(17) NOT NULL,
    source character varying(20) NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: portal_login_flows; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portal_login_flows (
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


--
-- Name: tnt_anchor_domains; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_anchor_domains (
    id character varying(17) NOT NULL,
    domain character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: tnt_client_auth_configs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_client_auth_configs (
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
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tnt_client_auth_configs_auth_provider CHECK (((auth_provider)::text = ANY ((ARRAY['INTERNAL'::character varying, 'OIDC'::character varying])::text[]))),
    CONSTRAINT chk_tnt_client_auth_configs_config_type CHECK (((config_type)::text = ANY ((ARRAY['ANCHOR'::character varying, 'PARTNER'::character varying, 'CLIENT'::character varying])::text[])))
);


--
-- Name: tnt_clients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_clients (
    id character varying(17) NOT NULL,
    name character varying(255) NOT NULL,
    identifier character varying(100) NOT NULL,
    status character varying(50) DEFAULT 'ACTIVE'::character varying NOT NULL,
    status_reason character varying(255),
    status_changed_at timestamp with time zone,
    notes jsonb DEFAULT '[]'::jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_tnt_clients_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'SUSPENDED'::character varying])::text[])))
);


--
-- Name: tnt_cors_allowed_origins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_cors_allowed_origins (
    id character varying(17) NOT NULL,
    origin character varying(500) NOT NULL,
    description text,
    created_by character varying(17),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: tnt_email_domain_mapping_2fa_methods; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_email_domain_mapping_2fa_methods (
    id integer NOT NULL,
    email_domain_mapping_id character varying(17) CONSTRAINT tnt_email_domain_mapping_2fa_m_email_domain_mapping_id_not_null NOT NULL,
    method character varying(20) NOT NULL
);


--
-- Name: tnt_email_domain_mapping_2fa_methods_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tnt_email_domain_mapping_2fa_methods_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tnt_email_domain_mapping_2fa_methods_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tnt_email_domain_mapping_2fa_methods_id_seq OWNED BY public.tnt_email_domain_mapping_2fa_methods.id;


--
-- Name: tnt_email_domain_mapping_additional_clients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_email_domain_mapping_additional_clients (
    id integer NOT NULL,
    email_domain_mapping_id character varying(17) CONSTRAINT tnt_email_domain_mapping_addit_email_domain_mapping_id_not_null NOT NULL,
    client_id character varying(17) NOT NULL
);


--
-- Name: tnt_email_domain_mapping_additional_clients_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tnt_email_domain_mapping_additional_clients_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tnt_email_domain_mapping_additional_clients_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tnt_email_domain_mapping_additional_clients_id_seq OWNED BY public.tnt_email_domain_mapping_additional_clients.id;


--
-- Name: tnt_email_domain_mapping_allowed_roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_email_domain_mapping_allowed_roles (
    id integer NOT NULL,
    email_domain_mapping_id character varying(17) CONSTRAINT tnt_email_domain_mapping_allow_email_domain_mapping_id_not_null NOT NULL,
    role_id character varying(17) NOT NULL
);


--
-- Name: tnt_email_domain_mapping_allowed_roles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tnt_email_domain_mapping_allowed_roles_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tnt_email_domain_mapping_allowed_roles_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tnt_email_domain_mapping_allowed_roles_id_seq OWNED BY public.tnt_email_domain_mapping_allowed_roles.id;


--
-- Name: tnt_email_domain_mapping_granted_clients; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_email_domain_mapping_granted_clients (
    id integer NOT NULL,
    email_domain_mapping_id character varying(17) CONSTRAINT tnt_email_domain_mapping_grant_email_domain_mapping_id_not_null NOT NULL,
    client_id character varying(17) NOT NULL
);


--
-- Name: tnt_email_domain_mapping_granted_clients_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tnt_email_domain_mapping_granted_clients_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tnt_email_domain_mapping_granted_clients_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tnt_email_domain_mapping_granted_clients_id_seq OWNED BY public.tnt_email_domain_mapping_granted_clients.id;


--
-- Name: tnt_email_domain_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tnt_email_domain_mappings (
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
    remember_device_days integer DEFAULT 30 NOT NULL,
    CONSTRAINT chk_tnt_email_domain_mappings_scope_type CHECK (((scope_type)::text = ANY ((ARRAY['ANCHOR'::character varying, 'PARTNER'::character varying, 'CLIENT'::character varying])::text[])))
);


--
-- Name: webauthn_credentials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.webauthn_credentials (
    id character varying(17) NOT NULL,
    principal_id character varying(17) NOT NULL,
    credential_id bytea NOT NULL,
    passkey_data jsonb NOT NULL,
    name character varying(120),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone
);


--
-- Name: iam_login_attempts_2026_q3; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_login_attempts ATTACH PARTITION public.iam_login_attempts_2026_q3 FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: iam_login_attempts_2026_q4; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_login_attempts ATTACH PARTITION public.iam_login_attempts_2026_q4 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: iam_login_attempts_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_login_attempts ATTACH PARTITION public.iam_login_attempts_default DEFAULT;


--
-- Name: msg_dispatch_job_attempts_2026_08; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts ATTACH PARTITION public.msg_dispatch_job_attempts_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');


--
-- Name: msg_dispatch_job_attempts_2026_09; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts ATTACH PARTITION public.msg_dispatch_job_attempts_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: msg_dispatch_job_attempts_2026_10; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts ATTACH PARTITION public.msg_dispatch_job_attempts_2026_10 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');


--
-- Name: msg_dispatch_job_attempts_2026_11; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts ATTACH PARTITION public.msg_dispatch_job_attempts_2026_11 FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');


--
-- Name: msg_dispatch_job_attempts_2026_12; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts ATTACH PARTITION public.msg_dispatch_job_attempts_2026_12 FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_2026_08; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs ATTACH PARTITION public.msg_dispatch_jobs_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_2026_09; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs ATTACH PARTITION public.msg_dispatch_jobs_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_2026_10; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs ATTACH PARTITION public.msg_dispatch_jobs_2026_10 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_2026_11; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs ATTACH PARTITION public.msg_dispatch_jobs_2026_11 FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_2026_12; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs ATTACH PARTITION public.msg_dispatch_jobs_2026_12 FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_read_2026_08; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_read_2026_09; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_read_2026_10; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_read_2026_11; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11 FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');


--
-- Name: msg_dispatch_jobs_read_2026_12; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12 FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: msg_events_2026_08; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events ATTACH PARTITION public.msg_events_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');


--
-- Name: msg_events_2026_09; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events ATTACH PARTITION public.msg_events_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: msg_events_2026_10; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events ATTACH PARTITION public.msg_events_2026_10 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');


--
-- Name: msg_events_2026_11; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events ATTACH PARTITION public.msg_events_2026_11 FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');


--
-- Name: msg_events_2026_12; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events ATTACH PARTITION public.msg_events_2026_12 FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: msg_events_read_2026_08; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read ATTACH PARTITION public.msg_events_read_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');


--
-- Name: msg_events_read_2026_09; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read ATTACH PARTITION public.msg_events_read_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: msg_events_read_2026_10; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read ATTACH PARTITION public.msg_events_read_2026_10 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');


--
-- Name: msg_events_read_2026_11; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read ATTACH PARTITION public.msg_events_read_2026_11 FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');


--
-- Name: msg_events_read_2026_12; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read ATTACH PARTITION public.msg_events_read_2026_12 FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instance_logs_2026_08; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instance_logs_2026_09; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instance_logs_2026_10; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_10 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instance_logs_2026_11; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_11 FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instance_logs_2026_12; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_12 FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instances_2026_08; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances ATTACH PARTITION public.msg_scheduled_job_instances_2026_08 FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instances_2026_09; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances ATTACH PARTITION public.msg_scheduled_job_instances_2026_09 FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instances_2026_10; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances ATTACH PARTITION public.msg_scheduled_job_instances_2026_10 FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instances_2026_11; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances ATTACH PARTITION public.msg_scheduled_job_instances_2026_11 FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');


--
-- Name: msg_scheduled_job_instances_2026_12; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances ATTACH PARTITION public.msg_scheduled_job_instances_2026_12 FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');


--
-- Name: iam_rate_limit_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_rate_limit_events ALTER COLUMN id SET DEFAULT nextval('public.iam_rate_limit_events_id_seq'::regclass);


--
-- Name: msg_dispatch_job_projection_feed id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_projection_feed ALTER COLUMN id SET DEFAULT nextval('public.msg_dispatch_job_projection_feed_id_seq'::regclass);


--
-- Name: msg_event_projection_feed id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_event_projection_feed ALTER COLUMN id SET DEFAULT nextval('public.msg_event_projection_feed_id_seq'::regclass);


--
-- Name: msg_subscription_custom_configs id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_subscription_custom_configs ALTER COLUMN id SET DEFAULT nextval('public.msg_subscription_custom_configs_id_seq'::regclass);


--
-- Name: msg_subscription_event_types id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_subscription_event_types ALTER COLUMN id SET DEFAULT nextval('public.msg_subscription_event_types_id_seq'::regclass);


--
-- Name: oauth_identity_provider_allowed_domains id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_identity_provider_allowed_domains ALTER COLUMN id SET DEFAULT nextval('public.oauth_identity_provider_allowed_domains_id_seq'::regclass);


--
-- Name: oauth_identity_provider_allowed_roles id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_identity_provider_allowed_roles ALTER COLUMN id SET DEFAULT nextval('public.oauth_identity_provider_allowed_roles_id_seq'::regclass);


--
-- Name: tnt_email_domain_mapping_2fa_methods id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mapping_2fa_methods ALTER COLUMN id SET DEFAULT nextval('public.tnt_email_domain_mapping_2fa_methods_id_seq'::regclass);


--
-- Name: tnt_email_domain_mapping_additional_clients id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mapping_additional_clients ALTER COLUMN id SET DEFAULT nextval('public.tnt_email_domain_mapping_additional_clients_id_seq'::regclass);


--
-- Name: tnt_email_domain_mapping_allowed_roles id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mapping_allowed_roles ALTER COLUMN id SET DEFAULT nextval('public.tnt_email_domain_mapping_allowed_roles_id_seq'::regclass);


--
-- Name: tnt_email_domain_mapping_granted_clients id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mapping_granted_clients ALTER COLUMN id SET DEFAULT nextval('public.tnt_email_domain_mapping_granted_clients_id_seq'::regclass);


--
-- Name: app_application_openapi_specs app_application_openapi_specs_application_id_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_application_openapi_specs
    ADD CONSTRAINT app_application_openapi_specs_application_id_version_key UNIQUE (application_id, version);


--
-- Name: app_application_openapi_specs app_application_openapi_specs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_application_openapi_specs
    ADD CONSTRAINT app_application_openapi_specs_pkey PRIMARY KEY (id);


--
-- Name: app_applications app_applications_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_applications
    ADD CONSTRAINT app_applications_code_key UNIQUE (code);


--
-- Name: app_applications app_applications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_applications
    ADD CONSTRAINT app_applications_pkey PRIMARY KEY (id);


--
-- Name: app_client_configs app_client_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_client_configs
    ADD CONSTRAINT app_client_configs_pkey PRIMARY KEY (id);


--
-- Name: app_docs app_docs_application_id_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_docs
    ADD CONSTRAINT app_docs_application_id_slug_key UNIQUE (application_id, slug);


--
-- Name: app_docs app_docs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_docs
    ADD CONSTRAINT app_docs_pkey PRIMARY KEY (id);


--
-- Name: app_platform_config_access app_platform_config_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_platform_config_access
    ADD CONSTRAINT app_platform_config_access_pkey PRIMARY KEY (id);


--
-- Name: app_platform_configs app_platform_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_platform_configs
    ADD CONSTRAINT app_platform_configs_pkey PRIMARY KEY (id);


--
-- Name: aud_logs aud_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aud_logs
    ADD CONSTRAINT aud_logs_pkey PRIMARY KEY (id);


--
-- Name: goose_db_version goose_db_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.goose_db_version
    ADD CONSTRAINT goose_db_version_pkey PRIMARY KEY (id);


--
-- Name: iam_authorization_codes iam_authorization_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_authorization_codes
    ADD CONSTRAINT iam_authorization_codes_pkey PRIMARY KEY (code);


--
-- Name: iam_client_access_grants iam_client_access_grants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_client_access_grants
    ADD CONSTRAINT iam_client_access_grants_pkey PRIMARY KEY (id);


--
-- Name: iam_login_attempts iam_login_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_login_attempts
    ADD CONSTRAINT iam_login_attempts_pkey PRIMARY KEY (id, attempted_at);


--
-- Name: iam_login_attempts_2026_q3 iam_login_attempts_2026_q3_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_login_attempts_2026_q3
    ADD CONSTRAINT iam_login_attempts_2026_q3_pkey PRIMARY KEY (id, attempted_at);


--
-- Name: iam_login_attempts_2026_q4 iam_login_attempts_2026_q4_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_login_attempts_2026_q4
    ADD CONSTRAINT iam_login_attempts_2026_q4_pkey PRIMARY KEY (id, attempted_at);


--
-- Name: iam_login_attempts_default iam_login_attempts_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_login_attempts_default
    ADD CONSTRAINT iam_login_attempts_default_pkey PRIMARY KEY (id, attempted_at);


--
-- Name: iam_mfa_email_pins iam_mfa_email_pins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_mfa_email_pins
    ADD CONSTRAINT iam_mfa_email_pins_pkey PRIMARY KEY (id);


--
-- Name: iam_mfa_trusted_devices iam_mfa_trusted_devices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_mfa_trusted_devices
    ADD CONSTRAINT iam_mfa_trusted_devices_pkey PRIMARY KEY (id);


--
-- Name: iam_mfa_trusted_devices iam_mfa_trusted_devices_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_mfa_trusted_devices
    ADD CONSTRAINT iam_mfa_trusted_devices_token_hash_key UNIQUE (token_hash);


--
-- Name: iam_oidc_login_states iam_oidc_login_states_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_oidc_login_states
    ADD CONSTRAINT iam_oidc_login_states_pkey PRIMARY KEY (state);


--
-- Name: iam_password_reset_tokens iam_password_reset_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_password_reset_tokens
    ADD CONSTRAINT iam_password_reset_tokens_pkey PRIMARY KEY (id);


--
-- Name: iam_password_reset_tokens iam_password_reset_tokens_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_password_reset_tokens
    ADD CONSTRAINT iam_password_reset_tokens_token_hash_key UNIQUE (token_hash);


--
-- Name: iam_permissions iam_permissions_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_permissions
    ADD CONSTRAINT iam_permissions_code_key UNIQUE (code);


--
-- Name: iam_permissions iam_permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_permissions
    ADD CONSTRAINT iam_permissions_pkey PRIMARY KEY (id);


--
-- Name: iam_principal_application_access iam_principal_application_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_principal_application_access
    ADD CONSTRAINT iam_principal_application_access_pkey PRIMARY KEY (principal_id, application_id);


--
-- Name: iam_principal_roles iam_principal_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_principal_roles
    ADD CONSTRAINT iam_principal_roles_pkey PRIMARY KEY (principal_id, role_name);


--
-- Name: iam_principals iam_principals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_principals
    ADD CONSTRAINT iam_principals_pkey PRIMARY KEY (id);


--
-- Name: iam_rate_limit_events iam_rate_limit_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_rate_limit_events
    ADD CONSTRAINT iam_rate_limit_events_pkey PRIMARY KEY (id);


--
-- Name: iam_refresh_tokens iam_refresh_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_refresh_tokens
    ADD CONSTRAINT iam_refresh_tokens_pkey PRIMARY KEY (id);


--
-- Name: iam_reset_approval_requests iam_reset_approval_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_reset_approval_requests
    ADD CONSTRAINT iam_reset_approval_requests_pkey PRIMARY KEY (id);


--
-- Name: iam_role_permissions iam_role_permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_role_permissions
    ADD CONSTRAINT iam_role_permissions_pkey PRIMARY KEY (role_id, permission);


--
-- Name: iam_roles iam_roles_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_roles
    ADD CONSTRAINT iam_roles_name_key UNIQUE (name);


--
-- Name: iam_roles iam_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_roles
    ADD CONSTRAINT iam_roles_pkey PRIMARY KEY (id);


--
-- Name: iam_service_accounts iam_service_accounts_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_service_accounts
    ADD CONSTRAINT iam_service_accounts_code_key UNIQUE (code);


--
-- Name: iam_service_accounts iam_service_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_service_accounts
    ADD CONSTRAINT iam_service_accounts_pkey PRIMARY KEY (id);


--
-- Name: iam_user_mfa_methods iam_user_mfa_methods_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_user_mfa_methods
    ADD CONSTRAINT iam_user_mfa_methods_pkey PRIMARY KEY (id);


--
-- Name: iam_user_mfa_recovery_codes iam_user_mfa_recovery_codes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_user_mfa_recovery_codes
    ADD CONSTRAINT iam_user_mfa_recovery_codes_pkey PRIMARY KEY (id);


--
-- Name: msg_connections msg_connections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_connections
    ADD CONSTRAINT msg_connections_pkey PRIMARY KEY (id);


--
-- Name: msg_dispatch_job_attempts msg_dispatch_job_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts
    ADD CONSTRAINT msg_dispatch_job_attempts_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_job_attempts_2026_08 msg_dispatch_job_attempts_2026_08_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts_2026_08
    ADD CONSTRAINT msg_dispatch_job_attempts_2026_08_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_job_attempts_2026_09 msg_dispatch_job_attempts_2026_09_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts_2026_09
    ADD CONSTRAINT msg_dispatch_job_attempts_2026_09_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_job_attempts_2026_10 msg_dispatch_job_attempts_2026_10_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts_2026_10
    ADD CONSTRAINT msg_dispatch_job_attempts_2026_10_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_job_attempts_2026_11 msg_dispatch_job_attempts_2026_11_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts_2026_11
    ADD CONSTRAINT msg_dispatch_job_attempts_2026_11_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_job_attempts_2026_12 msg_dispatch_job_attempts_2026_12_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_attempts_2026_12
    ADD CONSTRAINT msg_dispatch_job_attempts_2026_12_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_job_projection_feed msg_dispatch_job_projection_feed_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_job_projection_feed
    ADD CONSTRAINT msg_dispatch_job_projection_feed_pkey PRIMARY KEY (id);


--
-- Name: msg_dispatch_jobs msg_dispatch_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs
    ADD CONSTRAINT msg_dispatch_jobs_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_2026_08 msg_dispatch_jobs_2026_08_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_2026_08
    ADD CONSTRAINT msg_dispatch_jobs_2026_08_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_2026_09 msg_dispatch_jobs_2026_09_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_2026_09
    ADD CONSTRAINT msg_dispatch_jobs_2026_09_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_2026_10 msg_dispatch_jobs_2026_10_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_2026_10
    ADD CONSTRAINT msg_dispatch_jobs_2026_10_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_2026_11 msg_dispatch_jobs_2026_11_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_2026_11
    ADD CONSTRAINT msg_dispatch_jobs_2026_11_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_2026_12 msg_dispatch_jobs_2026_12_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_2026_12
    ADD CONSTRAINT msg_dispatch_jobs_2026_12_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_read msg_dispatch_jobs_read_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read
    ADD CONSTRAINT msg_dispatch_jobs_read_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_read_2026_08 msg_dispatch_jobs_read_2026_08_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read_2026_08
    ADD CONSTRAINT msg_dispatch_jobs_read_2026_08_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_read_2026_09 msg_dispatch_jobs_read_2026_09_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read_2026_09
    ADD CONSTRAINT msg_dispatch_jobs_read_2026_09_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_read_2026_10 msg_dispatch_jobs_read_2026_10_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read_2026_10
    ADD CONSTRAINT msg_dispatch_jobs_read_2026_10_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_read_2026_11 msg_dispatch_jobs_read_2026_11_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read_2026_11
    ADD CONSTRAINT msg_dispatch_jobs_read_2026_11_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_jobs_read_2026_12 msg_dispatch_jobs_read_2026_12_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_jobs_read_2026_12
    ADD CONSTRAINT msg_dispatch_jobs_read_2026_12_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_dispatch_pools msg_dispatch_pools_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_dispatch_pools
    ADD CONSTRAINT msg_dispatch_pools_pkey PRIMARY KEY (id);


--
-- Name: msg_event_projection_feed msg_event_projection_feed_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_event_projection_feed
    ADD CONSTRAINT msg_event_projection_feed_pkey PRIMARY KEY (id);


--
-- Name: msg_event_type_spec_versions msg_event_type_spec_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_event_type_spec_versions
    ADD CONSTRAINT msg_event_type_spec_versions_pkey PRIMARY KEY (id);


--
-- Name: msg_event_types msg_event_types_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_event_types
    ADD CONSTRAINT msg_event_types_code_key UNIQUE (code);


--
-- Name: msg_event_types msg_event_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_event_types
    ADD CONSTRAINT msg_event_types_pkey PRIMARY KEY (id);


--
-- Name: msg_events msg_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events
    ADD CONSTRAINT msg_events_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_2026_08 msg_events_2026_08_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_2026_08
    ADD CONSTRAINT msg_events_2026_08_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_2026_09 msg_events_2026_09_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_2026_09
    ADD CONSTRAINT msg_events_2026_09_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_2026_10 msg_events_2026_10_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_2026_10
    ADD CONSTRAINT msg_events_2026_10_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_2026_11 msg_events_2026_11_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_2026_11
    ADD CONSTRAINT msg_events_2026_11_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_2026_12 msg_events_2026_12_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_2026_12
    ADD CONSTRAINT msg_events_2026_12_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_read msg_events_read_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read
    ADD CONSTRAINT msg_events_read_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_read_2026_08 msg_events_read_2026_08_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read_2026_08
    ADD CONSTRAINT msg_events_read_2026_08_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_read_2026_09 msg_events_read_2026_09_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read_2026_09
    ADD CONSTRAINT msg_events_read_2026_09_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_read_2026_10 msg_events_read_2026_10_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read_2026_10
    ADD CONSTRAINT msg_events_read_2026_10_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_read_2026_11 msg_events_read_2026_11_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read_2026_11
    ADD CONSTRAINT msg_events_read_2026_11_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_events_read_2026_12 msg_events_read_2026_12_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_events_read_2026_12
    ADD CONSTRAINT msg_events_read_2026_12_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_processes msg_processes_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_processes
    ADD CONSTRAINT msg_processes_code_key UNIQUE (code);


--
-- Name: msg_processes msg_processes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_processes
    ADD CONSTRAINT msg_processes_pkey PRIMARY KEY (id);


--
-- Name: msg_scheduled_job_instance_logs msg_scheduled_job_instance_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs
    ADD CONSTRAINT msg_scheduled_job_instance_logs_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_2026_08 msg_scheduled_job_instance_logs_2026_08_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs_2026_08
    ADD CONSTRAINT msg_scheduled_job_instance_logs_2026_08_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_2026_09 msg_scheduled_job_instance_logs_2026_09_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs_2026_09
    ADD CONSTRAINT msg_scheduled_job_instance_logs_2026_09_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_2026_10 msg_scheduled_job_instance_logs_2026_10_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs_2026_10
    ADD CONSTRAINT msg_scheduled_job_instance_logs_2026_10_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_2026_11 msg_scheduled_job_instance_logs_2026_11_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs_2026_11
    ADD CONSTRAINT msg_scheduled_job_instance_logs_2026_11_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_2026_12 msg_scheduled_job_instance_logs_2026_12_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instance_logs_2026_12
    ADD CONSTRAINT msg_scheduled_job_instance_logs_2026_12_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instances msg_scheduled_job_instances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances
    ADD CONSTRAINT msg_scheduled_job_instances_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instances_2026_08 msg_scheduled_job_instances_2026_08_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances_2026_08
    ADD CONSTRAINT msg_scheduled_job_instances_2026_08_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instances_2026_09 msg_scheduled_job_instances_2026_09_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances_2026_09
    ADD CONSTRAINT msg_scheduled_job_instances_2026_09_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instances_2026_10 msg_scheduled_job_instances_2026_10_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances_2026_10
    ADD CONSTRAINT msg_scheduled_job_instances_2026_10_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instances_2026_11 msg_scheduled_job_instances_2026_11_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances_2026_11
    ADD CONSTRAINT msg_scheduled_job_instances_2026_11_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_job_instances_2026_12 msg_scheduled_job_instances_2026_12_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_job_instances_2026_12
    ADD CONSTRAINT msg_scheduled_job_instances_2026_12_pkey PRIMARY KEY (id, created_at);


--
-- Name: msg_scheduled_jobs msg_scheduled_jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_scheduled_jobs
    ADD CONSTRAINT msg_scheduled_jobs_pkey PRIMARY KEY (id);


--
-- Name: msg_subscription_custom_configs msg_subscription_custom_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_subscription_custom_configs
    ADD CONSTRAINT msg_subscription_custom_configs_pkey PRIMARY KEY (id);


--
-- Name: msg_subscription_event_types msg_subscription_event_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_subscription_event_types
    ADD CONSTRAINT msg_subscription_event_types_pkey PRIMARY KEY (id);


--
-- Name: msg_subscriptions msg_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.msg_subscriptions
    ADD CONSTRAINT msg_subscriptions_pkey PRIMARY KEY (id);


--
-- Name: oauth_client_allowed_origins oauth_client_allowed_origins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_allowed_origins
    ADD CONSTRAINT oauth_client_allowed_origins_pkey PRIMARY KEY (oauth_client_id, allowed_origin);


--
-- Name: oauth_client_application_ids oauth_client_application_ids_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_application_ids
    ADD CONSTRAINT oauth_client_application_ids_pkey PRIMARY KEY (oauth_client_id, application_id);


--
-- Name: oauth_client_grant_types oauth_client_grant_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_grant_types
    ADD CONSTRAINT oauth_client_grant_types_pkey PRIMARY KEY (oauth_client_id, grant_type);


--
-- Name: oauth_client_post_logout_redirect_uris oauth_client_post_logout_redirect_uris_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_post_logout_redirect_uris
    ADD CONSTRAINT oauth_client_post_logout_redirect_uris_pkey PRIMARY KEY (oauth_client_id, post_logout_redirect_uri);


--
-- Name: oauth_client_redirect_uris oauth_client_redirect_uris_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_redirect_uris
    ADD CONSTRAINT oauth_client_redirect_uris_pkey PRIMARY KEY (oauth_client_id, redirect_uri);


--
-- Name: oauth_clients oauth_clients_client_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_clients
    ADD CONSTRAINT oauth_clients_client_id_key UNIQUE (client_id);


--
-- Name: oauth_clients oauth_clients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_clients
    ADD CONSTRAINT oauth_clients_pkey PRIMARY KEY (id);


--
-- Name: oauth_identity_provider_allowed_domains oauth_identity_provider_allowed_domains_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_identity_provider_allowed_domains
    ADD CONSTRAINT oauth_identity_provider_allowed_domains_pkey PRIMARY KEY (id);


--
-- Name: oauth_identity_provider_allowed_roles oauth_identity_provider_allowed_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_identity_provider_allowed_roles
    ADD CONSTRAINT oauth_identity_provider_allowed_roles_pkey PRIMARY KEY (id);


--
-- Name: oauth_identity_providers oauth_identity_providers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_identity_providers
    ADD CONSTRAINT oauth_identity_providers_pkey PRIMARY KEY (id);


--
-- Name: oauth_idp_role_mappings oauth_idp_role_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_idp_role_mappings
    ADD CONSTRAINT oauth_idp_role_mappings_pkey PRIMARY KEY (id);


--
-- Name: oauth_oidc_login_states oauth_oidc_login_states_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_oidc_login_states
    ADD CONSTRAINT oauth_oidc_login_states_pkey PRIMARY KEY (state);


--
-- Name: oauth_oidc_payloads oauth_oidc_payloads_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_oidc_payloads
    ADD CONSTRAINT oauth_oidc_payloads_pkey PRIMARY KEY (id);


--
-- Name: portal_apps portal_apps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portal_apps
    ADD CONSTRAINT portal_apps_pkey PRIMARY KEY (id);


--
-- Name: portal_identities portal_identities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portal_identities
    ADD CONSTRAINT portal_identities_pkey PRIMARY KEY (id);


--
-- Name: portal_identity_apps portal_identity_apps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portal_identity_apps
    ADD CONSTRAINT portal_identity_apps_pkey PRIMARY KEY (identity_id, portal_app_id);


--
-- Name: portal_login_flows portal_login_flows_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portal_login_flows
    ADD CONSTRAINT portal_login_flows_pkey PRIMARY KEY (id);


--
-- Name: tnt_anchor_domains tnt_anchor_domains_domain_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_anchor_domains
    ADD CONSTRAINT tnt_anchor_domains_domain_key UNIQUE (domain);


--
-- Name: tnt_anchor_domains tnt_anchor_domains_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_anchor_domains
    ADD CONSTRAINT tnt_anchor_domains_pkey PRIMARY KEY (id);


--
-- Name: tnt_client_auth_configs tnt_client_auth_configs_email_domain_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_client_auth_configs
    ADD CONSTRAINT tnt_client_auth_configs_email_domain_key UNIQUE (email_domain);


--
-- Name: tnt_client_auth_configs tnt_client_auth_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_client_auth_configs
    ADD CONSTRAINT tnt_client_auth_configs_pkey PRIMARY KEY (id);


--
-- Name: tnt_clients tnt_clients_identifier_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_clients
    ADD CONSTRAINT tnt_clients_identifier_key UNIQUE (identifier);


--
-- Name: tnt_clients tnt_clients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_clients
    ADD CONSTRAINT tnt_clients_pkey PRIMARY KEY (id);


--
-- Name: tnt_cors_allowed_origins tnt_cors_allowed_origins_origin_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_cors_allowed_origins
    ADD CONSTRAINT tnt_cors_allowed_origins_origin_key UNIQUE (origin);


--
-- Name: tnt_cors_allowed_origins tnt_cors_allowed_origins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_cors_allowed_origins
    ADD CONSTRAINT tnt_cors_allowed_origins_pkey PRIMARY KEY (id);


--
-- Name: tnt_email_domain_mapping_2fa_methods tnt_email_domain_mapping_2fa_methods_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mapping_2fa_methods
    ADD CONSTRAINT tnt_email_domain_mapping_2fa_methods_pkey PRIMARY KEY (id);


--
-- Name: tnt_email_domain_mapping_additional_clients tnt_email_domain_mapping_additional_clients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mapping_additional_clients
    ADD CONSTRAINT tnt_email_domain_mapping_additional_clients_pkey PRIMARY KEY (id);


--
-- Name: tnt_email_domain_mapping_allowed_roles tnt_email_domain_mapping_allowed_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mapping_allowed_roles
    ADD CONSTRAINT tnt_email_domain_mapping_allowed_roles_pkey PRIMARY KEY (id);


--
-- Name: tnt_email_domain_mapping_granted_clients tnt_email_domain_mapping_granted_clients_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mapping_granted_clients
    ADD CONSTRAINT tnt_email_domain_mapping_granted_clients_pkey PRIMARY KEY (id);


--
-- Name: tnt_email_domain_mappings tnt_email_domain_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tnt_email_domain_mappings
    ADD CONSTRAINT tnt_email_domain_mappings_pkey PRIMARY KEY (id);


--
-- Name: portal_apps uq_portal_apps_client_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portal_apps
    ADD CONSTRAINT uq_portal_apps_client_code UNIQUE (client_id, code);


--
-- Name: portal_identities uq_portal_identities_client_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portal_identities
    ADD CONSTRAINT uq_portal_identities_client_email UNIQUE (client_id, email);


--
-- Name: webauthn_credentials webauthn_credentials_credential_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webauthn_credentials
    ADD CONSTRAINT webauthn_credentials_credential_id_key UNIQUE (credential_id);


--
-- Name: webauthn_credentials webauthn_credentials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webauthn_credentials
    ADD CONSTRAINT webauthn_credentials_pkey PRIMARY KEY (id);


--
-- Name: idx_iam_login_attempts_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_login_attempts_type ON ONLY public.iam_login_attempts USING btree (attempt_type);


--
-- Name: iam_login_attempts_2026_q3_attempt_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q3_attempt_type_idx ON public.iam_login_attempts_2026_q3 USING btree (attempt_type);


--
-- Name: idx_iam_login_attempts_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_login_attempts_at ON ONLY public.iam_login_attempts USING btree (attempted_at);


--
-- Name: iam_login_attempts_2026_q3_attempted_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q3_attempted_at_idx ON public.iam_login_attempts_2026_q3 USING btree (attempted_at);


--
-- Name: idx_iam_login_attempts_identifier_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_login_attempts_identifier_at ON ONLY public.iam_login_attempts USING btree (identifier, attempted_at);


--
-- Name: iam_login_attempts_2026_q3_identifier_attempted_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q3_identifier_attempted_at_idx ON public.iam_login_attempts_2026_q3 USING btree (identifier, attempted_at);


--
-- Name: idx_iam_login_attempts_failure_throttle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_login_attempts_failure_throttle ON ONLY public.iam_login_attempts USING btree (identifier, attempted_at) WHERE ((outcome)::text = 'FAILURE'::text);


--
-- Name: iam_login_attempts_2026_q3_identifier_attempted_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q3_identifier_attempted_at_idx1 ON public.iam_login_attempts_2026_q3 USING btree (identifier, attempted_at) WHERE ((outcome)::text = 'FAILURE'::text);


--
-- Name: idx_iam_login_attempts_identifier_ip_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_login_attempts_identifier_ip_at ON ONLY public.iam_login_attempts USING btree (identifier, ip_address, attempted_at);


--
-- Name: iam_login_attempts_2026_q3_identifier_ip_address_attempted__idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q3_identifier_ip_address_attempted__idx ON public.iam_login_attempts_2026_q3 USING btree (identifier, ip_address, attempted_at);


--
-- Name: idx_iam_login_attempts_outcome; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_login_attempts_outcome ON ONLY public.iam_login_attempts USING btree (outcome);


--
-- Name: iam_login_attempts_2026_q3_outcome_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q3_outcome_idx ON public.iam_login_attempts_2026_q3 USING btree (outcome);


--
-- Name: idx_iam_login_attempts_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_login_attempts_principal ON ONLY public.iam_login_attempts USING btree (principal_id);


--
-- Name: iam_login_attempts_2026_q3_principal_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q3_principal_id_idx ON public.iam_login_attempts_2026_q3 USING btree (principal_id);


--
-- Name: iam_login_attempts_2026_q4_attempt_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q4_attempt_type_idx ON public.iam_login_attempts_2026_q4 USING btree (attempt_type);


--
-- Name: iam_login_attempts_2026_q4_attempted_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q4_attempted_at_idx ON public.iam_login_attempts_2026_q4 USING btree (attempted_at);


--
-- Name: iam_login_attempts_2026_q4_identifier_attempted_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q4_identifier_attempted_at_idx ON public.iam_login_attempts_2026_q4 USING btree (identifier, attempted_at);


--
-- Name: iam_login_attempts_2026_q4_identifier_attempted_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q4_identifier_attempted_at_idx1 ON public.iam_login_attempts_2026_q4 USING btree (identifier, attempted_at) WHERE ((outcome)::text = 'FAILURE'::text);


--
-- Name: iam_login_attempts_2026_q4_identifier_ip_address_attempted__idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q4_identifier_ip_address_attempted__idx ON public.iam_login_attempts_2026_q4 USING btree (identifier, ip_address, attempted_at);


--
-- Name: iam_login_attempts_2026_q4_outcome_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q4_outcome_idx ON public.iam_login_attempts_2026_q4 USING btree (outcome);


--
-- Name: iam_login_attempts_2026_q4_principal_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_2026_q4_principal_id_idx ON public.iam_login_attempts_2026_q4 USING btree (principal_id);


--
-- Name: iam_login_attempts_default_attempt_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_default_attempt_type_idx ON public.iam_login_attempts_default USING btree (attempt_type);


--
-- Name: iam_login_attempts_default_attempted_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_default_attempted_at_idx ON public.iam_login_attempts_default USING btree (attempted_at);


--
-- Name: iam_login_attempts_default_identifier_attempted_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_default_identifier_attempted_at_idx ON public.iam_login_attempts_default USING btree (identifier, attempted_at);


--
-- Name: iam_login_attempts_default_identifier_attempted_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_default_identifier_attempted_at_idx1 ON public.iam_login_attempts_default USING btree (identifier, attempted_at) WHERE ((outcome)::text = 'FAILURE'::text);


--
-- Name: iam_login_attempts_default_identifier_ip_address_attempted__idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_default_identifier_ip_address_attempted__idx ON public.iam_login_attempts_default USING btree (identifier, ip_address, attempted_at);


--
-- Name: iam_login_attempts_default_outcome_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_default_outcome_idx ON public.iam_login_attempts_default USING btree (outcome);


--
-- Name: iam_login_attempts_default_principal_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX iam_login_attempts_default_principal_id_idx ON public.iam_login_attempts_default USING btree (principal_id);


--
-- Name: idx_app_applications_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_applications_active ON public.app_applications USING btree (active);


--
-- Name: idx_app_applications_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_applications_code ON public.app_applications USING btree (code);


--
-- Name: idx_app_applications_service_account_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_applications_service_account_id ON public.app_applications USING btree (service_account_id) WHERE (service_account_id IS NOT NULL);


--
-- Name: idx_app_applications_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_applications_type ON public.app_applications USING btree (type);


--
-- Name: idx_app_client_configs_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_client_configs_app ON public.app_client_configs USING btree (application_id);


--
-- Name: idx_app_client_configs_clt; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_client_configs_clt ON public.app_client_configs USING btree (client_id);


--
-- Name: idx_app_config_access_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_config_access_app ON public.app_platform_config_access USING btree (application_code);


--
-- Name: idx_app_config_access_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_config_access_role ON public.app_platform_config_access USING btree (role_code);


--
-- Name: idx_app_docs_application; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_docs_application ON public.app_docs USING btree (application_id, "position");


--
-- Name: idx_app_openapi_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_openapi_app ON public.app_application_openapi_specs USING btree (application_id, synced_at DESC);


--
-- Name: idx_app_openapi_one_current; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_app_openapi_one_current ON public.app_application_openapi_specs USING btree (application_id) WHERE ((status)::text = 'CURRENT'::text);


--
-- Name: idx_app_platform_configs_app_section; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_platform_configs_app_section ON public.app_platform_configs USING btree (application_code, section);


--
-- Name: idx_app_platform_configs_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_platform_configs_lookup ON public.app_platform_configs USING btree (application_code, section, scope, client_id);


--
-- Name: idx_aud_logs_application_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aud_logs_application_id ON public.aud_logs USING btree (application_id);


--
-- Name: idx_aud_logs_client_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aud_logs_client_id ON public.aud_logs USING btree (client_id);


--
-- Name: idx_aud_logs_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aud_logs_entity ON public.aud_logs USING btree (entity_type, entity_id);


--
-- Name: idx_aud_logs_operation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aud_logs_operation ON public.aud_logs USING btree (operation);


--
-- Name: idx_aud_logs_performed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aud_logs_performed ON public.aud_logs USING btree (performed_at);


--
-- Name: idx_aud_logs_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aud_logs_principal ON public.aud_logs USING btree (principal_id);


--
-- Name: idx_dispatch_jobs_blocked_groups; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dispatch_jobs_blocked_groups ON ONLY public.msg_dispatch_jobs USING btree (message_group, status) WHERE ((status)::text = ANY ((ARRAY['FAILED'::character varying, 'ERROR'::character varying])::text[]));


--
-- Name: idx_dispatch_jobs_pending_poll; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dispatch_jobs_pending_poll ON ONLY public.msg_dispatch_jobs USING btree (message_group, sequence, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_dispatch_jobs_stale_queued; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dispatch_jobs_stale_queued ON ONLY public.msg_dispatch_jobs USING btree (queued_at) WHERE ((status)::text = 'QUEUED'::text);


--
-- Name: idx_iam_auth_codes_client; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_auth_codes_client ON public.iam_authorization_codes USING btree (client_id);


--
-- Name: idx_iam_auth_codes_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_auth_codes_expires ON public.iam_authorization_codes USING btree (expires_at);


--
-- Name: idx_iam_auth_codes_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_auth_codes_principal ON public.iam_authorization_codes USING btree (principal_id);


--
-- Name: idx_iam_client_access_grants_client; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_client_access_grants_client ON public.iam_client_access_grants USING btree (client_id);


--
-- Name: idx_iam_client_access_grants_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_client_access_grants_principal ON public.iam_client_access_grants USING btree (principal_id);


--
-- Name: idx_iam_mfa_email_pins_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_mfa_email_pins_expires ON public.iam_mfa_email_pins USING btree (expires_at);


--
-- Name: idx_iam_mfa_email_pins_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_mfa_email_pins_principal ON public.iam_mfa_email_pins USING btree (principal_id);


--
-- Name: idx_iam_mfa_trusted_devices_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_mfa_trusted_devices_hash ON public.iam_mfa_trusted_devices USING btree (token_hash);


--
-- Name: idx_iam_mfa_trusted_devices_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_mfa_trusted_devices_principal ON public.iam_mfa_trusted_devices USING btree (principal_id);


--
-- Name: idx_iam_oidc_login_states_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_oidc_login_states_expires ON public.iam_oidc_login_states USING btree (expires_at);


--
-- Name: idx_iam_password_reset_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_password_reset_principal ON public.iam_password_reset_tokens USING btree (principal_id);


--
-- Name: idx_iam_password_reset_token_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_password_reset_token_hash ON public.iam_password_reset_tokens USING btree (token_hash);


--
-- Name: idx_iam_permissions_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_permissions_code ON public.iam_permissions USING btree (code);


--
-- Name: idx_iam_permissions_context; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_permissions_context ON public.iam_permissions USING btree (context);


--
-- Name: idx_iam_permissions_subdomain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_permissions_subdomain ON public.iam_permissions USING btree (subdomain);


--
-- Name: idx_iam_principal_app_access_app_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_principal_app_access_app_id ON public.iam_principal_application_access USING btree (application_id);


--
-- Name: idx_iam_principal_roles_assigned_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_principal_roles_assigned_at ON public.iam_principal_roles USING btree (assigned_at);


--
-- Name: idx_iam_principal_roles_role_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_principal_roles_role_name ON public.iam_principal_roles USING btree (role_name);


--
-- Name: idx_iam_principals_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_principals_active ON public.iam_principals USING btree (active);


--
-- Name: idx_iam_principals_client_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_principals_client_id ON public.iam_principals USING btree (client_id);


--
-- Name: idx_iam_principals_email; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_iam_principals_email ON public.iam_principals USING btree (email);


--
-- Name: idx_iam_principals_email_domain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_principals_email_domain ON public.iam_principals USING btree (email_domain);


--
-- Name: idx_iam_principals_service_account_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_iam_principals_service_account_id ON public.iam_principals USING btree (service_account_id);


--
-- Name: idx_iam_principals_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_principals_type ON public.iam_principals USING btree (type);


--
-- Name: idx_iam_rate_limit_events_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_rate_limit_events_lookup ON public.iam_rate_limit_events USING btree (bucket, key, occurred_at DESC);


--
-- Name: idx_iam_rate_limit_events_occurred_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_rate_limit_events_occurred_at ON public.iam_rate_limit_events USING btree (occurred_at);


--
-- Name: idx_iam_refresh_tokens_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_refresh_tokens_expires ON public.iam_refresh_tokens USING btree (expires_at);


--
-- Name: idx_iam_refresh_tokens_family; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_refresh_tokens_family ON public.iam_refresh_tokens USING btree (token_family);


--
-- Name: idx_iam_refresh_tokens_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_refresh_tokens_hash ON public.iam_refresh_tokens USING btree (token_hash);


--
-- Name: idx_iam_refresh_tokens_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_refresh_tokens_principal ON public.iam_refresh_tokens USING btree (principal_id);


--
-- Name: idx_iam_refresh_tokens_revoked; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_refresh_tokens_revoked ON public.iam_refresh_tokens USING btree (revoked);


--
-- Name: idx_iam_reset_approval_client_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_reset_approval_client_status ON public.iam_reset_approval_requests USING btree (client_id, status);


--
-- Name: idx_iam_reset_approval_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_reset_approval_principal ON public.iam_reset_approval_requests USING btree (principal_id);


--
-- Name: idx_iam_role_permissions_role_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_role_permissions_role_id ON public.iam_role_permissions USING btree (role_id);


--
-- Name: idx_iam_roles_application_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_roles_application_code ON public.iam_roles USING btree (application_code);


--
-- Name: idx_iam_roles_application_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_roles_application_id ON public.iam_roles USING btree (application_id);


--
-- Name: idx_iam_roles_client_managed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_roles_client_managed ON public.iam_roles USING btree (client_managed);


--
-- Name: idx_iam_roles_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_roles_name ON public.iam_roles USING btree (name);


--
-- Name: idx_iam_roles_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_roles_source ON public.iam_roles USING btree (source);


--
-- Name: idx_iam_service_accounts_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_service_accounts_active ON public.iam_service_accounts USING btree (active);


--
-- Name: idx_iam_service_accounts_application_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_service_accounts_application_id ON public.iam_service_accounts USING btree (application_id);


--
-- Name: idx_iam_service_accounts_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_iam_service_accounts_code ON public.iam_service_accounts USING btree (code);


--
-- Name: idx_iam_user_mfa_methods_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_user_mfa_methods_principal ON public.iam_user_mfa_methods USING btree (principal_id);


--
-- Name: idx_iam_user_mfa_methods_principal_method; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_iam_user_mfa_methods_principal_method ON public.iam_user_mfa_methods USING btree (principal_id, method);


--
-- Name: idx_iam_user_mfa_recovery_codes_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_user_mfa_recovery_codes_hash ON public.iam_user_mfa_recovery_codes USING btree (code_hash);


--
-- Name: idx_iam_user_mfa_recovery_codes_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_iam_user_mfa_recovery_codes_principal ON public.iam_user_mfa_recovery_codes USING btree (principal_id);


--
-- Name: idx_msg_connections_client_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_connections_client_id ON public.msg_connections USING btree (client_id);


--
-- Name: idx_msg_connections_service_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_connections_service_account ON public.msg_connections USING btree (service_account_id);


--
-- Name: idx_msg_connections_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_connections_status ON public.msg_connections USING btree (status);


--
-- Name: idx_msg_dispatch_job_attempts_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_job_attempts_job ON ONLY public.msg_dispatch_job_attempts USING btree (dispatch_job_id);


--
-- Name: idx_msg_dispatch_job_attempts_job_number; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_msg_dispatch_job_attempts_job_number ON ONLY public.msg_dispatch_job_attempts USING btree (dispatch_job_id, attempt_number, created_at);


--
-- Name: idx_msg_dispatch_jobs_dirty; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_dirty ON ONLY public.msg_dispatch_jobs USING btree (created_at) WHERE ((projected_at IS NULL) OR (updated_at > projected_at));


--
-- Name: idx_msg_dispatch_jobs_read_application; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_application ON ONLY public.msg_dispatch_jobs_read USING btree (application);


--
-- Name: idx_msg_dispatch_jobs_read_client_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_client_created ON ONLY public.msg_dispatch_jobs_read USING btree (client_id, created_at DESC);


--
-- Name: idx_msg_dispatch_jobs_read_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_code ON ONLY public.msg_dispatch_jobs_read USING btree (code);


--
-- Name: idx_msg_dispatch_jobs_read_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_created_at ON ONLY public.msg_dispatch_jobs_read USING btree (created_at);


--
-- Name: idx_msg_dispatch_jobs_read_dispatch_pool_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_dispatch_pool_id ON ONLY public.msg_dispatch_jobs_read USING btree (dispatch_pool_id);


--
-- Name: idx_msg_dispatch_jobs_read_event_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_event_id ON ONLY public.msg_dispatch_jobs_read USING btree (event_id);


--
-- Name: idx_msg_dispatch_jobs_read_message_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_message_group ON ONLY public.msg_dispatch_jobs_read USING btree (message_group);


--
-- Name: idx_msg_dispatch_jobs_read_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_status_created ON ONLY public.msg_dispatch_jobs_read USING btree (status, created_at DESC);


--
-- Name: idx_msg_dispatch_jobs_read_subscription_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_jobs_read_subscription_id ON ONLY public.msg_dispatch_jobs_read USING btree (subscription_id);


--
-- Name: idx_msg_dispatch_pools_client_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_pools_client_id ON public.msg_dispatch_pools USING btree (client_id);


--
-- Name: idx_msg_dispatch_pools_code_client; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_msg_dispatch_pools_code_client ON public.msg_dispatch_pools USING btree (code, client_id);


--
-- Name: idx_msg_dispatch_pools_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dispatch_pools_status ON public.msg_dispatch_pools USING btree (status);


--
-- Name: idx_msg_dj_projection_feed_in_progress; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dj_projection_feed_in_progress ON public.msg_dispatch_job_projection_feed USING btree (id) WHERE (processed = 9);


--
-- Name: idx_msg_dj_projection_feed_processed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dj_projection_feed_processed_at ON public.msg_dispatch_job_projection_feed USING btree (processed_at) WHERE (processed = 1);


--
-- Name: idx_msg_dj_projection_feed_unprocessed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_dj_projection_feed_unprocessed ON public.msg_dispatch_job_projection_feed USING btree (dispatch_job_id, id) WHERE (processed = 0);


--
-- Name: idx_msg_event_projection_feed_in_progress; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_event_projection_feed_in_progress ON public.msg_event_projection_feed USING btree (id) WHERE (processed = 9);


--
-- Name: idx_msg_event_projection_feed_unprocessed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_event_projection_feed_unprocessed ON public.msg_event_projection_feed USING btree (id) WHERE (processed = 0);


--
-- Name: idx_msg_event_types_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_event_types_aggregate ON public.msg_event_types USING btree (aggregate);


--
-- Name: idx_msg_event_types_application; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_event_types_application ON public.msg_event_types USING btree (application);


--
-- Name: idx_msg_event_types_code; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_event_types_code ON public.msg_event_types USING btree (code);


--
-- Name: idx_msg_event_types_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_event_types_source ON public.msg_event_types USING btree (source);


--
-- Name: idx_msg_event_types_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_event_types_status ON public.msg_event_types USING btree (status);


--
-- Name: idx_msg_event_types_subdomain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_event_types_subdomain ON public.msg_event_types USING btree (subdomain);


--
-- Name: idx_msg_events_client_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_client_id ON ONLY public.msg_events USING btree (client_id);


--
-- Name: idx_msg_events_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_created_at ON ONLY public.msg_events USING btree (created_at);


--
-- Name: idx_msg_events_deduplication; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_msg_events_deduplication ON ONLY public.msg_events USING btree (deduplication_id, created_at);


--
-- Name: idx_msg_events_read_aggregate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_read_aggregate ON ONLY public.msg_events_read USING btree (aggregate);


--
-- Name: idx_msg_events_read_application; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_read_application ON ONLY public.msg_events_read USING btree (application);


--
-- Name: idx_msg_events_read_client_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_read_client_created ON ONLY public.msg_events_read USING btree (client_id, created_at DESC);


--
-- Name: idx_msg_events_read_correlation_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_read_correlation_id ON ONLY public.msg_events_read USING btree (correlation_id);


--
-- Name: idx_msg_events_read_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_read_created_at ON ONLY public.msg_events_read USING btree (created_at);


--
-- Name: idx_msg_events_read_subdomain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_read_subdomain ON ONLY public.msg_events_read USING btree (subdomain);


--
-- Name: idx_msg_events_read_type_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_read_type_created ON ONLY public.msg_events_read USING btree (type, created_at DESC);


--
-- Name: idx_msg_events_unfanned; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_unfanned ON ONLY public.msg_events USING btree (created_at) WHERE (fanned_out_at IS NULL);


--
-- Name: idx_msg_events_unprojected; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_events_unprojected ON ONLY public.msg_events USING btree (created_at) WHERE (projected_at IS NULL);


--
-- Name: idx_msg_processes_application; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_processes_application ON public.msg_processes USING btree (application);


--
-- Name: idx_msg_processes_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_processes_source ON public.msg_processes USING btree (source);


--
-- Name: idx_msg_processes_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_processes_status ON public.msg_processes USING btree (status);


--
-- Name: idx_msg_processes_subdomain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_processes_subdomain ON public.msg_processes USING btree (subdomain);


--
-- Name: idx_msg_scheduled_job_instance_logs_instance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_job_instance_logs_instance ON ONLY public.msg_scheduled_job_instance_logs USING btree (instance_id, created_at);


--
-- Name: idx_msg_scheduled_job_instance_logs_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_job_instance_logs_job ON ONLY public.msg_scheduled_job_instance_logs USING btree (scheduled_job_id, created_at DESC);


--
-- Name: idx_msg_scheduled_job_instances_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_job_instances_active ON ONLY public.msg_scheduled_job_instances USING btree (scheduled_job_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying])::text[]));


--
-- Name: idx_msg_scheduled_job_instances_client; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_job_instances_client ON ONLY public.msg_scheduled_job_instances USING btree (client_id, created_at DESC);


--
-- Name: idx_msg_scheduled_job_instances_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_job_instances_job ON ONLY public.msg_scheduled_job_instances USING btree (scheduled_job_id, created_at DESC);


--
-- Name: idx_msg_scheduled_job_instances_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_job_instances_status ON ONLY public.msg_scheduled_job_instances USING btree (status, created_at);


--
-- Name: idx_msg_scheduled_jobs_active_poll; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_jobs_active_poll ON public.msg_scheduled_jobs USING btree (last_fired_at NULLS FIRST) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: idx_msg_scheduled_jobs_application_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_jobs_application_id ON public.msg_scheduled_jobs USING btree (application_id);


--
-- Name: idx_msg_scheduled_jobs_client_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_scheduled_jobs_client_id ON public.msg_scheduled_jobs USING btree (client_id);


--
-- Name: idx_msg_scheduled_jobs_code_per_client; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_msg_scheduled_jobs_code_per_client ON public.msg_scheduled_jobs USING btree (client_id, code) WHERE (client_id IS NOT NULL);


--
-- Name: idx_msg_scheduled_jobs_code_platform; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_msg_scheduled_jobs_code_platform ON public.msg_scheduled_jobs USING btree (code) WHERE (client_id IS NULL);


--
-- Name: idx_msg_spec_versions_event_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_spec_versions_event_type ON public.msg_event_type_spec_versions USING btree (event_type_id);


--
-- Name: idx_msg_spec_versions_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_spec_versions_status ON public.msg_event_type_spec_versions USING btree (status);


--
-- Name: idx_msg_sub_configs_subscription; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_sub_configs_subscription ON public.msg_subscription_custom_configs USING btree (subscription_id);


--
-- Name: idx_msg_sub_event_types_event_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_sub_event_types_event_type ON public.msg_subscription_event_types USING btree (event_type_id);


--
-- Name: idx_msg_sub_event_types_subscription; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_sub_event_types_subscription ON public.msg_subscription_event_types USING btree (subscription_id);


--
-- Name: idx_msg_subscriptions_client_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_subscriptions_client_id ON public.msg_subscriptions USING btree (client_id);


--
-- Name: idx_msg_subscriptions_connection_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_subscriptions_connection_id ON public.msg_subscriptions USING btree (connection_id);


--
-- Name: idx_msg_subscriptions_dispatch_pool; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_subscriptions_dispatch_pool ON public.msg_subscriptions USING btree (dispatch_pool_id);


--
-- Name: idx_msg_subscriptions_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_subscriptions_source ON public.msg_subscriptions USING btree (source);


--
-- Name: idx_msg_subscriptions_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msg_subscriptions_status ON public.msg_subscriptions USING btree (status);


--
-- Name: idx_oauth_client_allowed_origins_client; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_client_allowed_origins_client ON public.oauth_client_allowed_origins USING btree (oauth_client_id);


--
-- Name: idx_oauth_client_allowed_origins_origin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_client_allowed_origins_origin ON public.oauth_client_allowed_origins USING btree (allowed_origin);


--
-- Name: idx_oauth_client_application_ids_client; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_client_application_ids_client ON public.oauth_client_application_ids USING btree (oauth_client_id);


--
-- Name: idx_oauth_client_grant_types_client; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_client_grant_types_client ON public.oauth_client_grant_types USING btree (oauth_client_id);


--
-- Name: idx_oauth_client_post_logout_redirect_uris_client; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_client_post_logout_redirect_uris_client ON public.oauth_client_post_logout_redirect_uris USING btree (oauth_client_id);


--
-- Name: idx_oauth_client_redirect_uris_client; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_client_redirect_uris_client ON public.oauth_client_redirect_uris USING btree (oauth_client_id);


--
-- Name: idx_oauth_clients_previous_secret_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_clients_previous_secret_expires_at ON public.oauth_clients USING btree (previous_secret_expires_at) WHERE (previous_secret_ref IS NOT NULL);


--
-- Name: idx_oauth_clients_service_account_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_clients_service_account_principal ON public.oauth_clients USING btree (service_account_principal_id) WHERE (service_account_principal_id IS NOT NULL);


--
-- Name: idx_oauth_identity_providers_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_oauth_identity_providers_code ON public.oauth_identity_providers USING btree (code);


--
-- Name: idx_oauth_idp_allowed_domains_idp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_idp_allowed_domains_idp ON public.oauth_identity_provider_allowed_domains USING btree (identity_provider_id);


--
-- Name: idx_oauth_idp_allowed_roles_idp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_idp_allowed_roles_idp ON public.oauth_identity_provider_allowed_roles USING btree (identity_provider_id);


--
-- Name: idx_oauth_idp_role_mappings_idp_role_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_oauth_idp_role_mappings_idp_role_name ON public.oauth_idp_role_mappings USING btree (idp_role_name);


--
-- Name: idx_oauth_oidc_login_states_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_oauth_oidc_login_states_expires ON public.oauth_oidc_login_states USING btree (expires_at);


--
-- Name: idx_portal_identities_client_email_prefix; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portal_identities_client_email_prefix ON public.portal_identities USING btree (client_id, email text_pattern_ops);


--
-- Name: idx_portal_identities_client_name_prefix; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portal_identities_client_name_prefix ON public.portal_identities USING btree (client_id, lower((name)::text) text_pattern_ops);


--
-- Name: idx_portal_identities_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portal_identities_email ON public.portal_identities USING btree (email);


--
-- Name: idx_portal_identity_apps_app; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portal_identity_apps_app ON public.portal_identity_apps USING btree (portal_app_id);


--
-- Name: idx_tnt_clients_identifier; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnt_clients_identifier ON public.tnt_clients USING btree (identifier);


--
-- Name: idx_tnt_clients_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnt_clients_status ON public.tnt_clients USING btree (status);


--
-- Name: idx_tnt_edm_2fa_methods_mapping; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnt_edm_2fa_methods_mapping ON public.tnt_email_domain_mapping_2fa_methods USING btree (email_domain_mapping_id);


--
-- Name: idx_tnt_edm_additional_clients_mapping; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnt_edm_additional_clients_mapping ON public.tnt_email_domain_mapping_additional_clients USING btree (email_domain_mapping_id);


--
-- Name: idx_tnt_edm_allowed_roles_mapping; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnt_edm_allowed_roles_mapping ON public.tnt_email_domain_mapping_allowed_roles USING btree (email_domain_mapping_id);


--
-- Name: idx_tnt_edm_granted_clients_mapping; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnt_edm_granted_clients_mapping ON public.tnt_email_domain_mapping_granted_clients USING btree (email_domain_mapping_id);


--
-- Name: idx_tnt_email_domain_mappings_domain; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_tnt_email_domain_mappings_domain ON public.tnt_email_domain_mappings USING btree (email_domain);


--
-- Name: idx_tnt_email_domain_mappings_idp; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnt_email_domain_mappings_idp ON public.tnt_email_domain_mappings USING btree (identity_provider_id);


--
-- Name: idx_tnt_email_domain_mappings_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tnt_email_domain_mappings_scope ON public.tnt_email_domain_mappings USING btree (scope_type);


--
-- Name: idx_webauthn_credentials_principal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_webauthn_credentials_principal ON public.webauthn_credentials USING btree (principal_id);


--
-- Name: msg_dispatch_job_attempts_2026_08_dispatch_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_job_attempts_2026_08_dispatch_job_id_idx ON public.msg_dispatch_job_attempts_2026_08 USING btree (dispatch_job_id);


--
-- Name: msg_dispatch_job_attempts_2026_09_dispatch_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_job_attempts_2026_09_dispatch_job_id_idx ON public.msg_dispatch_job_attempts_2026_09 USING btree (dispatch_job_id);


--
-- Name: msg_dispatch_job_attempts_2026_10_dispatch_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_job_attempts_2026_10_dispatch_job_id_idx ON public.msg_dispatch_job_attempts_2026_10 USING btree (dispatch_job_id);


--
-- Name: msg_dispatch_job_attempts_2026_11_dispatch_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_job_attempts_2026_11_dispatch_job_id_idx ON public.msg_dispatch_job_attempts_2026_11 USING btree (dispatch_job_id);


--
-- Name: msg_dispatch_job_attempts_2026_12_dispatch_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_job_attempts_2026_12_dispatch_job_id_idx ON public.msg_dispatch_job_attempts_2026_12 USING btree (dispatch_job_id);


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx1 ON public.msg_dispatch_job_attempts_2026_09 USING btree (dispatch_job_id, attempt_number, created_at);


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx2 ON public.msg_dispatch_job_attempts_2026_10 USING btree (dispatch_job_id, attempt_number, created_at);


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx3; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx3 ON public.msg_dispatch_job_attempts_2026_11 USING btree (dispatch_job_id, attempt_number, created_at);


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx4; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx4 ON public.msg_dispatch_job_attempts_2026_12 USING btree (dispatch_job_id, attempt_number, created_at);


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numbe_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numbe_idx ON public.msg_dispatch_job_attempts_2026_08 USING btree (dispatch_job_id, attempt_number, created_at);


--
-- Name: msg_dispatch_jobs_2026_08_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_08_created_at_idx ON public.msg_dispatch_jobs_2026_08 USING btree (created_at) WHERE ((projected_at IS NULL) OR (updated_at > projected_at));


--
-- Name: msg_dispatch_jobs_2026_08_message_group_sequence_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_08_message_group_sequence_created_at_idx ON public.msg_dispatch_jobs_2026_08 USING btree (message_group, sequence, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: msg_dispatch_jobs_2026_08_message_group_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_08_message_group_status_idx ON public.msg_dispatch_jobs_2026_08 USING btree (message_group, status) WHERE ((status)::text = ANY ((ARRAY['FAILED'::character varying, 'ERROR'::character varying])::text[]));


--
-- Name: msg_dispatch_jobs_2026_08_queued_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_08_queued_at_idx ON public.msg_dispatch_jobs_2026_08 USING btree (queued_at) WHERE ((status)::text = 'QUEUED'::text);


--
-- Name: msg_dispatch_jobs_2026_09_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_09_created_at_idx ON public.msg_dispatch_jobs_2026_09 USING btree (created_at) WHERE ((projected_at IS NULL) OR (updated_at > projected_at));


--
-- Name: msg_dispatch_jobs_2026_09_message_group_sequence_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_09_message_group_sequence_created_at_idx ON public.msg_dispatch_jobs_2026_09 USING btree (message_group, sequence, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: msg_dispatch_jobs_2026_09_message_group_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_09_message_group_status_idx ON public.msg_dispatch_jobs_2026_09 USING btree (message_group, status) WHERE ((status)::text = ANY ((ARRAY['FAILED'::character varying, 'ERROR'::character varying])::text[]));


--
-- Name: msg_dispatch_jobs_2026_09_queued_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_09_queued_at_idx ON public.msg_dispatch_jobs_2026_09 USING btree (queued_at) WHERE ((status)::text = 'QUEUED'::text);


--
-- Name: msg_dispatch_jobs_2026_10_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_10_created_at_idx ON public.msg_dispatch_jobs_2026_10 USING btree (created_at) WHERE ((projected_at IS NULL) OR (updated_at > projected_at));


--
-- Name: msg_dispatch_jobs_2026_10_message_group_sequence_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_10_message_group_sequence_created_at_idx ON public.msg_dispatch_jobs_2026_10 USING btree (message_group, sequence, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: msg_dispatch_jobs_2026_10_message_group_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_10_message_group_status_idx ON public.msg_dispatch_jobs_2026_10 USING btree (message_group, status) WHERE ((status)::text = ANY ((ARRAY['FAILED'::character varying, 'ERROR'::character varying])::text[]));


--
-- Name: msg_dispatch_jobs_2026_10_queued_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_10_queued_at_idx ON public.msg_dispatch_jobs_2026_10 USING btree (queued_at) WHERE ((status)::text = 'QUEUED'::text);


--
-- Name: msg_dispatch_jobs_2026_11_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_11_created_at_idx ON public.msg_dispatch_jobs_2026_11 USING btree (created_at) WHERE ((projected_at IS NULL) OR (updated_at > projected_at));


--
-- Name: msg_dispatch_jobs_2026_11_message_group_sequence_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_11_message_group_sequence_created_at_idx ON public.msg_dispatch_jobs_2026_11 USING btree (message_group, sequence, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: msg_dispatch_jobs_2026_11_message_group_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_11_message_group_status_idx ON public.msg_dispatch_jobs_2026_11 USING btree (message_group, status) WHERE ((status)::text = ANY ((ARRAY['FAILED'::character varying, 'ERROR'::character varying])::text[]));


--
-- Name: msg_dispatch_jobs_2026_11_queued_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_11_queued_at_idx ON public.msg_dispatch_jobs_2026_11 USING btree (queued_at) WHERE ((status)::text = 'QUEUED'::text);


--
-- Name: msg_dispatch_jobs_2026_12_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_12_created_at_idx ON public.msg_dispatch_jobs_2026_12 USING btree (created_at) WHERE ((projected_at IS NULL) OR (updated_at > projected_at));


--
-- Name: msg_dispatch_jobs_2026_12_message_group_sequence_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_12_message_group_sequence_created_at_idx ON public.msg_dispatch_jobs_2026_12 USING btree (message_group, sequence, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: msg_dispatch_jobs_2026_12_message_group_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_12_message_group_status_idx ON public.msg_dispatch_jobs_2026_12 USING btree (message_group, status) WHERE ((status)::text = ANY ((ARRAY['FAILED'::character varying, 'ERROR'::character varying])::text[]));


--
-- Name: msg_dispatch_jobs_2026_12_queued_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_2026_12_queued_at_idx ON public.msg_dispatch_jobs_2026_12 USING btree (queued_at) WHERE ((status)::text = 'QUEUED'::text);


--
-- Name: msg_dispatch_jobs_read_2026_08_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_application_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (application);


--
-- Name: msg_dispatch_jobs_read_2026_08_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_client_id_created_at_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (client_id, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_08_code_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_code_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (code);


--
-- Name: msg_dispatch_jobs_read_2026_08_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_created_at_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (created_at);


--
-- Name: msg_dispatch_jobs_read_2026_08_dispatch_pool_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_dispatch_pool_id_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (dispatch_pool_id);


--
-- Name: msg_dispatch_jobs_read_2026_08_event_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_event_id_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (event_id);


--
-- Name: msg_dispatch_jobs_read_2026_08_message_group_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_message_group_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (message_group);


--
-- Name: msg_dispatch_jobs_read_2026_08_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_status_created_at_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (status, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_08_subscription_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_08_subscription_id_idx ON public.msg_dispatch_jobs_read_2026_08 USING btree (subscription_id);


--
-- Name: msg_dispatch_jobs_read_2026_09_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_application_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (application);


--
-- Name: msg_dispatch_jobs_read_2026_09_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_client_id_created_at_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (client_id, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_09_code_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_code_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (code);


--
-- Name: msg_dispatch_jobs_read_2026_09_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_created_at_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (created_at);


--
-- Name: msg_dispatch_jobs_read_2026_09_dispatch_pool_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_dispatch_pool_id_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (dispatch_pool_id);


--
-- Name: msg_dispatch_jobs_read_2026_09_event_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_event_id_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (event_id);


--
-- Name: msg_dispatch_jobs_read_2026_09_message_group_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_message_group_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (message_group);


--
-- Name: msg_dispatch_jobs_read_2026_09_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_status_created_at_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (status, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_09_subscription_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_09_subscription_id_idx ON public.msg_dispatch_jobs_read_2026_09 USING btree (subscription_id);


--
-- Name: msg_dispatch_jobs_read_2026_10_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_application_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (application);


--
-- Name: msg_dispatch_jobs_read_2026_10_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_client_id_created_at_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (client_id, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_10_code_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_code_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (code);


--
-- Name: msg_dispatch_jobs_read_2026_10_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_created_at_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (created_at);


--
-- Name: msg_dispatch_jobs_read_2026_10_dispatch_pool_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_dispatch_pool_id_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (dispatch_pool_id);


--
-- Name: msg_dispatch_jobs_read_2026_10_event_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_event_id_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (event_id);


--
-- Name: msg_dispatch_jobs_read_2026_10_message_group_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_message_group_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (message_group);


--
-- Name: msg_dispatch_jobs_read_2026_10_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_status_created_at_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (status, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_10_subscription_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_10_subscription_id_idx ON public.msg_dispatch_jobs_read_2026_10 USING btree (subscription_id);


--
-- Name: msg_dispatch_jobs_read_2026_11_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_application_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (application);


--
-- Name: msg_dispatch_jobs_read_2026_11_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_client_id_created_at_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (client_id, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_11_code_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_code_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (code);


--
-- Name: msg_dispatch_jobs_read_2026_11_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_created_at_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (created_at);


--
-- Name: msg_dispatch_jobs_read_2026_11_dispatch_pool_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_dispatch_pool_id_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (dispatch_pool_id);


--
-- Name: msg_dispatch_jobs_read_2026_11_event_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_event_id_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (event_id);


--
-- Name: msg_dispatch_jobs_read_2026_11_message_group_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_message_group_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (message_group);


--
-- Name: msg_dispatch_jobs_read_2026_11_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_status_created_at_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (status, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_11_subscription_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_11_subscription_id_idx ON public.msg_dispatch_jobs_read_2026_11 USING btree (subscription_id);


--
-- Name: msg_dispatch_jobs_read_2026_12_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_application_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (application);


--
-- Name: msg_dispatch_jobs_read_2026_12_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_client_id_created_at_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (client_id, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_12_code_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_code_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (code);


--
-- Name: msg_dispatch_jobs_read_2026_12_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_created_at_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (created_at);


--
-- Name: msg_dispatch_jobs_read_2026_12_dispatch_pool_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_dispatch_pool_id_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (dispatch_pool_id);


--
-- Name: msg_dispatch_jobs_read_2026_12_event_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_event_id_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (event_id);


--
-- Name: msg_dispatch_jobs_read_2026_12_message_group_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_message_group_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (message_group);


--
-- Name: msg_dispatch_jobs_read_2026_12_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_status_created_at_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (status, created_at DESC);


--
-- Name: msg_dispatch_jobs_read_2026_12_subscription_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_dispatch_jobs_read_2026_12_subscription_id_idx ON public.msg_dispatch_jobs_read_2026_12 USING btree (subscription_id);


--
-- Name: msg_events_2026_08_client_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_08_client_id_idx ON public.msg_events_2026_08 USING btree (client_id);


--
-- Name: msg_events_2026_08_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_08_created_at_idx ON public.msg_events_2026_08 USING btree (created_at);


--
-- Name: msg_events_2026_08_created_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_08_created_at_idx1 ON public.msg_events_2026_08 USING btree (created_at) WHERE (projected_at IS NULL);


--
-- Name: msg_events_2026_08_created_at_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_08_created_at_idx2 ON public.msg_events_2026_08 USING btree (created_at) WHERE (fanned_out_at IS NULL);


--
-- Name: msg_events_2026_08_deduplication_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_events_2026_08_deduplication_id_created_at_idx ON public.msg_events_2026_08 USING btree (deduplication_id, created_at);


--
-- Name: msg_events_2026_09_client_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_09_client_id_idx ON public.msg_events_2026_09 USING btree (client_id);


--
-- Name: msg_events_2026_09_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_09_created_at_idx ON public.msg_events_2026_09 USING btree (created_at);


--
-- Name: msg_events_2026_09_created_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_09_created_at_idx1 ON public.msg_events_2026_09 USING btree (created_at) WHERE (projected_at IS NULL);


--
-- Name: msg_events_2026_09_created_at_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_09_created_at_idx2 ON public.msg_events_2026_09 USING btree (created_at) WHERE (fanned_out_at IS NULL);


--
-- Name: msg_events_2026_09_deduplication_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_events_2026_09_deduplication_id_created_at_idx ON public.msg_events_2026_09 USING btree (deduplication_id, created_at);


--
-- Name: msg_events_2026_10_client_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_10_client_id_idx ON public.msg_events_2026_10 USING btree (client_id);


--
-- Name: msg_events_2026_10_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_10_created_at_idx ON public.msg_events_2026_10 USING btree (created_at);


--
-- Name: msg_events_2026_10_created_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_10_created_at_idx1 ON public.msg_events_2026_10 USING btree (created_at) WHERE (projected_at IS NULL);


--
-- Name: msg_events_2026_10_created_at_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_10_created_at_idx2 ON public.msg_events_2026_10 USING btree (created_at) WHERE (fanned_out_at IS NULL);


--
-- Name: msg_events_2026_10_deduplication_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_events_2026_10_deduplication_id_created_at_idx ON public.msg_events_2026_10 USING btree (deduplication_id, created_at);


--
-- Name: msg_events_2026_11_client_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_11_client_id_idx ON public.msg_events_2026_11 USING btree (client_id);


--
-- Name: msg_events_2026_11_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_11_created_at_idx ON public.msg_events_2026_11 USING btree (created_at);


--
-- Name: msg_events_2026_11_created_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_11_created_at_idx1 ON public.msg_events_2026_11 USING btree (created_at) WHERE (projected_at IS NULL);


--
-- Name: msg_events_2026_11_created_at_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_11_created_at_idx2 ON public.msg_events_2026_11 USING btree (created_at) WHERE (fanned_out_at IS NULL);


--
-- Name: msg_events_2026_11_deduplication_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_events_2026_11_deduplication_id_created_at_idx ON public.msg_events_2026_11 USING btree (deduplication_id, created_at);


--
-- Name: msg_events_2026_12_client_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_12_client_id_idx ON public.msg_events_2026_12 USING btree (client_id);


--
-- Name: msg_events_2026_12_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_12_created_at_idx ON public.msg_events_2026_12 USING btree (created_at);


--
-- Name: msg_events_2026_12_created_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_12_created_at_idx1 ON public.msg_events_2026_12 USING btree (created_at) WHERE (projected_at IS NULL);


--
-- Name: msg_events_2026_12_created_at_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_2026_12_created_at_idx2 ON public.msg_events_2026_12 USING btree (created_at) WHERE (fanned_out_at IS NULL);


--
-- Name: msg_events_2026_12_deduplication_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX msg_events_2026_12_deduplication_id_created_at_idx ON public.msg_events_2026_12 USING btree (deduplication_id, created_at);


--
-- Name: msg_events_read_2026_08_aggregate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_08_aggregate_idx ON public.msg_events_read_2026_08 USING btree (aggregate);


--
-- Name: msg_events_read_2026_08_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_08_application_idx ON public.msg_events_read_2026_08 USING btree (application);


--
-- Name: msg_events_read_2026_08_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_08_client_id_created_at_idx ON public.msg_events_read_2026_08 USING btree (client_id, created_at DESC);


--
-- Name: msg_events_read_2026_08_correlation_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_08_correlation_id_idx ON public.msg_events_read_2026_08 USING btree (correlation_id);


--
-- Name: msg_events_read_2026_08_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_08_created_at_idx ON public.msg_events_read_2026_08 USING btree (created_at);


--
-- Name: msg_events_read_2026_08_subdomain_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_08_subdomain_idx ON public.msg_events_read_2026_08 USING btree (subdomain);


--
-- Name: msg_events_read_2026_08_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_08_type_created_at_idx ON public.msg_events_read_2026_08 USING btree (type, created_at DESC);


--
-- Name: msg_events_read_2026_09_aggregate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_09_aggregate_idx ON public.msg_events_read_2026_09 USING btree (aggregate);


--
-- Name: msg_events_read_2026_09_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_09_application_idx ON public.msg_events_read_2026_09 USING btree (application);


--
-- Name: msg_events_read_2026_09_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_09_client_id_created_at_idx ON public.msg_events_read_2026_09 USING btree (client_id, created_at DESC);


--
-- Name: msg_events_read_2026_09_correlation_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_09_correlation_id_idx ON public.msg_events_read_2026_09 USING btree (correlation_id);


--
-- Name: msg_events_read_2026_09_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_09_created_at_idx ON public.msg_events_read_2026_09 USING btree (created_at);


--
-- Name: msg_events_read_2026_09_subdomain_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_09_subdomain_idx ON public.msg_events_read_2026_09 USING btree (subdomain);


--
-- Name: msg_events_read_2026_09_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_09_type_created_at_idx ON public.msg_events_read_2026_09 USING btree (type, created_at DESC);


--
-- Name: msg_events_read_2026_10_aggregate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_10_aggregate_idx ON public.msg_events_read_2026_10 USING btree (aggregate);


--
-- Name: msg_events_read_2026_10_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_10_application_idx ON public.msg_events_read_2026_10 USING btree (application);


--
-- Name: msg_events_read_2026_10_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_10_client_id_created_at_idx ON public.msg_events_read_2026_10 USING btree (client_id, created_at DESC);


--
-- Name: msg_events_read_2026_10_correlation_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_10_correlation_id_idx ON public.msg_events_read_2026_10 USING btree (correlation_id);


--
-- Name: msg_events_read_2026_10_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_10_created_at_idx ON public.msg_events_read_2026_10 USING btree (created_at);


--
-- Name: msg_events_read_2026_10_subdomain_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_10_subdomain_idx ON public.msg_events_read_2026_10 USING btree (subdomain);


--
-- Name: msg_events_read_2026_10_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_10_type_created_at_idx ON public.msg_events_read_2026_10 USING btree (type, created_at DESC);


--
-- Name: msg_events_read_2026_11_aggregate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_11_aggregate_idx ON public.msg_events_read_2026_11 USING btree (aggregate);


--
-- Name: msg_events_read_2026_11_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_11_application_idx ON public.msg_events_read_2026_11 USING btree (application);


--
-- Name: msg_events_read_2026_11_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_11_client_id_created_at_idx ON public.msg_events_read_2026_11 USING btree (client_id, created_at DESC);


--
-- Name: msg_events_read_2026_11_correlation_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_11_correlation_id_idx ON public.msg_events_read_2026_11 USING btree (correlation_id);


--
-- Name: msg_events_read_2026_11_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_11_created_at_idx ON public.msg_events_read_2026_11 USING btree (created_at);


--
-- Name: msg_events_read_2026_11_subdomain_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_11_subdomain_idx ON public.msg_events_read_2026_11 USING btree (subdomain);


--
-- Name: msg_events_read_2026_11_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_11_type_created_at_idx ON public.msg_events_read_2026_11 USING btree (type, created_at DESC);


--
-- Name: msg_events_read_2026_12_aggregate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_12_aggregate_idx ON public.msg_events_read_2026_12 USING btree (aggregate);


--
-- Name: msg_events_read_2026_12_application_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_12_application_idx ON public.msg_events_read_2026_12 USING btree (application);


--
-- Name: msg_events_read_2026_12_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_12_client_id_created_at_idx ON public.msg_events_read_2026_12 USING btree (client_id, created_at DESC);


--
-- Name: msg_events_read_2026_12_correlation_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_12_correlation_id_idx ON public.msg_events_read_2026_12 USING btree (correlation_id);


--
-- Name: msg_events_read_2026_12_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_12_created_at_idx ON public.msg_events_read_2026_12 USING btree (created_at);


--
-- Name: msg_events_read_2026_12_subdomain_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_12_subdomain_idx ON public.msg_events_read_2026_12 USING btree (subdomain);


--
-- Name: msg_events_read_2026_12_type_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_events_read_2026_12_type_created_at_idx ON public.msg_events_read_2026_12 USING btree (type, created_at DESC);


--
-- Name: msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx1 ON public.msg_scheduled_job_instance_logs_2026_09 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx2 ON public.msg_scheduled_job_instance_logs_2026_10 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx3 ON public.msg_scheduled_job_instance_logs_2026_11 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx4; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx4 ON public.msg_scheduled_job_instance_logs_2026_12 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instance_logs_2026_instance_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_logs_2026_instance_id_created_at_idx ON public.msg_scheduled_job_instance_logs_2026_08 USING btree (instance_id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_202_instance_id_created_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_logs_202_instance_id_created_at_idx1 ON public.msg_scheduled_job_instance_logs_2026_09 USING btree (instance_id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_202_instance_id_created_at_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_logs_202_instance_id_created_at_idx2 ON public.msg_scheduled_job_instance_logs_2026_10 USING btree (instance_id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_202_instance_id_created_at_idx3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_logs_202_instance_id_created_at_idx3 ON public.msg_scheduled_job_instance_logs_2026_11 USING btree (instance_id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_202_instance_id_created_at_idx4; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_logs_202_instance_id_created_at_idx4 ON public.msg_scheduled_job_instance_logs_2026_12 USING btree (instance_id, created_at);


--
-- Name: msg_scheduled_job_instance_logs_scheduled_job_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instance_logs_scheduled_job_id_created_at_idx ON public.msg_scheduled_job_instance_logs_2026_08 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_2026_08_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_08_client_id_created_at_idx ON public.msg_scheduled_job_instances_2026_08 USING btree (client_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_2026_08_scheduled_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_08_scheduled_job_id_idx ON public.msg_scheduled_job_instances_2026_08 USING btree (scheduled_job_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying])::text[]));


--
-- Name: msg_scheduled_job_instances_2026_08_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_08_status_created_at_idx ON public.msg_scheduled_job_instances_2026_08 USING btree (status, created_at);


--
-- Name: msg_scheduled_job_instances_2026_09_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_09_client_id_created_at_idx ON public.msg_scheduled_job_instances_2026_09 USING btree (client_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_2026_09_scheduled_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_09_scheduled_job_id_idx ON public.msg_scheduled_job_instances_2026_09 USING btree (scheduled_job_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying])::text[]));


--
-- Name: msg_scheduled_job_instances_2026_09_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_09_status_created_at_idx ON public.msg_scheduled_job_instances_2026_09 USING btree (status, created_at);


--
-- Name: msg_scheduled_job_instances_2026_10_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_10_client_id_created_at_idx ON public.msg_scheduled_job_instances_2026_10 USING btree (client_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_2026_10_scheduled_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_10_scheduled_job_id_idx ON public.msg_scheduled_job_instances_2026_10 USING btree (scheduled_job_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying])::text[]));


--
-- Name: msg_scheduled_job_instances_2026_10_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_10_status_created_at_idx ON public.msg_scheduled_job_instances_2026_10 USING btree (status, created_at);


--
-- Name: msg_scheduled_job_instances_2026_11_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_11_client_id_created_at_idx ON public.msg_scheduled_job_instances_2026_11 USING btree (client_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_2026_11_scheduled_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_11_scheduled_job_id_idx ON public.msg_scheduled_job_instances_2026_11 USING btree (scheduled_job_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying])::text[]));


--
-- Name: msg_scheduled_job_instances_2026_11_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_11_status_created_at_idx ON public.msg_scheduled_job_instances_2026_11 USING btree (status, created_at);


--
-- Name: msg_scheduled_job_instances_2026_12_client_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_12_client_id_created_at_idx ON public.msg_scheduled_job_instances_2026_12 USING btree (client_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_2026_12_scheduled_job_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_12_scheduled_job_id_idx ON public.msg_scheduled_job_instances_2026_12 USING btree (scheduled_job_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'IN_FLIGHT'::character varying, 'DELIVERED'::character varying])::text[]));


--
-- Name: msg_scheduled_job_instances_2026_12_status_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_2026_12_status_created_at_idx ON public.msg_scheduled_job_instances_2026_12 USING btree (status, created_at);


--
-- Name: msg_scheduled_job_instances_202_scheduled_job_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_202_scheduled_job_id_created_at_idx ON public.msg_scheduled_job_instances_2026_08 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx1 ON public.msg_scheduled_job_instances_2026_09 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx2 ON public.msg_scheduled_job_instances_2026_10 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx3 ON public.msg_scheduled_job_instances_2026_11 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx4; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx4 ON public.msg_scheduled_job_instances_2026_12 USING btree (scheduled_job_id, created_at DESC);


--
-- Name: oauth_clients_active_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX oauth_clients_active_idx ON public.oauth_clients USING btree (active);


--
-- Name: oauth_clients_client_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX oauth_clients_client_id_idx ON public.oauth_clients USING btree (client_id);


--
-- Name: oauth_oidc_payloads_expires_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX oauth_oidc_payloads_expires_at_idx ON public.oauth_oidc_payloads USING btree (expires_at);


--
-- Name: oauth_oidc_payloads_grant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX oauth_oidc_payloads_grant_id_idx ON public.oauth_oidc_payloads USING btree (grant_id);


--
-- Name: oauth_oidc_payloads_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX oauth_oidc_payloads_type_idx ON public.oauth_oidc_payloads USING btree (type);


--
-- Name: oauth_oidc_payloads_uid_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX oauth_oidc_payloads_uid_idx ON public.oauth_oidc_payloads USING btree (uid);


--
-- Name: oauth_oidc_payloads_user_code_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX oauth_oidc_payloads_user_code_idx ON public.oauth_oidc_payloads USING btree (user_code);


--
-- Name: tnt_anchor_domains_domain_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX tnt_anchor_domains_domain_idx ON public.tnt_anchor_domains USING btree (domain);


--
-- Name: tnt_client_auth_configs_config_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX tnt_client_auth_configs_config_type_idx ON public.tnt_client_auth_configs USING btree (config_type);


--
-- Name: tnt_client_auth_configs_email_domain_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX tnt_client_auth_configs_email_domain_idx ON public.tnt_client_auth_configs USING btree (email_domain);


--
-- Name: tnt_client_auth_configs_primary_client_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX tnt_client_auth_configs_primary_client_id_idx ON public.tnt_client_auth_configs USING btree (primary_client_id);


--
-- Name: tnt_cors_allowed_origins_origin_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX tnt_cors_allowed_origins_origin_idx ON public.tnt_cors_allowed_origins USING btree (origin);


--
-- Name: uq_app_client_configs_app_clt; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_app_client_configs_app_clt ON public.app_client_configs USING btree (application_id, client_id);


--
-- Name: uq_app_config_access_role; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_app_config_access_role ON public.app_platform_config_access USING btree (application_code, role_code);


--
-- Name: uq_app_platform_config_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_app_platform_config_key ON public.app_platform_configs USING btree (application_code, section, property, scope, client_id);


--
-- Name: uq_iam_client_access_grants_principal_client; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_iam_client_access_grants_principal_client ON public.iam_client_access_grants USING btree (principal_id, client_id);


--
-- Name: uq_msg_connections_app_client_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_msg_connections_app_client_code ON public.msg_connections USING btree (COALESCE(application_code, ''::character varying), COALESCE(client_id, ''::character varying), code);


--
-- Name: uq_msg_spec_versions_event_type_version; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_msg_spec_versions_event_type_version ON public.msg_event_type_spec_versions USING btree (event_type_id, version);


--
-- Name: uq_msg_subscriptions_app_client_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_msg_subscriptions_app_client_code ON public.msg_subscriptions USING btree (COALESCE(application_code, ''::character varying), COALESCE(client_id, ''::character varying), code);


--
-- Name: iam_login_attempts_2026_q3_attempt_type_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_type ATTACH PARTITION public.iam_login_attempts_2026_q3_attempt_type_idx;


--
-- Name: iam_login_attempts_2026_q3_attempted_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_at ATTACH PARTITION public.iam_login_attempts_2026_q3_attempted_at_idx;


--
-- Name: iam_login_attempts_2026_q3_identifier_attempted_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_identifier_at ATTACH PARTITION public.iam_login_attempts_2026_q3_identifier_attempted_at_idx;


--
-- Name: iam_login_attempts_2026_q3_identifier_attempted_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_failure_throttle ATTACH PARTITION public.iam_login_attempts_2026_q3_identifier_attempted_at_idx1;


--
-- Name: iam_login_attempts_2026_q3_identifier_ip_address_attempted__idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_identifier_ip_at ATTACH PARTITION public.iam_login_attempts_2026_q3_identifier_ip_address_attempted__idx;


--
-- Name: iam_login_attempts_2026_q3_outcome_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_outcome ATTACH PARTITION public.iam_login_attempts_2026_q3_outcome_idx;


--
-- Name: iam_login_attempts_2026_q3_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.iam_login_attempts_pkey ATTACH PARTITION public.iam_login_attempts_2026_q3_pkey;


--
-- Name: iam_login_attempts_2026_q3_principal_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_principal ATTACH PARTITION public.iam_login_attempts_2026_q3_principal_id_idx;


--
-- Name: iam_login_attempts_2026_q4_attempt_type_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_type ATTACH PARTITION public.iam_login_attempts_2026_q4_attempt_type_idx;


--
-- Name: iam_login_attempts_2026_q4_attempted_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_at ATTACH PARTITION public.iam_login_attempts_2026_q4_attempted_at_idx;


--
-- Name: iam_login_attempts_2026_q4_identifier_attempted_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_identifier_at ATTACH PARTITION public.iam_login_attempts_2026_q4_identifier_attempted_at_idx;


--
-- Name: iam_login_attempts_2026_q4_identifier_attempted_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_failure_throttle ATTACH PARTITION public.iam_login_attempts_2026_q4_identifier_attempted_at_idx1;


--
-- Name: iam_login_attempts_2026_q4_identifier_ip_address_attempted__idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_identifier_ip_at ATTACH PARTITION public.iam_login_attempts_2026_q4_identifier_ip_address_attempted__idx;


--
-- Name: iam_login_attempts_2026_q4_outcome_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_outcome ATTACH PARTITION public.iam_login_attempts_2026_q4_outcome_idx;


--
-- Name: iam_login_attempts_2026_q4_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.iam_login_attempts_pkey ATTACH PARTITION public.iam_login_attempts_2026_q4_pkey;


--
-- Name: iam_login_attempts_2026_q4_principal_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_principal ATTACH PARTITION public.iam_login_attempts_2026_q4_principal_id_idx;


--
-- Name: iam_login_attempts_default_attempt_type_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_type ATTACH PARTITION public.iam_login_attempts_default_attempt_type_idx;


--
-- Name: iam_login_attempts_default_attempted_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_at ATTACH PARTITION public.iam_login_attempts_default_attempted_at_idx;


--
-- Name: iam_login_attempts_default_identifier_attempted_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_identifier_at ATTACH PARTITION public.iam_login_attempts_default_identifier_attempted_at_idx;


--
-- Name: iam_login_attempts_default_identifier_attempted_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_failure_throttle ATTACH PARTITION public.iam_login_attempts_default_identifier_attempted_at_idx1;


--
-- Name: iam_login_attempts_default_identifier_ip_address_attempted__idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_identifier_ip_at ATTACH PARTITION public.iam_login_attempts_default_identifier_ip_address_attempted__idx;


--
-- Name: iam_login_attempts_default_outcome_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_outcome ATTACH PARTITION public.iam_login_attempts_default_outcome_idx;


--
-- Name: iam_login_attempts_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.iam_login_attempts_pkey ATTACH PARTITION public.iam_login_attempts_default_pkey;


--
-- Name: iam_login_attempts_default_principal_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_iam_login_attempts_principal ATTACH PARTITION public.iam_login_attempts_default_principal_id_idx;


--
-- Name: msg_dispatch_job_attempts_2026_08_dispatch_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job ATTACH PARTITION public.msg_dispatch_job_attempts_2026_08_dispatch_job_id_idx;


--
-- Name: msg_dispatch_job_attempts_2026_08_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_job_attempts_pkey ATTACH PARTITION public.msg_dispatch_job_attempts_2026_08_pkey;


--
-- Name: msg_dispatch_job_attempts_2026_09_dispatch_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job ATTACH PARTITION public.msg_dispatch_job_attempts_2026_09_dispatch_job_id_idx;


--
-- Name: msg_dispatch_job_attempts_2026_09_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_job_attempts_pkey ATTACH PARTITION public.msg_dispatch_job_attempts_2026_09_pkey;


--
-- Name: msg_dispatch_job_attempts_2026_10_dispatch_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job ATTACH PARTITION public.msg_dispatch_job_attempts_2026_10_dispatch_job_id_idx;


--
-- Name: msg_dispatch_job_attempts_2026_10_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_job_attempts_pkey ATTACH PARTITION public.msg_dispatch_job_attempts_2026_10_pkey;


--
-- Name: msg_dispatch_job_attempts_2026_11_dispatch_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job ATTACH PARTITION public.msg_dispatch_job_attempts_2026_11_dispatch_job_id_idx;


--
-- Name: msg_dispatch_job_attempts_2026_11_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_job_attempts_pkey ATTACH PARTITION public.msg_dispatch_job_attempts_2026_11_pkey;


--
-- Name: msg_dispatch_job_attempts_2026_12_dispatch_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job ATTACH PARTITION public.msg_dispatch_job_attempts_2026_12_dispatch_job_id_idx;


--
-- Name: msg_dispatch_job_attempts_2026_12_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_job_attempts_pkey ATTACH PARTITION public.msg_dispatch_job_attempts_2026_12_pkey;


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job_number ATTACH PARTITION public.msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx1;


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job_number ATTACH PARTITION public.msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx2;


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job_number ATTACH PARTITION public.msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx3;


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx4; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job_number ATTACH PARTITION public.msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numb_idx4;


--
-- Name: msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numbe_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_job_attempts_job_number ATTACH PARTITION public.msg_dispatch_job_attempts_202_dispatch_job_id_attempt_numbe_idx;


--
-- Name: msg_dispatch_jobs_2026_08_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_dirty ATTACH PARTITION public.msg_dispatch_jobs_2026_08_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_08_message_group_sequence_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_pending_poll ATTACH PARTITION public.msg_dispatch_jobs_2026_08_message_group_sequence_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_08_message_group_status_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_blocked_groups ATTACH PARTITION public.msg_dispatch_jobs_2026_08_message_group_status_idx;


--
-- Name: msg_dispatch_jobs_2026_08_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_pkey ATTACH PARTITION public.msg_dispatch_jobs_2026_08_pkey;


--
-- Name: msg_dispatch_jobs_2026_08_queued_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_stale_queued ATTACH PARTITION public.msg_dispatch_jobs_2026_08_queued_at_idx;


--
-- Name: msg_dispatch_jobs_2026_09_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_dirty ATTACH PARTITION public.msg_dispatch_jobs_2026_09_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_09_message_group_sequence_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_pending_poll ATTACH PARTITION public.msg_dispatch_jobs_2026_09_message_group_sequence_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_09_message_group_status_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_blocked_groups ATTACH PARTITION public.msg_dispatch_jobs_2026_09_message_group_status_idx;


--
-- Name: msg_dispatch_jobs_2026_09_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_pkey ATTACH PARTITION public.msg_dispatch_jobs_2026_09_pkey;


--
-- Name: msg_dispatch_jobs_2026_09_queued_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_stale_queued ATTACH PARTITION public.msg_dispatch_jobs_2026_09_queued_at_idx;


--
-- Name: msg_dispatch_jobs_2026_10_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_dirty ATTACH PARTITION public.msg_dispatch_jobs_2026_10_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_10_message_group_sequence_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_pending_poll ATTACH PARTITION public.msg_dispatch_jobs_2026_10_message_group_sequence_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_10_message_group_status_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_blocked_groups ATTACH PARTITION public.msg_dispatch_jobs_2026_10_message_group_status_idx;


--
-- Name: msg_dispatch_jobs_2026_10_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_pkey ATTACH PARTITION public.msg_dispatch_jobs_2026_10_pkey;


--
-- Name: msg_dispatch_jobs_2026_10_queued_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_stale_queued ATTACH PARTITION public.msg_dispatch_jobs_2026_10_queued_at_idx;


--
-- Name: msg_dispatch_jobs_2026_11_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_dirty ATTACH PARTITION public.msg_dispatch_jobs_2026_11_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_11_message_group_sequence_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_pending_poll ATTACH PARTITION public.msg_dispatch_jobs_2026_11_message_group_sequence_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_11_message_group_status_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_blocked_groups ATTACH PARTITION public.msg_dispatch_jobs_2026_11_message_group_status_idx;


--
-- Name: msg_dispatch_jobs_2026_11_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_pkey ATTACH PARTITION public.msg_dispatch_jobs_2026_11_pkey;


--
-- Name: msg_dispatch_jobs_2026_11_queued_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_stale_queued ATTACH PARTITION public.msg_dispatch_jobs_2026_11_queued_at_idx;


--
-- Name: msg_dispatch_jobs_2026_12_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_dirty ATTACH PARTITION public.msg_dispatch_jobs_2026_12_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_12_message_group_sequence_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_pending_poll ATTACH PARTITION public.msg_dispatch_jobs_2026_12_message_group_sequence_created_at_idx;


--
-- Name: msg_dispatch_jobs_2026_12_message_group_status_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_blocked_groups ATTACH PARTITION public.msg_dispatch_jobs_2026_12_message_group_status_idx;


--
-- Name: msg_dispatch_jobs_2026_12_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_pkey ATTACH PARTITION public.msg_dispatch_jobs_2026_12_pkey;


--
-- Name: msg_dispatch_jobs_2026_12_queued_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_dispatch_jobs_stale_queued ATTACH PARTITION public.msg_dispatch_jobs_2026_12_queued_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_application ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_application_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_client_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_client_id_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_code_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_code ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_code_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_created_at ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_dispatch_pool_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_dispatch_pool_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_dispatch_pool_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_event_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_event_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_event_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_message_group_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_message_group ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_message_group_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_read_pkey ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_pkey;


--
-- Name: msg_dispatch_jobs_read_2026_08_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_status_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_status_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_08_subscription_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_subscription_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_08_subscription_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_application ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_application_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_client_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_client_id_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_code_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_code ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_code_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_created_at ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_dispatch_pool_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_dispatch_pool_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_dispatch_pool_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_event_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_event_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_event_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_message_group_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_message_group ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_message_group_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_read_pkey ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_pkey;


--
-- Name: msg_dispatch_jobs_read_2026_09_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_status_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_status_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_09_subscription_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_subscription_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_09_subscription_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_application ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_application_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_client_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_client_id_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_code_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_code ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_code_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_created_at ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_dispatch_pool_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_dispatch_pool_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_dispatch_pool_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_event_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_event_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_event_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_message_group_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_message_group ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_message_group_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_read_pkey ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_pkey;


--
-- Name: msg_dispatch_jobs_read_2026_10_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_status_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_status_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_10_subscription_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_subscription_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_10_subscription_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_application ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_application_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_client_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_client_id_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_code_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_code ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_code_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_created_at ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_dispatch_pool_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_dispatch_pool_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_dispatch_pool_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_event_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_event_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_event_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_message_group_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_message_group ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_message_group_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_read_pkey ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_pkey;


--
-- Name: msg_dispatch_jobs_read_2026_11_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_status_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_status_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_11_subscription_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_subscription_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_11_subscription_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_application ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_application_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_client_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_client_id_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_code_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_code ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_code_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_created_at ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_dispatch_pool_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_dispatch_pool_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_dispatch_pool_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_event_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_event_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_event_id_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_message_group_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_message_group ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_message_group_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_dispatch_jobs_read_pkey ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_pkey;


--
-- Name: msg_dispatch_jobs_read_2026_12_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_status_created ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_status_created_at_idx;


--
-- Name: msg_dispatch_jobs_read_2026_12_subscription_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_dispatch_jobs_read_subscription_id ATTACH PARTITION public.msg_dispatch_jobs_read_2026_12_subscription_id_idx;


--
-- Name: msg_events_2026_08_client_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_client_id ATTACH PARTITION public.msg_events_2026_08_client_id_idx;


--
-- Name: msg_events_2026_08_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_created_at ATTACH PARTITION public.msg_events_2026_08_created_at_idx;


--
-- Name: msg_events_2026_08_created_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unprojected ATTACH PARTITION public.msg_events_2026_08_created_at_idx1;


--
-- Name: msg_events_2026_08_created_at_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unfanned ATTACH PARTITION public.msg_events_2026_08_created_at_idx2;


--
-- Name: msg_events_2026_08_deduplication_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_deduplication ATTACH PARTITION public.msg_events_2026_08_deduplication_id_created_at_idx;


--
-- Name: msg_events_2026_08_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_pkey ATTACH PARTITION public.msg_events_2026_08_pkey;


--
-- Name: msg_events_2026_09_client_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_client_id ATTACH PARTITION public.msg_events_2026_09_client_id_idx;


--
-- Name: msg_events_2026_09_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_created_at ATTACH PARTITION public.msg_events_2026_09_created_at_idx;


--
-- Name: msg_events_2026_09_created_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unprojected ATTACH PARTITION public.msg_events_2026_09_created_at_idx1;


--
-- Name: msg_events_2026_09_created_at_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unfanned ATTACH PARTITION public.msg_events_2026_09_created_at_idx2;


--
-- Name: msg_events_2026_09_deduplication_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_deduplication ATTACH PARTITION public.msg_events_2026_09_deduplication_id_created_at_idx;


--
-- Name: msg_events_2026_09_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_pkey ATTACH PARTITION public.msg_events_2026_09_pkey;


--
-- Name: msg_events_2026_10_client_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_client_id ATTACH PARTITION public.msg_events_2026_10_client_id_idx;


--
-- Name: msg_events_2026_10_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_created_at ATTACH PARTITION public.msg_events_2026_10_created_at_idx;


--
-- Name: msg_events_2026_10_created_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unprojected ATTACH PARTITION public.msg_events_2026_10_created_at_idx1;


--
-- Name: msg_events_2026_10_created_at_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unfanned ATTACH PARTITION public.msg_events_2026_10_created_at_idx2;


--
-- Name: msg_events_2026_10_deduplication_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_deduplication ATTACH PARTITION public.msg_events_2026_10_deduplication_id_created_at_idx;


--
-- Name: msg_events_2026_10_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_pkey ATTACH PARTITION public.msg_events_2026_10_pkey;


--
-- Name: msg_events_2026_11_client_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_client_id ATTACH PARTITION public.msg_events_2026_11_client_id_idx;


--
-- Name: msg_events_2026_11_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_created_at ATTACH PARTITION public.msg_events_2026_11_created_at_idx;


--
-- Name: msg_events_2026_11_created_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unprojected ATTACH PARTITION public.msg_events_2026_11_created_at_idx1;


--
-- Name: msg_events_2026_11_created_at_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unfanned ATTACH PARTITION public.msg_events_2026_11_created_at_idx2;


--
-- Name: msg_events_2026_11_deduplication_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_deduplication ATTACH PARTITION public.msg_events_2026_11_deduplication_id_created_at_idx;


--
-- Name: msg_events_2026_11_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_pkey ATTACH PARTITION public.msg_events_2026_11_pkey;


--
-- Name: msg_events_2026_12_client_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_client_id ATTACH PARTITION public.msg_events_2026_12_client_id_idx;


--
-- Name: msg_events_2026_12_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_created_at ATTACH PARTITION public.msg_events_2026_12_created_at_idx;


--
-- Name: msg_events_2026_12_created_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unprojected ATTACH PARTITION public.msg_events_2026_12_created_at_idx1;


--
-- Name: msg_events_2026_12_created_at_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_unfanned ATTACH PARTITION public.msg_events_2026_12_created_at_idx2;


--
-- Name: msg_events_2026_12_deduplication_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_deduplication ATTACH PARTITION public.msg_events_2026_12_deduplication_id_created_at_idx;


--
-- Name: msg_events_2026_12_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_pkey ATTACH PARTITION public.msg_events_2026_12_pkey;


--
-- Name: msg_events_read_2026_08_aggregate_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_aggregate ATTACH PARTITION public.msg_events_read_2026_08_aggregate_idx;


--
-- Name: msg_events_read_2026_08_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_application ATTACH PARTITION public.msg_events_read_2026_08_application_idx;


--
-- Name: msg_events_read_2026_08_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_client_created ATTACH PARTITION public.msg_events_read_2026_08_client_id_created_at_idx;


--
-- Name: msg_events_read_2026_08_correlation_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_correlation_id ATTACH PARTITION public.msg_events_read_2026_08_correlation_id_idx;


--
-- Name: msg_events_read_2026_08_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_created_at ATTACH PARTITION public.msg_events_read_2026_08_created_at_idx;


--
-- Name: msg_events_read_2026_08_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_read_pkey ATTACH PARTITION public.msg_events_read_2026_08_pkey;


--
-- Name: msg_events_read_2026_08_subdomain_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_subdomain ATTACH PARTITION public.msg_events_read_2026_08_subdomain_idx;


--
-- Name: msg_events_read_2026_08_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_type_created ATTACH PARTITION public.msg_events_read_2026_08_type_created_at_idx;


--
-- Name: msg_events_read_2026_09_aggregate_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_aggregate ATTACH PARTITION public.msg_events_read_2026_09_aggregate_idx;


--
-- Name: msg_events_read_2026_09_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_application ATTACH PARTITION public.msg_events_read_2026_09_application_idx;


--
-- Name: msg_events_read_2026_09_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_client_created ATTACH PARTITION public.msg_events_read_2026_09_client_id_created_at_idx;


--
-- Name: msg_events_read_2026_09_correlation_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_correlation_id ATTACH PARTITION public.msg_events_read_2026_09_correlation_id_idx;


--
-- Name: msg_events_read_2026_09_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_created_at ATTACH PARTITION public.msg_events_read_2026_09_created_at_idx;


--
-- Name: msg_events_read_2026_09_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_read_pkey ATTACH PARTITION public.msg_events_read_2026_09_pkey;


--
-- Name: msg_events_read_2026_09_subdomain_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_subdomain ATTACH PARTITION public.msg_events_read_2026_09_subdomain_idx;


--
-- Name: msg_events_read_2026_09_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_type_created ATTACH PARTITION public.msg_events_read_2026_09_type_created_at_idx;


--
-- Name: msg_events_read_2026_10_aggregate_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_aggregate ATTACH PARTITION public.msg_events_read_2026_10_aggregate_idx;


--
-- Name: msg_events_read_2026_10_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_application ATTACH PARTITION public.msg_events_read_2026_10_application_idx;


--
-- Name: msg_events_read_2026_10_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_client_created ATTACH PARTITION public.msg_events_read_2026_10_client_id_created_at_idx;


--
-- Name: msg_events_read_2026_10_correlation_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_correlation_id ATTACH PARTITION public.msg_events_read_2026_10_correlation_id_idx;


--
-- Name: msg_events_read_2026_10_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_created_at ATTACH PARTITION public.msg_events_read_2026_10_created_at_idx;


--
-- Name: msg_events_read_2026_10_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_read_pkey ATTACH PARTITION public.msg_events_read_2026_10_pkey;


--
-- Name: msg_events_read_2026_10_subdomain_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_subdomain ATTACH PARTITION public.msg_events_read_2026_10_subdomain_idx;


--
-- Name: msg_events_read_2026_10_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_type_created ATTACH PARTITION public.msg_events_read_2026_10_type_created_at_idx;


--
-- Name: msg_events_read_2026_11_aggregate_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_aggregate ATTACH PARTITION public.msg_events_read_2026_11_aggregate_idx;


--
-- Name: msg_events_read_2026_11_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_application ATTACH PARTITION public.msg_events_read_2026_11_application_idx;


--
-- Name: msg_events_read_2026_11_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_client_created ATTACH PARTITION public.msg_events_read_2026_11_client_id_created_at_idx;


--
-- Name: msg_events_read_2026_11_correlation_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_correlation_id ATTACH PARTITION public.msg_events_read_2026_11_correlation_id_idx;


--
-- Name: msg_events_read_2026_11_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_created_at ATTACH PARTITION public.msg_events_read_2026_11_created_at_idx;


--
-- Name: msg_events_read_2026_11_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_read_pkey ATTACH PARTITION public.msg_events_read_2026_11_pkey;


--
-- Name: msg_events_read_2026_11_subdomain_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_subdomain ATTACH PARTITION public.msg_events_read_2026_11_subdomain_idx;


--
-- Name: msg_events_read_2026_11_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_type_created ATTACH PARTITION public.msg_events_read_2026_11_type_created_at_idx;


--
-- Name: msg_events_read_2026_12_aggregate_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_aggregate ATTACH PARTITION public.msg_events_read_2026_12_aggregate_idx;


--
-- Name: msg_events_read_2026_12_application_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_application ATTACH PARTITION public.msg_events_read_2026_12_application_idx;


--
-- Name: msg_events_read_2026_12_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_client_created ATTACH PARTITION public.msg_events_read_2026_12_client_id_created_at_idx;


--
-- Name: msg_events_read_2026_12_correlation_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_correlation_id ATTACH PARTITION public.msg_events_read_2026_12_correlation_id_idx;


--
-- Name: msg_events_read_2026_12_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_created_at ATTACH PARTITION public.msg_events_read_2026_12_created_at_idx;


--
-- Name: msg_events_read_2026_12_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_events_read_pkey ATTACH PARTITION public.msg_events_read_2026_12_pkey;


--
-- Name: msg_events_read_2026_12_subdomain_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_subdomain ATTACH PARTITION public.msg_events_read_2026_12_subdomain_idx;


--
-- Name: msg_events_read_2026_12_type_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_events_read_type_created ATTACH PARTITION public.msg_events_read_2026_12_type_created_at_idx;


--
-- Name: msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_job ATTACH PARTITION public.msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx1;


--
-- Name: msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_job ATTACH PARTITION public.msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx2;


--
-- Name: msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_job ATTACH PARTITION public.msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx3;


--
-- Name: msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx4; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_job ATTACH PARTITION public.msg_scheduled_job_instance_log_scheduled_job_id_created_at_idx4;


--
-- Name: msg_scheduled_job_instance_logs_2026_08_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instance_logs_pkey ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_08_pkey;


--
-- Name: msg_scheduled_job_instance_logs_2026_09_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instance_logs_pkey ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_09_pkey;


--
-- Name: msg_scheduled_job_instance_logs_2026_10_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instance_logs_pkey ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_10_pkey;


--
-- Name: msg_scheduled_job_instance_logs_2026_11_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instance_logs_pkey ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_11_pkey;


--
-- Name: msg_scheduled_job_instance_logs_2026_12_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instance_logs_pkey ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_12_pkey;


--
-- Name: msg_scheduled_job_instance_logs_2026_instance_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_instance ATTACH PARTITION public.msg_scheduled_job_instance_logs_2026_instance_id_created_at_idx;


--
-- Name: msg_scheduled_job_instance_logs_202_instance_id_created_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_instance ATTACH PARTITION public.msg_scheduled_job_instance_logs_202_instance_id_created_at_idx1;


--
-- Name: msg_scheduled_job_instance_logs_202_instance_id_created_at_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_instance ATTACH PARTITION public.msg_scheduled_job_instance_logs_202_instance_id_created_at_idx2;


--
-- Name: msg_scheduled_job_instance_logs_202_instance_id_created_at_idx3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_instance ATTACH PARTITION public.msg_scheduled_job_instance_logs_202_instance_id_created_at_idx3;


--
-- Name: msg_scheduled_job_instance_logs_202_instance_id_created_at_idx4; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_instance ATTACH PARTITION public.msg_scheduled_job_instance_logs_202_instance_id_created_at_idx4;


--
-- Name: msg_scheduled_job_instance_logs_scheduled_job_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instance_logs_job ATTACH PARTITION public.msg_scheduled_job_instance_logs_scheduled_job_id_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_08_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_client ATTACH PARTITION public.msg_scheduled_job_instances_2026_08_client_id_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_08_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instances_pkey ATTACH PARTITION public.msg_scheduled_job_instances_2026_08_pkey;


--
-- Name: msg_scheduled_job_instances_2026_08_scheduled_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_active ATTACH PARTITION public.msg_scheduled_job_instances_2026_08_scheduled_job_id_idx;


--
-- Name: msg_scheduled_job_instances_2026_08_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_status ATTACH PARTITION public.msg_scheduled_job_instances_2026_08_status_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_09_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_client ATTACH PARTITION public.msg_scheduled_job_instances_2026_09_client_id_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_09_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instances_pkey ATTACH PARTITION public.msg_scheduled_job_instances_2026_09_pkey;


--
-- Name: msg_scheduled_job_instances_2026_09_scheduled_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_active ATTACH PARTITION public.msg_scheduled_job_instances_2026_09_scheduled_job_id_idx;


--
-- Name: msg_scheduled_job_instances_2026_09_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_status ATTACH PARTITION public.msg_scheduled_job_instances_2026_09_status_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_10_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_client ATTACH PARTITION public.msg_scheduled_job_instances_2026_10_client_id_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_10_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instances_pkey ATTACH PARTITION public.msg_scheduled_job_instances_2026_10_pkey;


--
-- Name: msg_scheduled_job_instances_2026_10_scheduled_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_active ATTACH PARTITION public.msg_scheduled_job_instances_2026_10_scheduled_job_id_idx;


--
-- Name: msg_scheduled_job_instances_2026_10_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_status ATTACH PARTITION public.msg_scheduled_job_instances_2026_10_status_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_11_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_client ATTACH PARTITION public.msg_scheduled_job_instances_2026_11_client_id_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_11_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instances_pkey ATTACH PARTITION public.msg_scheduled_job_instances_2026_11_pkey;


--
-- Name: msg_scheduled_job_instances_2026_11_scheduled_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_active ATTACH PARTITION public.msg_scheduled_job_instances_2026_11_scheduled_job_id_idx;


--
-- Name: msg_scheduled_job_instances_2026_11_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_status ATTACH PARTITION public.msg_scheduled_job_instances_2026_11_status_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_12_client_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_client ATTACH PARTITION public.msg_scheduled_job_instances_2026_12_client_id_created_at_idx;


--
-- Name: msg_scheduled_job_instances_2026_12_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.msg_scheduled_job_instances_pkey ATTACH PARTITION public.msg_scheduled_job_instances_2026_12_pkey;


--
-- Name: msg_scheduled_job_instances_2026_12_scheduled_job_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_active ATTACH PARTITION public.msg_scheduled_job_instances_2026_12_scheduled_job_id_idx;


--
-- Name: msg_scheduled_job_instances_2026_12_status_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_status ATTACH PARTITION public.msg_scheduled_job_instances_2026_12_status_created_at_idx;


--
-- Name: msg_scheduled_job_instances_202_scheduled_job_id_created_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_job ATTACH PARTITION public.msg_scheduled_job_instances_202_scheduled_job_id_created_at_idx;


--
-- Name: msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_job ATTACH PARTITION public.msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx1;


--
-- Name: msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_job ATTACH PARTITION public.msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx2;


--
-- Name: msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_job ATTACH PARTITION public.msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx3;


--
-- Name: msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx4; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_msg_scheduled_job_instances_job ATTACH PARTITION public.msg_scheduled_job_instances_20_scheduled_job_id_created_at_idx4;


--
-- Name: app_application_openapi_specs app_application_openapi_specs_application_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_application_openapi_specs
    ADD CONSTRAINT app_application_openapi_specs_application_id_fkey FOREIGN KEY (application_id) REFERENCES public.app_applications(id) ON DELETE CASCADE;


--
-- Name: app_applications app_applications_service_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_applications
    ADD CONSTRAINT app_applications_service_account_fk FOREIGN KEY (service_account_id) REFERENCES public.iam_principals(id) ON DELETE SET NULL;


--
-- Name: oauth_client_post_logout_redirect_uris fk_oauth_client_post_logout_redirect_uris_client; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_post_logout_redirect_uris
    ADD CONSTRAINT fk_oauth_client_post_logout_redirect_uris_client FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;


--
-- Name: iam_mfa_email_pins iam_mfa_email_pins_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_mfa_email_pins
    ADD CONSTRAINT iam_mfa_email_pins_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- Name: iam_mfa_trusted_devices iam_mfa_trusted_devices_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_mfa_trusted_devices
    ADD CONSTRAINT iam_mfa_trusted_devices_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- Name: iam_principal_roles iam_principal_roles_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_principal_roles
    ADD CONSTRAINT iam_principal_roles_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- Name: iam_reset_approval_requests iam_reset_approval_requests_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_reset_approval_requests
    ADD CONSTRAINT iam_reset_approval_requests_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- Name: iam_role_permissions iam_role_permissions_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_role_permissions
    ADD CONSTRAINT iam_role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.iam_roles(id) ON DELETE CASCADE;


--
-- Name: iam_user_mfa_methods iam_user_mfa_methods_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_user_mfa_methods
    ADD CONSTRAINT iam_user_mfa_methods_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- Name: iam_user_mfa_recovery_codes iam_user_mfa_recovery_codes_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.iam_user_mfa_recovery_codes
    ADD CONSTRAINT iam_user_mfa_recovery_codes_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- Name: oauth_client_allowed_origins oauth_client_allowed_origins_oauth_client_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_allowed_origins
    ADD CONSTRAINT oauth_client_allowed_origins_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;


--
-- Name: oauth_client_application_ids oauth_client_application_ids_oauth_client_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_application_ids
    ADD CONSTRAINT oauth_client_application_ids_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;


--
-- Name: oauth_client_grant_types oauth_client_grant_types_oauth_client_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_grant_types
    ADD CONSTRAINT oauth_client_grant_types_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;


--
-- Name: oauth_client_redirect_uris oauth_client_redirect_uris_oauth_client_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_client_redirect_uris
    ADD CONSTRAINT oauth_client_redirect_uris_oauth_client_id_fkey FOREIGN KEY (oauth_client_id) REFERENCES public.oauth_clients(id) ON DELETE CASCADE;


--
-- Name: oauth_clients oauth_clients_service_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth_clients
    ADD CONSTRAINT oauth_clients_service_account_fk FOREIGN KEY (service_account_principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- Name: portal_identity_apps portal_identity_apps_identity_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portal_identity_apps
    ADD CONSTRAINT portal_identity_apps_identity_id_fkey FOREIGN KEY (identity_id) REFERENCES public.portal_identities(id) ON DELETE CASCADE;


--
-- Name: portal_identity_apps portal_identity_apps_portal_app_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portal_identity_apps
    ADD CONSTRAINT portal_identity_apps_portal_app_id_fkey FOREIGN KEY (portal_app_id) REFERENCES public.portal_apps(id) ON DELETE CASCADE;


--
-- Name: webauthn_credentials webauthn_credentials_principal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webauthn_credentials
    ADD CONSTRAINT webauthn_credentials_principal_id_fkey FOREIGN KEY (principal_id) REFERENCES public.iam_principals(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict eOHt7xtG85gR3etO6NCuM6pZeybwjREWWazHWdd63iCFKUZTfVkztZvd5f02WtR

