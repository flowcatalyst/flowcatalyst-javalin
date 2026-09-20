package io.flowcatalyst.platform.seed;

import java.util.List;

/// Permission identifiers — the Java reading of
/// `internal/platform/seed/permissions.go`. Keep the string values
/// byte-identical across releases: existing rows in `iam_role_permissions`
/// reference these strings, and the SDK pins its permission checks against
/// them.
///
/// Naming follows the permission contexts
/// (admin / iam / auth / applicationService / developer).
public final class Permissions {

    private Permissions() {
    }

    // ── Admin context — clients, applications, config ───────────────────

    // Client
    public static final String ADMIN_CLIENT_READ = "platform:admin:client:view";
    public static final String ADMIN_CLIENT_CREATE = "platform:admin:client:create";
    public static final String ADMIN_CLIENT_UPDATE = "platform:admin:client:update";
    public static final String ADMIN_CLIENT_DELETE = "platform:admin:client:delete";
    public static final String ADMIN_CLIENT_MANAGE = "platform:admin:client:manage";
    public static final String ADMIN_CLIENT_ACTIVATE = "platform:admin:client:activate";
    public static final String ADMIN_CLIENT_SUSPEND = "platform:admin:client:suspend";
    public static final String ADMIN_CLIENT_DEACTIVATE = "platform:admin:client:deactivate";

    // Anchor domain
    public static final String ADMIN_ANCHOR_DOMAIN_READ = "platform:admin:anchor-domain:view";
    public static final String ADMIN_ANCHOR_DOMAIN_CREATE = "platform:admin:anchor-domain:create";
    public static final String ADMIN_ANCHOR_DOMAIN_UPDATE = "platform:admin:anchor-domain:update";
    public static final String ADMIN_ANCHOR_DOMAIN_DELETE = "platform:admin:anchor-domain:delete";
    public static final String ADMIN_ANCHOR_DOMAIN_MANAGE = "platform:admin:anchor-domain:manage";

    // Application
    public static final String ADMIN_APPLICATION_READ = "platform:admin:application:view";
    public static final String ADMIN_APPLICATION_CREATE = "platform:admin:application:create";
    public static final String ADMIN_APPLICATION_UPDATE = "platform:admin:application:update";
    public static final String ADMIN_APPLICATION_DELETE = "platform:admin:application:delete";
    public static final String ADMIN_APPLICATION_MANAGE = "platform:admin:application:manage";
    public static final String ADMIN_APPLICATION_ACTIVATE = "platform:admin:application:activate";
    public static final String ADMIN_APPLICATION_DEACTIVATE = "platform:admin:application:deactivate";
    public static final String ADMIN_APPLICATION_ENABLE_CLIENT = "platform:admin:application:enable-client";
    public static final String ADMIN_APPLICATION_DISABLE_CLIENT = "platform:admin:application:disable-client";

    // Event type (stored under messaging context)
    public static final String ADMIN_EVENT_TYPE_READ = "platform:messaging:event-type:view";
    public static final String ADMIN_EVENT_TYPE_CREATE = "platform:messaging:event-type:create";
    public static final String ADMIN_EVENT_TYPE_UPDATE = "platform:messaging:event-type:update";
    public static final String ADMIN_EVENT_TYPE_DELETE = "platform:messaging:event-type:delete";
    public static final String ADMIN_EVENT_TYPE_MANAGE = "platform:messaging:event-type:manage";
    public static final String ADMIN_EVENT_TYPE_ARCHIVE = "platform:messaging:event-type:archive";
    public static final String ADMIN_EVENT_TYPE_MANAGE_SCHEMA = "platform:messaging:event-type:manage-schema";
    public static final String ADMIN_EVENT_TYPE_SYNC = "platform:messaging:event-type:sync";

    // Process
    public static final String ADMIN_PROCESS_READ = "platform:messaging:process:view";
    public static final String ADMIN_PROCESS_CREATE = "platform:messaging:process:create";
    public static final String ADMIN_PROCESS_UPDATE = "platform:messaging:process:update";
    public static final String ADMIN_PROCESS_DELETE = "platform:messaging:process:delete";
    public static final String ADMIN_PROCESS_MANAGE = "platform:messaging:process:manage";
    public static final String ADMIN_PROCESS_ARCHIVE = "platform:messaging:process:archive";
    public static final String ADMIN_PROCESS_SYNC = "platform:messaging:process:sync";

