package io.flowcatalyst.platform.shared.auth;

import java.util.List;

/// The permission catalogue the typed [Checks] use — the 4-segment
/// `platform:<context>:<resource>:<action>` identifiers stored in
/// `iam_role_permissions` and pinned by the SDK. These MUST stay byte-identical
/// to the Go `auth` package constants / `seed/permissions.go`.
///
/// Matching: a *held* permission may contain `*` segments (a role granting
/// `platform:messaging:*:*`, or the super-admin `platform:*:*:*`); it
/// satisfies a required code with the same segment count whose non-wildcard
/// segments are equal.
public final class Permissions {

    private Permissions() {
    }

    // EventType (messaging)
    public static final String EVENT_TYPE_VIEW = "platform:messaging:event-type:view";
    public static final String EVENT_TYPE_CREATE = "platform:messaging:event-type:create";
    public static final String EVENT_TYPE_UPDATE = "platform:messaging:event-type:update";
    public static final String EVENT_TYPE_DELETE = "platform:messaging:event-type:delete";
    public static final String EVENT_TYPE_SYNC = "platform:messaging:event-type:sync";
    public static final String EVENT_TYPE_MANAGE = "platform:messaging:event-type:manage";
    // Connection (messaging)
    public static final String CONNECTION_VIEW = "platform:messaging:connection:view";
    public static final String CONNECTION_CREATE = "platform:messaging:connection:create";
    public static final String CONNECTION_UPDATE = "platform:messaging:connection:update";
    public static final String CONNECTION_DELETE = "platform:messaging:connection:delete";
    // Subscription (messaging)
    public static final String SUBSCRIPTION_VIEW = "platform:messaging:subscription:view";
    public static final String SUBSCRIPTION_CREATE = "platform:messaging:subscription:create";
    public static final String SUBSCRIPTION_UPDATE = "platform:messaging:subscription:update";
    public static final String SUBSCRIPTION_DELETE = "platform:messaging:subscription:delete";
    public static final String SUBSCRIPTION_SYNC = "platform:messaging:subscription:sync";
    public static final String SUBSCRIPTION_MANAGE = "platform:messaging:subscription:manage";
    // DispatchPool (messaging)
    public static final String DISPATCH_POOL_VIEW = "platform:messaging:dispatch-pool:view";
    public static final String DISPATCH_POOL_CREATE = "platform:messaging:dispatch-pool:create";
    public static final String DISPATCH_POOL_UPDATE = "platform:messaging:dispatch-pool:update";
    public static final String DISPATCH_POOL_DELETE = "platform:messaging:dispatch-pool:delete";
    public static final String DISPATCH_POOL_SYNC = "platform:messaging:dispatch-pool:sync";
    public static final String DISPATCH_POOL_MANAGE = "platform:messaging:dispatch-pool:manage";
    // Process (messaging)
    public static final String PROCESS_VIEW = "platform:messaging:process:view";
    public static final String PROCESS_CREATE = "platform:messaging:process:create";
    public static final String PROCESS_UPDATE = "platform:messaging:process:update";
    public static final String PROCESS_DELETE = "platform:messaging:process:delete";
    public static final String PROCESS_SYNC = "platform:messaging:process:sync";
    // Application (admin)
    public static final String APPLICATION_VIEW = "platform:admin:application:view";
    public static final String APPLICATION_CREATE = "platform:admin:application:create";
    public static final String APPLICATION_UPDATE = "platform:admin:application:update";
    public static final String APPLICATION_DELETE = "platform:admin:application:delete";
    // Role (iam)
    public static final String ROLE_VIEW = "platform:iam:role:view";
    public static final String ROLE_CREATE = "platform:iam:role:create";
    public static final String ROLE_UPDATE = "platform:iam:role:update";
    public static final String ROLE_DELETE = "platform:iam:role:delete";
    public static final String ROLE_MANAGE = "platform:iam:role:manage";
    // Application-service (held by SDK service accounts for /api/applications/{appCode}/{resource}/sync)
    public static final String APP_SVC_EVENT_TYPE_CREATE = "platform:application-service:event-type:create";
    public static final String APP_SVC_EVENT_TYPE_UPDATE = "platform:application-service:event-type:update";
    public static final String APP_SVC_EVENT_TYPE_DELETE = "platform:application-service:event-type:delete";
    public static final String APP_SVC_ROLE_CREATE = "platform:application-service:role:create";
    public static final String APP_SVC_ROLE_UPDATE = "platform:application-service:role:update";
    public static final String APP_SVC_ROLE_DELETE = "platform:application-service:role:delete";
    public static final String APP_SVC_PROCESS_SYNC = "platform:application-service:process:sync";
    public static final String APP_SVC_SUBSCRIPTION_CREATE = "platform:application-service:subscription:create";
    public static final String APP_SVC_SUBSCRIPTION_UPDATE = "platform:application-service:subscription:update";
    public static final String APP_SVC_SUBSCRIPTION_DELETE = "platform:application-service:subscription:delete";
    public static final String APP_SVC_SCHEDULED_JOB_SYNC = "platform:application-service:scheduled-job:sync";
    public static final String APP_SVC_DOCS_SYNC = "platform:application-service:docs:sync";
    // Developer (application OpenAPI documents)
    public static final String APP_OPENAPI_SYNC = "platform:developer:application-openapi:sync";
    public static final String APP_OPENAPI_MANAGE = "platform:developer:application-openapi:manage";
    // Developer (self-service client_credentials API credential)
    public static final String DEVELOPER_API_CREDENTIAL_MANAGE = "platform:developer:api-credential:manage";
    // ServiceAccount (iam)
    public static final String SERVICE_ACCOUNT_VIEW = "platform:iam:service-account:view";
    public static final String SERVICE_ACCOUNT_CREATE = "platform:iam:service-account:create";
    public static final String SERVICE_ACCOUNT_UPDATE = "platform:iam:service-account:update";
    public static final String SERVICE_ACCOUNT_DELETE = "platform:iam:service-account:delete";
    // Platform documentation (embedded docs/*.md served at /api/docs)
    public static final String DOCS_VIEW = "platform:admin:docs:view";
    // Portal users (iam)
    public static final String PORTAL_USER_VIEW = "platform:iam:portal-user:view";
    public static final String PORTAL_USER_MANAGE = "platform:iam:portal-user:manage";
    // User / Principal (iam)
    public static final String USER_VIEW = "platform:iam:user:view";
    public static final String USER_CREATE = "platform:iam:user:create";
    public static final String USER_UPDATE = "platform:iam:user:update";
    public static final String USER_DELETE = "platform:iam:user:delete";
    public static final String USER_MANAGE = "platform:iam:user:manage";
    public static final String USER_ASSIGN_ROLES = "platform:iam:user:assign-roles";
    // ScheduledJob (messaging)
    public static final String SCHEDULED_JOB_VIEW = "platform:messaging:scheduled-job:view";
    public static final String SCHEDULED_JOB_CREATE = "platform:messaging:scheduled-job:create";
    public static final String SCHEDULED_JOB_UPDATE = "platform:messaging:scheduled-job:update";
    public static final String SCHEDULED_JOB_DELETE = "platform:messaging:scheduled-job:delete";
    public static final String SCHEDULED_JOB_FIRE = "platform:messaging:scheduled-job:fire";
    public static final String SCHEDULED_JOB_SYNC = "platform:messaging:scheduled-job:sync";
    public static final String SCHEDULED_JOB_MANAGE = "platform:messaging:scheduled-job:manage";
    // Super-admin wildcard
    public static final String SUPER_ADMIN = "platform:*:*:*";

    /// Whether any held pattern satisfies `required` (Go `auth.Grants`). Used
    /// by enforcement *and* by the token-mint path to narrow a requested OAuth
    /// scope to the principal's ceiling with the exact same rule.
    public static boolean grants(List<String> held, String required) {
        if (held == null || required == null) return false;
        return held.stream().anyMatch(p -> matches(p, required));
    }

    /// Go `permissionMatches`: equal strings, or same segment count with every
    /// held segment either `*` or equal to the required one.
    public static boolean matches(String held, String required) {
        if (held.equals(required)) return true;
        var h = held.split(":", -1);
        var r = required.split(":", -1);
        if (h.length != r.length) return false;
        for (var i = 0; i < h.length; i++) {
            if (!h[i].equals("*") && !h[i].equals(r[i])) return false;
        }
        return true;
    }
}
