package io.flowcatalyst.platform.shared.tsid;

import io.flowcatalyst.sdk.tsid.Tsid;

/// The platform's typed-entity overlay on the raw TSID primitives: every
/// platform entity has a fixed three-letter prefix (`clt_…`, `prn_…`,
/// `aud_…`). These prefixes are wire-visible ids across all SDKs and must
/// never drift — they mirror `internal/tsid` in the Go platform exactly.
public enum EntityType {
    CLIENT("clt"),
    PRINCIPAL("prn"),
    APPLICATION("app"),
    SERVICE_ACCOUNT("sac"),
    ROLE("rol"),
    PERMISSION("prm"),
    OAUTH_CLIENT("oac"),
    AUTH_CODE("acd"),
    LOGIN_ATTEMPT("lat"),
    CLIENT_AUTH_CONFIG("cac"),
    APP_CLIENT_CONFIG("apc"),
    IDP_ROLE_MAPPING("irm"),
    CORS_ORIGIN("cor"),
    ANCHOR_DOMAIN("anc"),
    IDENTITY_PROVIDER("idp"),
    EMAIL_DOMAIN_MAPPING("edm"),
    CLIENT_ACCESS_GRANT("gnt"),
    EVENT_TYPE("evt"),
    EVENT("evn"),
    EVENT_READ("evr"),
    CONNECTION("con"),
    SUBSCRIPTION("sub"),
    DISPATCH_POOL("dpl"),
    DISPATCH_JOB("djb"),
    DISPATCH_JOB_READ("djr"),
    SCHEMA("sch"),
    AUDIT_LOG("aud"),
    PLATFORM_CONFIG("pcf"),
    CONFIG_ACCESS("cfa"),
    PASSWORD_RESET_TOKEN("prt"),
    WEBAUTHN_CREDENTIAL("pkc"),
    SCHEDULED_JOB("sjb"),
    SCHEDULED_JOB_INSTANCE("sji"),
    SCHEDULED_JOB_INSTANCE_LOG("sjl"),
    APPLICATION_OPENAPI_SPEC("oas"),
    PROCESS("prc"),
    OAUTH_ACCESS_TOKEN("oat"),
    OAUTH_REFRESH_TOKEN("ort"),
    MFA_METHOD("mfm"),
    MFA_RECOVERY_CODE("mrc"),
    MFA_EMAIL_PIN("mep"),
    MFA_TRUSTED_DEVICE("mtd"),
    RESET_APPROVAL_REQUEST("rar"),
    PORTAL_USER("ptu"),
    PORTAL_APP("pta"),
    APP_DOC("doc"),
    FUNCTION("fnc"),
    FUNCTION_VERSION("fnv"),
    FUNCTION_DOMAIN("fnd"),
    FUNCTION_ROUTE("fnr");

    private final String prefix;

    EntityType(String prefix) {
        this.prefix = prefix;
    }

    /// The three-letter prefix, e.g. `aud`.
    public String prefix() {
        return prefix;
    }

    /// A new typed id: `{prefix}_{13-char raw}`, e.g. `aud_0HZXEQ5Y8JY5Z`.
    public String generate() {
        return Tsid.generateWithPrefix(prefix);
    }
}
