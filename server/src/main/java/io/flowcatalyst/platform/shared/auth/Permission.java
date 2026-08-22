package io.flowcatalyst.platform.shared.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// The permission catalogue as a type: one constant per 4-segment
/// `platform:<context>:<resource>:<action>` identifier stored in
/// `iam_role_permissions` and pinned by the SDK (the Go `auth` package
/// constants / `seed/permissions.go`). [Checks] takes these; the seeder keeps
/// its own string catalogue (`seed.Permissions`) because that is what it
/// writes — `PermissionTest` pins the two sets equal so they cannot drift.
///
/// Naming is mechanical — `<RESOURCE>_<ACTION>` upper-snake, prefixed
/// `APP_SVC_` for the `application-service` context (whose resources collide
/// with the admin/iam/messaging ones) — so a constant can be found from a
/// wire code without a lookup. [#SUPER_ADMIN] is the one exception: the
/// all-wildcard `platform:*:*:*`.
///
/// Matching ([#matches], [#grants]): a *held* permission may contain `*`
/// segments (a role granting `platform:messaging:*:*`, or the super-admin
/// wildcard); it satisfies a required code with the same segment count whose
/// non-wildcard segments are equal. Held patterns are therefore strings, not
/// [Permission]s — only the *required* side is ever typed.
public enum Permission {

    // admin / client
    CLIENT_VIEW("platform:admin:client:view"),
    CLIENT_CREATE("platform:admin:client:create"),
    CLIENT_UPDATE("platform:admin:client:update"),
    CLIENT_DELETE("platform:admin:client:delete"),
    CLIENT_MANAGE("platform:admin:client:manage"),
    CLIENT_ACTIVATE("platform:admin:client:activate"),
    CLIENT_SUSPEND("platform:admin:client:suspend"),
    CLIENT_DEACTIVATE("platform:admin:client:deactivate"),

    // admin / anchor-domain
    ANCHOR_DOMAIN_VIEW("platform:admin:anchor-domain:view"),
    ANCHOR_DOMAIN_CREATE("platform:admin:anchor-domain:create"),
    ANCHOR_DOMAIN_UPDATE("platform:admin:anchor-domain:update"),
    ANCHOR_DOMAIN_DELETE("platform:admin:anchor-domain:delete"),
    ANCHOR_DOMAIN_MANAGE("platform:admin:anchor-domain:manage"),

    // admin / application
    APPLICATION_VIEW("platform:admin:application:view"),
    APPLICATION_CREATE("platform:admin:application:create"),
    APPLICATION_UPDATE("platform:admin:application:update"),
    APPLICATION_DELETE("platform:admin:application:delete"),
    APPLICATION_MANAGE("platform:admin:application:manage"),
    APPLICATION_ACTIVATE("platform:admin:application:activate"),
    APPLICATION_DEACTIVATE("platform:admin:application:deactivate"),
    APPLICATION_ENABLE_CLIENT("platform:admin:application:enable-client"),
    APPLICATION_DISABLE_CLIENT("platform:admin:application:disable-client"),

    // messaging / event-type
    EVENT_TYPE_VIEW("platform:messaging:event-type:view"),
    EVENT_TYPE_CREATE("platform:messaging:event-type:create"),
    EVENT_TYPE_UPDATE("platform:messaging:event-type:update"),
    EVENT_TYPE_DELETE("platform:messaging:event-type:delete"),
    EVENT_TYPE_MANAGE("platform:messaging:event-type:manage"),
    EVENT_TYPE_ARCHIVE("platform:messaging:event-type:archive"),
    EVENT_TYPE_MANAGE_SCHEMA("platform:messaging:event-type:manage-schema"),
    EVENT_TYPE_SYNC("platform:messaging:event-type:sync"),

    // messaging / process
    PROCESS_VIEW("platform:messaging:process:view"),
    PROCESS_CREATE("platform:messaging:process:create"),
    PROCESS_UPDATE("platform:messaging:process:update"),
    PROCESS_DELETE("platform:messaging:process:delete"),
    PROCESS_MANAGE("platform:messaging:process:manage"),
    PROCESS_ARCHIVE("platform:messaging:process:archive"),
    PROCESS_SYNC("platform:messaging:process:sync"),

