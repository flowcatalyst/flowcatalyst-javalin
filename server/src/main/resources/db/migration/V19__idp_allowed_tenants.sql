-- Owner ruling 2026-09-25 (docs/backlog.md "Overnight review" item 3): a multi-tenant
-- OIDC identity provider must pin the tenants it accepts, either per email-domain
-- mapping (required_oidc_tenant_id) or here, for the whole provider (which also
-- covers provider-direct logins). Java-only: Go never reads it, so a rollback to Go
-- simply stops enforcing the provider-level list.
CREATE TABLE IF NOT EXISTS public.oauth_identity_provider_allowed_tenants (
    identity_provider_id character varying(17) NOT NULL
        REFERENCES public.oauth_identity_providers (id) ON DELETE CASCADE,
    tenant_id character varying(100) NOT NULL,
    CONSTRAINT oauth_identity_provider_allowed_tenants_pkey PRIMARY KEY (identity_provider_id, tenant_id)
);
