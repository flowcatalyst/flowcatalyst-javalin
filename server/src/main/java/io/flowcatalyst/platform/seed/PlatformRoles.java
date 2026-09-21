package io.flowcatalyst.platform.seed;

import static io.flowcatalyst.platform.seed.Permissions.*;

import java.util.List;

/// The built-in roles — `internal/platform/seed/roles.go`. Each role uses
/// `source = CODE` so the role-sync logic can identify them. Names are
/// `{applicationCode}:{roleName}` (e.g. `platform:admin`) to match the rows
/// installed by earlier deployments.
///
/// Public so the role/operations/sync use case can diff against the catalogue
/// without an inter-package indirection.
public final class PlatformRoles {

    /// Every built-in role hangs off the platform application.
    public static final String APPLICATION_CODE = "platform";

    private static final List<RoleDefinition> ALL = List.of(
            // platform:super-admin
            mk("super-admin", "Platform Super Admin",
                    "Full access to all platform operations",
                    List.of(ADMIN_ALL)),

            // platform:admin
            mk("admin", "Platform Admin",
                    "Manages clients, applications, and platform configuration",
                    List.of(
                            ADMIN_CLIENT_READ, ADMIN_CLIENT_CREATE, ADMIN_CLIENT_UPDATE,
                            ADMIN_CLIENT_ACTIVATE, ADMIN_CLIENT_SUSPEND, ADMIN_CLIENT_DEACTIVATE,
                            ADMIN_ANCHOR_DOMAIN_READ, ADMIN_ANCHOR_DOMAIN_CREATE,
                            ADMIN_ANCHOR_DOMAIN_UPDATE, ADMIN_ANCHOR_DOMAIN_DELETE,
                            ADMIN_APPLICATION_READ, ADMIN_APPLICATION_CREATE,
                            ADMIN_APPLICATION_UPDATE, ADMIN_APPLICATION_DELETE,
                            ADMIN_APPLICATION_ENABLE_CLIENT, ADMIN_APPLICATION_DISABLE_CLIENT,
                            ADMIN_AUDIT_LOG_READ, ADMIN_AUDIT_LOG_EXPORT,
                            ADMIN_LOGIN_ATTEMPT_READ,
                            ADMIN_DOCS_READ,
                            DEVELOPER_APPLICATION_OPENAPI_MANAGE,
                            // docs/spec/reach-only-routes.md §2: config + CORS-origin were
                            // held by no role before the permission gates existed.
                            ADMIN_CONFIG_READ, ADMIN_CONFIG_UPDATE,
                            ADMIN_CORS_ORIGIN_READ, ADMIN_CORS_ORIGIN_CREATE, ADMIN_CORS_ORIGIN_DELETE)),

            // platform:admin-readonly
            mk("admin-readonly", "Platform Admin Read-Only",
                    "View-only access to clients, applications, and platform configuration",
                    List.of(
                            ADMIN_CLIENT_READ,
                            ADMIN_ANCHOR_DOMAIN_READ,
                            ADMIN_APPLICATION_READ,
                            ADMIN_AUDIT_LOG_READ,
                            ADMIN_LOGIN_ATTEMPT_READ,
                            ADMIN_DOCS_READ,
                            DEVELOPER_APPLICATION_OPENAPI_VIEW,
                            // docs/spec/reach-only-routes.md §2.
                            ADMIN_CONFIG_READ,
                            ADMIN_CORS_ORIGIN_READ)),

            // platform:iam-admin
            mk("iam-admin", "Platform IAM Admin",
                    "Manages users, roles, and access control",
                    List.of(
                            IAM_USER_READ, IAM_USER_CREATE, IAM_USER_UPDATE, IAM_USER_DELETE,
                            IAM_USER_ACTIVATE, IAM_USER_DEACTIVATE, IAM_USER_ASSIGN_ROLES,
                            IAM_ROLE_READ, IAM_ROLE_CREATE, IAM_ROLE_UPDATE, IAM_ROLE_DELETE,
                            IAM_CLIENT_ACCESS_GRANT, IAM_CLIENT_ACCESS_REVOKE, IAM_CLIENT_ACCESS_READ,
                            // docs/spec/reach-only-routes.md §2: IdP + email-domain-mapping
                            // were held by no role before the permission gates existed.
                            ADMIN_IDENTITY_PROVIDER_READ, ADMIN_IDENTITY_PROVIDER_CREATE,
                            ADMIN_IDENTITY_PROVIDER_UPDATE, ADMIN_IDENTITY_PROVIDER_DELETE,
                            ADMIN_EMAIL_DOMAIN_MAPPING_READ, ADMIN_EMAIL_DOMAIN_MAPPING_CREATE,
                            ADMIN_EMAIL_DOMAIN_MAPPING_UPDATE, ADMIN_EMAIL_DOMAIN_MAPPING_DELETE)),

            // platform:iam-readonly
            mk("iam-readonly", "Platform IAM Read-Only",
                    "View-only access to users and roles",
                    List.of(
                            IAM_USER_READ,
                            IAM_ROLE_READ,
                            IAM_CLIENT_ACCESS_READ,
                            // docs/spec/reach-only-routes.md §2.
                            ADMIN_IDENTITY_PROVIDER_READ,
                            ADMIN_EMAIL_DOMAIN_MAPPING_READ)),

            // platform:client-admin — delegated user management scoped to the
            // administrator's own client(s). Same user permissions as iam-admin
            // MINUS client-access grant/revoke and role authoring. Every action is
            // additionally scope-gated to the client(s) the admin can access (via
            // auth.RequireUserAdmin), and role assignment is bounded to the client's
            // own application roles — never platform roles. See
            // docs/auth-hardening-plan.md.
            mk("client-admin", "Client Administrator",
                    "Manages users within the administrator's own client",
                    List.of(
                            IAM_USER_READ, IAM_USER_CREATE, IAM_USER_UPDATE, IAM_USER_DELETE,
                            IAM_USER_ACTIVATE, IAM_USER_DEACTIVATE, IAM_USER_ASSIGN_ROLES,
                            IAM_ROLE_READ)),

            // platform:auth-admin
            mk("auth-admin", "Platform Auth Admin",
                    "Manages authentication configuration",
                    List.of(
                            AUTH_CLIENT_AUTH_CONFIG_READ, AUTH_CLIENT_AUTH_CONFIG_CREATE,
                            AUTH_CLIENT_AUTH_CONFIG_UPDATE, AUTH_CLIENT_AUTH_CONFIG_DELETE,
                            AUTH_OAUTH_CLIENT_READ, AUTH_OAUTH_CLIENT_CREATE,
                            AUTH_OAUTH_CLIENT_UPDATE, AUTH_OAUTH_CLIENT_DELETE,
                            AUTH_OAUTH_CLIENT_REGENERATE_SECRET)),

            // platform:auth-readonly
            mk("auth-readonly", "Platform Auth Read-Only",
                    "View-only access to authentication configuration",
                    List.of(
                            AUTH_CLIENT_AUTH_CONFIG_READ,
                            AUTH_OAUTH_CLIENT_READ)),

            // platform:ai-agent-readonly
            mk("ai-agent-readonly", "AI Agent Read-Only",
                    "Read-only access to event types and subscriptions for AI agent integrations",
                    List.of(
                            ADMIN_EVENT_TYPE_READ,
                            ADMIN_SUBSCRIPTION_READ)),

            // platform:messaging-admin
            mk("messaging-admin", "Messaging Administrator",
                    "Manages event types, subscriptions, dispatch jobs, and scheduled jobs",
                    List.of(
                            ADMIN_EVENT_TYPE_READ, ADMIN_EVENT_TYPE_CREATE, ADMIN_EVENT_TYPE_UPDATE,
                            ADMIN_EVENT_TYPE_DELETE, ADMIN_EVENT_TYPE_ARCHIVE,
                            ADMIN_EVENT_TYPE_MANAGE_SCHEMA, ADMIN_EVENT_TYPE_SYNC,
                            ADMIN_SUBSCRIPTION_READ, ADMIN_SUBSCRIPTION_CREATE,
                            ADMIN_SUBSCRIPTION_UPDATE, ADMIN_SUBSCRIPTION_DELETE, ADMIN_SUBSCRIPTION_SYNC,
                            ADMIN_DISPATCH_POOL_READ, ADMIN_DISPATCH_POOL_CREATE,
                            ADMIN_DISPATCH_POOL_UPDATE, ADMIN_DISPATCH_POOL_DELETE, ADMIN_DISPATCH_POOL_SYNC,
                            ADMIN_CONNECTION_READ, ADMIN_CONNECTION_CREATE,
                            ADMIN_CONNECTION_UPDATE, ADMIN_CONNECTION_DELETE, ADMIN_CONNECTION_SYNC,
                            ADMIN_EVENT_READ, ADMIN_EVENT_VIEW_RAW,
                            ADMIN_DISPATCH_JOB_READ, ADMIN_DISPATCH_JOB_VIEW_RAW,
                            ADMIN_SCHEDULED_JOB_READ, ADMIN_SCHEDULED_JOB_CREATE,
                            ADMIN_SCHEDULED_JOB_UPDATE, ADMIN_SCHEDULED_JOB_DELETE,
                            ADMIN_SCHEDULED_JOB_PAUSE, ADMIN_SCHEDULED_JOB_FIRE, ADMIN_SCHEDULED_JOB_SYNC,
                            ADMIN_SCHEDULED_JOB_INSTANCE_READ,
                            ADMIN_PROCESS_READ, ADMIN_PROCESS_CREATE, ADMIN_PROCESS_UPDATE,
                            ADMIN_PROCESS_DELETE, ADMIN_PROCESS_ARCHIVE, ADMIN_PROCESS_SYNC)),

            // platform:viewer
            mk("viewer", "Platform Viewer",
                    "Read-only access across IAM, admin, and messaging",
                    List.of(
                            IAM_USER_READ,
                            IAM_ROLE_READ,
                            IAM_CLIENT_ACCESS_READ,
                            ADMIN_CLIENT_READ,
                            ADMIN_APPLICATION_READ,
                            ADMIN_EVENT_READ,
                            ADMIN_EVENT_TYPE_READ,
                            ADMIN_SUBSCRIPTION_READ,
                            ADMIN_DISPATCH_JOB_READ,
                            ADMIN_DISPATCH_POOL_READ,
                            ADMIN_SCHEDULED_JOB_READ,
                            ADMIN_SCHEDULED_JOB_INSTANCE_READ,
                            ADMIN_PROCESS_READ,
                            ADMIN_AUDIT_LOG_READ,
                            ADMIN_LOGIN_ATTEMPT_READ,
                            // docs/spec/reach-only-routes.md §2.
                            ADMIN_IDENTITY_PROVIDER_READ,
                            ADMIN_EMAIL_DOMAIN_MAPPING_READ,
                            ADMIN_CONFIG_READ,
                            ADMIN_CORS_ORIGIN_READ)),

            // platform:portal-administrator — CLIENT-delegable: assign to a
            // client administrator (manage their client's portal users in the
            // platform UI) or to the portal application's service account (the
            // /api/portal-users surface). Client confinement comes from the
            // holder's own client scope, not from the role.
            mk("portal-administrator", "Portal Administrator",
                    "Manage the client's portal users: invite, suspend, and remove portal identities",
                    List.of(
                            PORTAL_USER_VIEW, PORTAL_USER_MANAGE)),

            // platform:developer
            mk("developer", "Developer",
                    "Developer portal: API documentation, accessible event types, and a self-service API credential for local testing",
                    List.of(
                            DEVELOPER_APPLICATION_OPENAPI_VIEW,
                            DEVELOPER_API_CREDENTIAL_MANAGE,
                            ADMIN_EVENT_TYPE_READ,
                            ADMIN_PROCESS_READ, ADMIN_PROCESS_CREATE,
                            ADMIN_PROCESS_UPDATE, ADMIN_PROCESS_DELETE, ADMIN_PROCESS_ARCHIVE)),

            // platform:application-service
            mk("application-service", "Application Service Account",
                    "Permissions for application service accounts (scoped to own application)",
                    APPLICATION_SERVICE),

            // platform:router — `docs/spec/router-config-auth.md` R3′: the one
            // permission the router's client-credentials principal needs to
            // fetch `/api/dispatch/router-config`. Anchor-only (every built-in
            // role is), not client-delegable — the document lists every
            // client's queues.
            mk("router", "Router",
                    "Fetches the dispatch router configuration",
                    List.of(ADMIN_DISPATCH_POOL_READ)));

    private PlatformRoles() {
    }

    /// The built-in roles, in the order Go declares (and inserts) them.
    public static List<RoleDefinition> all() {
        return ALL;
    }

    /// Go's `mk`: `role.New("platform", roleName, displayName)` with
    /// `Source = CODE` and the permission list attached.
    private static RoleDefinition mk(String roleName, String displayName, String description,
                                     List<String> permissions) {
        return new RoleDefinition(
                APPLICATION_CODE + ":" + roleName, displayName, description,
                APPLICATION_CODE, RoleDefinition.SOURCE_CODE, permissions);
    }
}