    // messaging / dispatch-pool
    DISPATCH_POOL_VIEW("platform:messaging:dispatch-pool:view"),
    DISPATCH_POOL_CREATE("platform:messaging:dispatch-pool:create"),
    DISPATCH_POOL_UPDATE("platform:messaging:dispatch-pool:update"),
    DISPATCH_POOL_DELETE("platform:messaging:dispatch-pool:delete"),
    DISPATCH_POOL_MANAGE("platform:messaging:dispatch-pool:manage"),
    DISPATCH_POOL_SYNC("platform:messaging:dispatch-pool:sync"),

    // messaging / connection
    CONNECTION_VIEW("platform:messaging:connection:view"),
    CONNECTION_CREATE("platform:messaging:connection:create"),
    CONNECTION_UPDATE("platform:messaging:connection:update"),
    CONNECTION_DELETE("platform:messaging:connection:delete"),
    CONNECTION_MANAGE("platform:messaging:connection:manage"),

    // messaging / subscription
    SUBSCRIPTION_VIEW("platform:messaging:subscription:view"),
    SUBSCRIPTION_CREATE("platform:messaging:subscription:create"),
    SUBSCRIPTION_UPDATE("platform:messaging:subscription:update"),
    SUBSCRIPTION_DELETE("platform:messaging:subscription:delete"),
    SUBSCRIPTION_MANAGE("platform:messaging:subscription:manage"),
    SUBSCRIPTION_SYNC("platform:messaging:subscription:sync"),

    // messaging / event
    EVENT_VIEW("platform:messaging:event:view"),
    EVENT_VIEW_RAW("platform:messaging:event:view-raw"),

    // messaging / dispatch-job
    DISPATCH_JOB_VIEW("platform:messaging:dispatch-job:view"),
    DISPATCH_JOB_VIEW_RAW("platform:messaging:dispatch-job:view-raw"),

    // messaging / scheduled-job
    SCHEDULED_JOB_VIEW("platform:messaging:scheduled-job:view"),
    SCHEDULED_JOB_CREATE("platform:messaging:scheduled-job:create"),
    SCHEDULED_JOB_UPDATE("platform:messaging:scheduled-job:update"),
    SCHEDULED_JOB_DELETE("platform:messaging:scheduled-job:delete"),
    SCHEDULED_JOB_PAUSE("platform:messaging:scheduled-job:pause"),
    SCHEDULED_JOB_FIRE("platform:messaging:scheduled-job:fire"),
    SCHEDULED_JOB_MANAGE("platform:messaging:scheduled-job:manage"),
    SCHEDULED_JOB_SYNC("platform:messaging:scheduled-job:sync"),

    // messaging / scheduled-job-instance
    SCHEDULED_JOB_INSTANCE_VIEW("platform:messaging:scheduled-job-instance:view"),

    // iam / idp
    IDP_VIEW("platform:iam:idp:view"),
    IDP_CREATE("platform:iam:idp:create"),
    IDP_UPDATE("platform:iam:idp:update"),
    IDP_DELETE("platform:iam:idp:delete"),
    IDP_MANAGE("platform:iam:idp:manage"),

    // iam / email-domain-mapping
    EMAIL_DOMAIN_MAPPING_VIEW("platform:iam:email-domain-mapping:view"),
    EMAIL_DOMAIN_MAPPING_CREATE("platform:iam:email-domain-mapping:create"),
    EMAIL_DOMAIN_MAPPING_UPDATE("platform:iam:email-domain-mapping:update"),
    EMAIL_DOMAIN_MAPPING_DELETE("platform:iam:email-domain-mapping:delete"),
    EMAIL_DOMAIN_MAPPING_MANAGE("platform:iam:email-domain-mapping:manage"),