    // Dispatch pool
    public static final String ADMIN_DISPATCH_POOL_READ = "platform:messaging:dispatch-pool:view";
    public static final String ADMIN_DISPATCH_POOL_CREATE = "platform:messaging:dispatch-pool:create";
    public static final String ADMIN_DISPATCH_POOL_UPDATE = "platform:messaging:dispatch-pool:update";
    public static final String ADMIN_DISPATCH_POOL_DELETE = "platform:messaging:dispatch-pool:delete";
    public static final String ADMIN_DISPATCH_POOL_MANAGE = "platform:messaging:dispatch-pool:manage";
    public static final String ADMIN_DISPATCH_POOL_SYNC = "platform:messaging:dispatch-pool:sync";

    // Connection
    public static final String ADMIN_CONNECTION_READ = "platform:messaging:connection:view";
    public static final String ADMIN_CONNECTION_CREATE = "platform:messaging:connection:create";
    public static final String ADMIN_CONNECTION_UPDATE = "platform:messaging:connection:update";
    public static final String ADMIN_CONNECTION_DELETE = "platform:messaging:connection:delete";
    public static final String ADMIN_CONNECTION_MANAGE = "platform:messaging:connection:manage";

    // Subscription
    public static final String ADMIN_SUBSCRIPTION_READ = "platform:messaging:subscription:view";
    public static final String ADMIN_SUBSCRIPTION_CREATE = "platform:messaging:subscription:create";
    public static final String ADMIN_SUBSCRIPTION_UPDATE = "platform:messaging:subscription:update";
    public static final String ADMIN_SUBSCRIPTION_DELETE = "platform:messaging:subscription:delete";
    public static final String ADMIN_SUBSCRIPTION_MANAGE = "platform:messaging:subscription:manage";
    public static final String ADMIN_SUBSCRIPTION_SYNC = "platform:messaging:subscription:sync";

    // Event
    public static final String ADMIN_EVENT_READ = "platform:messaging:event:view";
    public static final String ADMIN_EVENT_VIEW_RAW = "platform:messaging:event:view-raw";

    // Dispatch job
    public static final String ADMIN_DISPATCH_JOB_READ = "platform:messaging:dispatch-job:view";
    public static final String ADMIN_DISPATCH_JOB_VIEW_RAW = "platform:messaging:dispatch-job:view-raw";

    // Scheduled job
    public static final String ADMIN_SCHEDULED_JOB_READ = "platform:messaging:scheduled-job:view";
    public static final String ADMIN_SCHEDULED_JOB_CREATE = "platform:messaging:scheduled-job:create";
    public static final String ADMIN_SCHEDULED_JOB_UPDATE = "platform:messaging:scheduled-job:update";
    public static final String ADMIN_SCHEDULED_JOB_DELETE = "platform:messaging:scheduled-job:delete";
    public static final String ADMIN_SCHEDULED_JOB_PAUSE = "platform:messaging:scheduled-job:pause";
    public static final String ADMIN_SCHEDULED_JOB_FIRE = "platform:messaging:scheduled-job:fire";
    public static final String ADMIN_SCHEDULED_JOB_MANAGE = "platform:messaging:scheduled-job:manage";
    public static final String ADMIN_SCHEDULED_JOB_SYNC = "platform:messaging:scheduled-job:sync";
    public static final String ADMIN_SCHEDULED_JOB_INSTANCE_READ = "platform:messaging:scheduled-job-instance:view";

    // Identity provider
    public static final String ADMIN_IDENTITY_PROVIDER_READ = "platform:iam:idp:view";
    public static final String ADMIN_IDENTITY_PROVIDER_CREATE = "platform:iam:idp:create";
    public static final String ADMIN_IDENTITY_PROVIDER_UPDATE = "platform:iam:idp:update";
    public static final String ADMIN_IDENTITY_PROVIDER_DELETE = "platform:iam:idp:delete";
    public static final String ADMIN_IDENTITY_PROVIDER_MANAGE = "platform:iam:idp:manage";