    // iam / service-account
    SERVICE_ACCOUNT_VIEW("platform:iam:service-account:view"),
    SERVICE_ACCOUNT_CREATE("platform:iam:service-account:create"),
    SERVICE_ACCOUNT_UPDATE("platform:iam:service-account:update"),
    SERVICE_ACCOUNT_DELETE("platform:iam:service-account:delete"),
    SERVICE_ACCOUNT_MANAGE("platform:iam:service-account:manage"),

    // admin / cors-origin
    CORS_ORIGIN_VIEW("platform:admin:cors-origin:view"),
    CORS_ORIGIN_CREATE("platform:admin:cors-origin:create"),
    CORS_ORIGIN_DELETE("platform:admin:cors-origin:delete"),
    CORS_ORIGIN_MANAGE("platform:admin:cors-origin:manage"),

    // admin / login-attempt
    LOGIN_ATTEMPT_VIEW("platform:admin:login-attempt:view"),

    // admin / docs
    DOCS_VIEW("platform:admin:docs:view"),

    // admin / audit-log
    AUDIT_LOG_VIEW("platform:admin:audit-log:view"),
    AUDIT_LOG_EXPORT("platform:admin:audit-log:export"),

    // admin / config
    CONFIG_VIEW("platform:admin:config:view"),
    CONFIG_UPDATE("platform:admin:config:update"),

    // messaging / batch
    BATCH_EVENTS_WRITE("platform:messaging:batch:events-write"),
    BATCH_DISPATCH_JOBS_WRITE("platform:messaging:batch:dispatch-jobs-write"),

    // admin / batch
    BATCH_AUDIT_LOGS_WRITE("platform:admin:batch:audit-logs-write"),

    // iam / user
    USER_VIEW("platform:iam:user:view"),
    USER_CREATE("platform:iam:user:create"),
    USER_UPDATE("platform:iam:user:update"),
    USER_DELETE("platform:iam:user:delete"),
    USER_MANAGE("platform:iam:user:manage"),
    USER_ACTIVATE("platform:iam:user:activate"),
    USER_DEACTIVATE("platform:iam:user:deactivate"),
    USER_ASSIGN_ROLES("platform:iam:user:assign-roles"),

    // iam / role
    ROLE_VIEW("platform:iam:role:view"),
    ROLE_CREATE("platform:iam:role:create"),
    ROLE_UPDATE("platform:iam:role:update"),
    ROLE_DELETE("platform:iam:role:delete"),
    ROLE_MANAGE("platform:iam:role:manage"),

    // iam / client-access
    CLIENT_ACCESS_GRANT("platform:iam:client-access:grant"),
    CLIENT_ACCESS_REVOKE("platform:iam:client-access:revoke"),
    CLIENT_ACCESS_VIEW("platform:iam:client-access:view"),

    // iam / permission
    PERMISSION_VIEW("platform:iam:permission:view"),

    // auth / client-auth-config
    CLIENT_AUTH_CONFIG_VIEW("platform:auth:client-auth-config:view"),
    CLIENT_AUTH_CONFIG_CREATE("platform:auth:client-auth-config:create"),
    CLIENT_AUTH_CONFIG_UPDATE("platform:auth:client-auth-config:update"),
    CLIENT_AUTH_CONFIG_DELETE("platform:auth:client-auth-config:delete"),
    CLIENT_AUTH_CONFIG_MANAGE("platform:auth:client-auth-config:manage"),

    // auth / oauth-client
    OAUTH_CLIENT_VIEW("platform:auth:oauth-client:view"),
    OAUTH_CLIENT_CREATE("platform:auth:oauth-client:create"),
    OAUTH_CLIENT_UPDATE("platform:auth:oauth-client:update"),
    OAUTH_CLIENT_DELETE("platform:auth:oauth-client:delete"),
    OAUTH_CLIENT_MANAGE("platform:auth:oauth-client:manage"),
    OAUTH_CLIENT_REGENERATE_SECRET("platform:auth:oauth-client:regenerate-secret"),

    // developer / application-openapi
    APPLICATION_OPENAPI_VIEW("platform:developer:application-openapi:view"),
    APPLICATION_OPENAPI_SYNC("platform:developer:application-openapi:sync"),
    APPLICATION_OPENAPI_MANAGE("platform:developer:application-openapi:manage"),

    // developer / api-credential
    API_CREDENTIAL_MANAGE("platform:developer:api-credential:manage"),

    // iam / portal-user
    PORTAL_USER_VIEW("platform:iam:portal-user:view"),
    PORTAL_USER_MANAGE("platform:iam:portal-user:manage"),

    // application-service / event
    APP_SVC_EVENT_CREATE("platform:application-service:event:create"),

    // application-service / event-type
    APP_SVC_EVENT_TYPE_VIEW("platform:application-service:event-type:view"),
    APP_SVC_EVENT_TYPE_CREATE("platform:application-service:event-type:create"),
    APP_SVC_EVENT_TYPE_UPDATE("platform:application-service:event-type:update"),
    APP_SVC_EVENT_TYPE_DELETE("platform:application-service:event-type:delete"),

    // application-service / subscription
    APP_SVC_SUBSCRIPTION_VIEW("platform:application-service:subscription:view"),
    APP_SVC_SUBSCRIPTION_CREATE("platform:application-service:subscription:create"),
    APP_SVC_SUBSCRIPTION_UPDATE("platform:application-service:subscription:update"),
    APP_SVC_SUBSCRIPTION_DELETE("platform:application-service:subscription:delete"),

    // application-service / role
    APP_SVC_ROLE_VIEW("platform:application-service:role:view"),
    APP_SVC_ROLE_CREATE("platform:application-service:role:create"),
    APP_SVC_ROLE_UPDATE("platform:application-service:role:update"),
    APP_SVC_ROLE_DELETE("platform:application-service:role:delete"),

    // application-service / permission
    APP_SVC_PERMISSION_VIEW("platform:application-service:permission:view"),
    APP_SVC_PERMISSION_SYNC("platform:application-service:permission:sync"),

    // application-service / scheduled-job-instance
    APP_SVC_SCHEDULED_JOB_INSTANCE_WRITE("platform:application-service:scheduled-job-instance:write"),

    // application-service / docs
    APP_SVC_DOCS_SYNC("platform:application-service:docs:sync"),

    // application-service / scheduled-job
    APP_SVC_SCHEDULED_JOB_SYNC("platform:application-service:scheduled-job:sync"),

    // application-service / process
    APP_SVC_PROCESS_VIEW("platform:application-service:process:view"),
    APP_SVC_PROCESS_SYNC("platform:application-service:process:sync"),

    // super-admin wildcard
    SUPER_ADMIN("platform:*:*:*");

    private static final Map<String, Permission> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    private final String code;
    private final String context;
    private final String resource;
    private final String action;

    Permission(String code) {
        var segments = code.split(":", -1);
        if (segments.length != 4 || !segments[0].equals("platform")) {
            throw new IllegalArgumentException("not a platform:<context>:<resource>:<action> code: " + code);
        }
        this.code = code;
        this.context = segments[1];
        this.resource = segments[2];
        this.action = segments[3];
    }

    /// The wire / DB identifier, e.g. `platform:messaging:event-type:view`.
    public String code() {
        return code;
    }

    /// Second segment: `admin`, `iam`, `auth`, `messaging`, `developer`,
    /// `application-service` (`*` for [#SUPER_ADMIN]).
    public String context() {
        return context;
    }

    /// Third segment, e.g. `event-type` (`*` for [#SUPER_ADMIN]).
    public String resource() {
        return resource;
    }

    /// Fourth segment, e.g. `view` (`*` for [#SUPER_ADMIN]).
    public String action() {
        return action;
    }

    /// Whether any of `held` (wildcards allowed) satisfies this permission.
    public boolean grantedBy(List<String> held) {
        return grants(held, code);
    }

    /// The constant for an exact wire code; empty for anything not in the
    /// catalogue (wildcard patterns included — those are held, never required).
    public static Optional<Permission> parse(String code) {
        return Optional.ofNullable(code).map(BY_CODE::get);
    }

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