    // Email-domain mapping
    public static final String ADMIN_EMAIL_DOMAIN_MAPPING_READ = "platform:iam:email-domain-mapping:view";
    public static final String ADMIN_EMAIL_DOMAIN_MAPPING_CREATE = "platform:iam:email-domain-mapping:create";
    public static final String ADMIN_EMAIL_DOMAIN_MAPPING_UPDATE = "platform:iam:email-domain-mapping:update";
    public static final String ADMIN_EMAIL_DOMAIN_MAPPING_DELETE = "platform:iam:email-domain-mapping:delete";
    public static final String ADMIN_EMAIL_DOMAIN_MAPPING_MANAGE = "platform:iam:email-domain-mapping:manage";

    // Service account
    public static final String ADMIN_SERVICE_ACCOUNT_READ = "platform:iam:service-account:view";
    public static final String ADMIN_SERVICE_ACCOUNT_CREATE = "platform:iam:service-account:create";
    public static final String ADMIN_SERVICE_ACCOUNT_UPDATE = "platform:iam:service-account:update";
    public static final String ADMIN_SERVICE_ACCOUNT_DELETE = "platform:iam:service-account:delete";
    public static final String ADMIN_SERVICE_ACCOUNT_MANAGE = "platform:iam:service-account:manage";

    // CORS
    public static final String ADMIN_CORS_ORIGIN_READ = "platform:admin:cors-origin:view";
    public static final String ADMIN_CORS_ORIGIN_CREATE = "platform:admin:cors-origin:create";
    public static final String ADMIN_CORS_ORIGIN_DELETE = "platform:admin:cors-origin:delete";
    public static final String ADMIN_CORS_ORIGIN_MANAGE = "platform:admin:cors-origin:manage";

    // Login + audit
    public static final String ADMIN_LOGIN_ATTEMPT_READ = "platform:admin:login-attempt:view";

    // Platform documentation (embedded docs served at /api/docs)
    public static final String ADMIN_DOCS_READ = "platform:admin:docs:view";
    public static final String ADMIN_AUDIT_LOG_READ = "platform:admin:audit-log:view";
    public static final String ADMIN_AUDIT_LOG_EXPORT = "platform:admin:audit-log:export";

    // Config
    public static final String ADMIN_CONFIG_READ = "platform:admin:config:view";
    public static final String ADMIN_CONFIG_UPDATE = "platform:admin:config:update";

    // Batch
    public static final String ADMIN_BATCH_EVENTS_WRITE = "platform:messaging:batch:events-write";
    public static final String ADMIN_BATCH_DISPATCH_JOBS_WRITE = "platform:messaging:batch:dispatch-jobs-write";
    public static final String ADMIN_BATCH_AUDIT_LOGS_WRITE = "platform:admin:batch:audit-logs-write";

    // ── IAM context — users, roles, access control ──────────────────────
    public static final String IAM_USER_READ = "platform:iam:user:view";
    public static final String IAM_USER_CREATE = "platform:iam:user:create";
    public static final String IAM_USER_UPDATE = "platform:iam:user:update";
    public static final String IAM_USER_DELETE = "platform:iam:user:delete";
    public static final String IAM_USER_MANAGE = "platform:iam:user:manage";
    public static final String IAM_USER_ACTIVATE = "platform:iam:user:activate";
    public static final String IAM_USER_DEACTIVATE = "platform:iam:user:deactivate";
    public static final String IAM_USER_ASSIGN_ROLES = "platform:iam:user:assign-roles";
    public static final String IAM_ROLE_READ = "platform:iam:role:view";
    public static final String IAM_ROLE_CREATE = "platform:iam:role:create";
    public static final String IAM_ROLE_UPDATE = "platform:iam:role:update";
    public static final String IAM_ROLE_DELETE = "platform:iam:role:delete";
    public static final String IAM_ROLE_MANAGE = "platform:iam:role:manage";
    public static final String IAM_CLIENT_ACCESS_GRANT = "platform:iam:client-access:grant";
    public static final String IAM_CLIENT_ACCESS_REVOKE = "platform:iam:client-access:revoke";
    public static final String IAM_CLIENT_ACCESS_READ = "platform:iam:client-access:view";
    public static final String IAM_PERMISSION_READ = "platform:iam:permission:view";

    // ── Auth context — OAuth clients + per-tenant auth configs ──────────
    public static final String AUTH_CLIENT_AUTH_CONFIG_READ = "platform:auth:client-auth-config:view";
    public static final String AUTH_CLIENT_AUTH_CONFIG_CREATE = "platform:auth:client-auth-config:create";
    public static final String AUTH_CLIENT_AUTH_CONFIG_UPDATE = "platform:auth:client-auth-config:update";
    public static final String AUTH_CLIENT_AUTH_CONFIG_DELETE = "platform:auth:client-auth-config:delete";
    public static final String AUTH_CLIENT_AUTH_CONFIG_MANAGE = "platform:auth:client-auth-config:manage";
    public static final String AUTH_OAUTH_CLIENT_READ = "platform:auth:oauth-client:view";
    public static final String AUTH_OAUTH_CLIENT_CREATE = "platform:auth:oauth-client:create";
    public static final String AUTH_OAUTH_CLIENT_UPDATE = "platform:auth:oauth-client:update";
    public static final String AUTH_OAUTH_CLIENT_DELETE = "platform:auth:oauth-client:delete";
    public static final String AUTH_OAUTH_CLIENT_MANAGE = "platform:auth:oauth-client:manage";
    public static final String AUTH_OAUTH_CLIENT_REGENERATE_SECRET = "platform:auth:oauth-client:regenerate-secret";

    // ── Developer portal ────────────────────────────────────────────────
    public static final String DEVELOPER_APPLICATION_OPENAPI_VIEW = "platform:developer:application-openapi:view";
    public static final String DEVELOPER_APPLICATION_OPENAPI_SYNC = "platform:developer:application-openapi:sync";
    public static final String DEVELOPER_APPLICATION_OPENAPI_MANAGE = "platform:developer:application-openapi:manage";
    /// Gates self-service developer `client_credentials` tokens:
    /// create/rotate/revoke your OWN credential. Resource-level enforcement
    /// (self vs admin-on-behalf-of) lives in the use case's Authorize phase,
    /// not here.
    public static final String DEVELOPER_API_CREDENTIAL_MANAGE = "platform:developer:api-credential:manage";

    // Portal identity plane (docs/portal-identity-plan.md Phase 2.5 v2).
    public static final String PORTAL_USER_VIEW = "platform:iam:portal-user:view";
    public static final String PORTAL_USER_MANAGE = "platform:iam:portal-user:manage";

    /// Application-service permissions — scoped to a single application via
    /// the SDK. Order is the order Go declares them (it is the insertion order
    /// of the `platform:application-service` role's permission rows).
    public static final List<String> APPLICATION_SERVICE = List.of(
            "platform:application-service:event:create",
            "platform:application-service:event-type:view",
            "platform:application-service:event-type:create",
            "platform:application-service:event-type:update",
            "platform:application-service:event-type:delete",
            "platform:application-service:subscription:view",
            "platform:application-service:subscription:create",
            "platform:application-service:subscription:update",
            "platform:application-service:subscription:delete",
            "platform:application-service:role:view",
            "platform:application-service:role:create",
            "platform:application-service:role:update",
            "platform:application-service:role:delete",
            "platform:application-service:permission:view",
            "platform:application-service:permission:sync",
            "platform:application-service:scheduled-job-instance:write",
            "platform:application-service:docs:sync",
            "platform:application-service:scheduled-job:sync",
            "platform:application-service:process:view",
            "platform:application-service:process:sync");

    // ── Function context — platform/client-owned functions (spec function-api.md §2) ──
    public static final String FUNCTION_VIEW = "platform:function:function:view";
    public static final String FUNCTION_MANAGE = "platform:function:function:manage";
    public static final String FUNCTION_PUBLISH = "platform:function:version:publish";
    public static final String FUNCTION_PROMOTE = "platform:function:alias:promote";
    public static final String FUNCTION_POLICY_MANAGE = "platform:function:policy:manage";
    public static final String FUNCTION_HOST_CONTROL = "platform:function:host:control";
    public static final String FUNCTION_VERSION_INVOKE = "platform:function:version:invoke";
    public static final String FUNCTION_SECRET_MANAGE = "platform:function:secret:manage";
    public static final String FUNCTION_DOMAIN_MANAGE = "platform:function:domain:manage";

    /// Wildcard.
    public static final String ADMIN_ALL = "platform:*:*:*";
}
