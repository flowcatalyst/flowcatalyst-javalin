package io.flowcatalyst.platform.seed;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The JSON-Schema map for the platform event-type catalogue —
/// `internal/platform/seed/event_schemas.go`. Keys match event-type codes
/// exactly. Each schema describes the data its operation emits (its `data()`
/// record through `Json.MAPPER`, which drops nulls): a field always sent is
/// required, one that can be null optional (`EmittedEventSchemaTest` checks
/// every code both ways). The DSL helpers (`obj` / `reqStr` / `optStr` / …)
/// build the schemas consumed cross-language. A schema change reaches an
/// existing database only through a migration (the seeder never replaces an
/// attached 1.0); see V27 (Go 072), which corrected 28 older schemas.
///
/// The schemas land in `msg_event_type_spec_versions.schema_content`
/// (`jsonb`), so key order is irrelevant; the *set* of keys and values is what
/// the Go shapes pin.
public final class PlatformEventSchemas {

    private static final String DRAFT_07 = "http://json-schema.org/draft-07/schema#";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private static final Map<String, JsonNode> ALL = build();

    private PlatformEventSchemas() {
    }

    /// Code → schema, in the order Go declares them.
    public static Map<String, JsonNode> all() {
        return ALL;
    }

    private static Map<String, JsonNode> build() {
        var m = new LinkedHashMap<String, JsonNode>();

        // ── platform:iam:user ───────────────────────────────────────────────
        m.put("platform:iam:user:created", obj(reqStr("principalId"), reqStr("email")));
        m.put("platform:iam:user:updated", obj(reqStr("principalId"), reqStr("name")));
        m.put("platform:iam:user:activated", obj(reqStr("principalId")));
        m.put("platform:iam:user:deactivated", obj(reqStr("principalId")));
        m.put("platform:iam:user:deleted", obj(reqStr("principalId"), reqStr("email")));
        m.put("platform:iam:user:roles-assigned", obj(
                reqStr("principalId"),
                reqStrArray("roles"),
                reqStrArray("added"),
                reqStrArray("removed")));
        m.put("platform:iam:user:application-access-assigned", obj(
                reqStr("userId"),
                reqStrArray("applicationIds"),
                reqStrArray("added"),
                reqStrArray("removed")));
        m.put("platform:iam:user:client-access-granted", obj(
                reqStr("principalId"), reqStr("clientId")));
        m.put("platform:iam:user:client-access-revoked", obj(
                reqStr("principalId"), reqStr("clientId")));
        // logged-in carries a richer payload — federatedClaims oneOf, etc.
        m.put("platform:iam:user:logged-in", loggedIn());
        m.put("platform:iam:user:password-reset-completed", obj(reqStr("principalId")));

        // ── platform:iam:principals (sync — plural aggregate) ───────────────
        m.put("platform:iam:principals:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"),
                reqU32("updated"),
                reqU32("deactivated"),
                reqStrArray("syncedEmails")));

        // ── platform:iam:serviceaccount ─────────────────────────────────────
        m.put("platform:iam:serviceaccount:created", obj(
                reqStr("serviceAccountId"), reqStr("code"), reqStr("name")));
        m.put("platform:iam:serviceaccount:updated", obj(reqStr("serviceAccountId"), reqStr("name")));
        m.put("platform:iam:serviceaccount:deleted", obj(reqStr("serviceAccountId"), reqStr("code")));
        m.put("platform:iam:serviceaccount:roles-assigned", obj(
                reqStr("serviceAccountId"),
                reqStrArray("rolesAdded"),
                reqStrArray("rolesRemoved")));
        m.put("platform:iam:serviceaccount:token-regenerated", obj(
                reqStr("serviceAccountId"), reqStr("code")));
        m.put("platform:iam:serviceaccount:secret-regenerated", obj(
                reqStr("serviceAccountId"), reqStr("code")));

        // ── platform:admin:client ───────────────────────────────────────────
        // The data each client operation emits (client/operations/ClientEvents).
        m.put("platform:admin:client:created", obj(reqStr("clientId"), reqStr("name"), reqStr("identifier")));
        m.put("platform:admin:client:updated", obj(reqStr("clientId"), reqStr("name")));
        m.put("platform:admin:client:activated", obj(reqStr("clientId")));
        m.put("platform:admin:client:suspended", obj(reqStr("clientId"), reqStr("reason")));
        m.put("platform:admin:client:deactivated", obj(reqStr("clientId"), optStr("reason")));
        m.put("platform:admin:client:deleted", obj(reqStr("clientId"), reqStr("identifier")));
        m.put("platform:admin:client:note-added", obj(reqStr("clientId"), reqStr("category"), reqStr("text")));

        // ── platform:iam:client ─────────────────────────────────────────────
        // Go writes an empty list as null; Java writes [], which the schema
        // also admits.
        m.put("platform:iam:client:applications-updated", obj(
                reqStr("clientId"), reqNullableStrArray("enabledApplicationIds"),
                reqNullableStrArray("enabledAdded"), reqNullableStrArray("disabledRemoved")));

        // ── platform:iam:application ────────────────────────────────────────
        m.put("platform:iam:application:created", obj(
                reqStr("applicationId"), reqStr("code"), reqStr("name")));
        m.put("platform:iam:application:updated", obj(reqStr("applicationId"), reqStr("name")));
        m.put("platform:iam:application:activated", obj(reqStr("applicationId")));
        m.put("platform:iam:application:deactivated", obj(reqStr("applicationId")));
        m.put("platform:iam:application:deleted", obj(reqStr("applicationId"), reqStr("code")));
        m.put("platform:iam:application:service-account-provisioned", obj(
                reqStr("applicationId"), reqStr("applicationCode"),
                reqStr("serviceAccountId"), reqStr("serviceAccountCode")));
        m.put("platform:iam:application:enabled-for-client", obj(
                reqStr("applicationId"), reqStr("clientId"), reqStr("configId")));
        m.put("platform:iam:application:disabled-for-client", obj(
                reqStr("applicationId"), reqStr("clientId"), reqStr("configId")));

        // ── platform:admin:cors ─────────────────────────────────────────────
        m.put("platform:admin:cors:origin-added", obj(reqStr("originId"), reqStr("origin")));
        m.put("platform:admin:cors:origin-deleted", obj(reqStr("originId"), reqStr("origin")));

        // ── platform:admin:eventtype ────────────────────────────────────────
        m.put("platform:admin:eventtype:created", obj(
                reqStr("eventTypeId"), reqStr("code"), reqStr("name"), optStr("description"),
                reqStr("application"), reqStr("subdomain"), reqStr("aggregate"),
                reqStr("eventName"), optStr("clientId")));
        m.put("platform:admin:eventtype:updated", obj(
                reqStr("eventTypeId"), reqStr("name"), optStr("description")));
        m.put("platform:admin:eventtype:archived", obj(reqStr("eventTypeId"), reqStr("code")));
        m.put("platform:admin:eventtype:deleted", obj(reqStr("eventTypeId"), reqStr("code")));
        m.put("platform:admin:eventtype:schema-added", obj(reqStr("eventTypeId"), reqStr("specVersion")));
        m.put("platform:admin:eventtype:schema-finalised", obj(
                reqStr("eventTypeId"), reqStr("specVersion"), optStr("deprecatedVersion")));
        m.put("platform:admin:eventtype:schema-deprecated", obj(reqStr("eventTypeId"), reqStr("specVersion")));
        m.put("platform:admin:eventtypes:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedCodes")));

        // ── platform:admin:connection ───────────────────────────────────────
        m.put("platform:admin:connection:created", obj(
                reqStr("connectionId"), reqStr("code"), reqStr("name")));
        m.put("platform:admin:connection:updated", obj(reqStr("connectionId"), reqStr("name")));
        m.put("platform:admin:connection:deleted", obj(reqStr("connectionId"), reqStr("code")));
        m.put("platform:admin:connection:synced", obj(
                reqStr("applicationCode"), optStr("clientId"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedCodes")));

        // ── platform:admin:dispatch-pool ────────────────────────────────────
        m.put("platform:admin:dispatch-pool:created", obj(reqStr("poolId"), reqStr("code"), reqStr("name")));
        m.put("platform:admin:dispatch-pool:updated", obj(reqStr("poolId"), reqStr("name")));
        m.put("platform:admin:dispatch-pool:archived", obj(reqStr("poolId"), reqStr("code")));
        m.put("platform:admin:dispatch-pool:deleted", obj(reqStr("poolId"), reqStr("code")));
        m.put("platform:admin:dispatch-pools:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedCodes")));

        // ── platform:admin:subscription ─────────────────────────────────────
        m.put("platform:admin:subscription:created", obj(
                reqStr("subscriptionId"), reqStr("code"), reqStr("name")));
        m.put("platform:admin:subscription:updated", obj(reqStr("subscriptionId"), reqStr("name")));
        m.put("platform:admin:subscription:paused", obj(reqStr("subscriptionId")));
        m.put("platform:admin:subscription:resumed", obj(reqStr("subscriptionId")));
        m.put("platform:admin:subscription:deleted", obj(reqStr("subscriptionId"), reqStr("code")));
        m.put("platform:admin:subscription:synced", obj(
                reqStr("applicationCode"), optStr("clientId"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedCodes")));

        // The schemas below were added for F9. Where Go emits the same code
        // with the same data the schema is equal to Go's (8ddaed4); the two
        // codes whose Java data differs, and the Java-only codes, are marked.

        // ── platform:iam:user (developer credentials) ───────────────────────
        m.put("platform:iam:user:developer-credential-set", obj(reqStr("userId")));
        m.put("platform:iam:user:developer-credential-revoked", obj(reqStr("userId")));

        // ── platform:iam:serviceaccount ─────────────────────────────────────
        m.put("platform:iam:serviceaccount:deactivated", obj(reqStr("serviceAccountId")));
        // Java only. servicePrincipalId is absent when the account has no principal.
        m.put("platform:iam:serviceaccount:token-minted", obj(
                reqStr("serviceAccountId"), optStr("servicePrincipalId"),
                reqU32("expiresInSeconds"), reqStrArray("permissions")));

        // ── platform:iam:reset-approval (Java only) ─────────────────────────
        m.put("platform:iam:reset-approval:queued", obj(reqStr("requestId"), reqStr("userId"), reqStr("clientId")));
        m.put("platform:iam:reset-approval:decided", obj(
                reqStr("requestId"), reqStr("userId"), reqStr("status"), reqStr("decidedBy")));

        // ── platform:admin:dispatch-pool ────────────────────────────────────
        m.put("platform:admin:dispatch-pool:suspended", obj(reqStr("poolId"), reqStr("code")));
        m.put("platform:admin:dispatch-pool:activated", obj(reqStr("poolId"), reqStr("code")));

        // ── platform:admin:anchor-domain ────────────────────────────────────
        m.put("platform:admin:anchor-domain:created", obj(reqStr("anchorDomainId"), reqStr("domain")));
        m.put("platform:admin:anchor-domain:updated", obj(reqStr("anchorDomainId"), reqStr("domain")));
        m.put("platform:admin:anchor-domain:deleted", obj(reqStr("anchorDomainId"), reqStr("domain")));

        // ── platform:admin:auth-config ──────────────────────────────────────
        m.put("platform:admin:auth-config:created", obj(reqStr("authConfigId"), reqStr("emailDomain")));
        m.put("platform:admin:auth-config:updated", obj(reqStr("authConfigId"), reqStr("emailDomain")));
        m.put("platform:admin:auth-config:deleted", obj(reqStr("authConfigId"), reqStr("emailDomain")));

        // ── platform:admin:oauth-client ─────────────────────────────────────
        m.put("platform:admin:oauth-client:created", obj(
                reqStr("oauthClientId"), reqStr("clientId"), reqStr("clientName")));
        m.put("platform:admin:oauth-client:updated", obj(reqStr("oauthClientId"), reqStr("clientName")));
        m.put("platform:admin:oauth-client:activated", obj(reqStr("oauthClientId")));
        m.put("platform:admin:oauth-client:deactivated", obj(reqStr("oauthClientId")));
        m.put("platform:admin:oauth-client:deleted", obj(reqStr("oauthClientId"), reqStr("clientId")));
        m.put("platform:admin:oauth-client:secret-rotated", obj(
                reqStr("oauthClientId"), optDateTime("previousSecretExpiresAt")));
        // Differs from Go: Java also says whether a previous secret was dropped.
        m.put("platform:admin:oauth-client:previous-secret-revoked", obj(
                reqStr("oauthClientId"), reqBool("dropped")));

        // ── platform:admin:identity-provider ────────────────────────────────
        m.put("platform:admin:identity-provider:created", obj(reqStr("identityProviderId"), reqStr("code")));
        m.put("platform:admin:identity-provider:updated", obj(reqStr("identityProviderId"), reqStr("code")));
        m.put("platform:admin:identity-provider:deleted", obj(reqStr("identityProviderId"), reqStr("code")));

        // ── platform:admin:email-domain-mapping ─────────────────────────────
        m.put("platform:admin:email-domain-mapping:created", obj(reqStr("mappingId"), reqStr("emailDomain")));
        m.put("platform:admin:email-domain-mapping:updated", obj(reqStr("mappingId"), reqStr("emailDomain")));
        m.put("platform:admin:email-domain-mapping:provider-changed", obj(
                reqStr("mappingId"), reqStr("emailDomain"),
                reqStr("fromIdentityProviderId"), reqStr("toIdentityProviderId")));
        m.put("platform:admin:email-domain-mapping:deleted", obj(reqStr("mappingId"), reqStr("emailDomain")));

        // ── platform:admin:idp-role-mapping ─────────────────────────────────
        m.put("platform:admin:idp-role-mapping:created", obj(
                reqStr("mappingId"), reqStr("idpType"), reqStr("idpRoleName"), reqStr("platformRoleName")));
        // Differs from Go (mappingId, idpRoleName): Java's delete carries the
        // same fields as the create.
        m.put("platform:admin:idp-role-mapping:deleted", obj(
                reqStr("mappingId"), reqStr("idpType"), reqStr("idpRoleName"), reqStr("platformRoleName")));

        // ── platform:admin:role ─────────────────────────────────────────────
        m.put("platform:admin:role:created", obj(reqStr("roleId"), reqStr("name")));
        m.put("platform:admin:role:updated", obj(reqStr("roleId"), reqStr("name")));
        m.put("platform:admin:role:deleted", obj(reqStr("roleId"), reqStr("name")));
        m.put("platform:admin:role:permission-granted", obj(
                reqStr("roleId"), reqStr("roleName"), reqStr("permission")));
        m.put("platform:admin:role:permission-revoked", obj(
                reqStr("roleId"), reqStr("roleName"), reqStr("permission")));
        // The application-scoped sync sets applicationCode and syncedCodes; the
        // platform-catalogue sync leaves both out.
        m.put("platform:admin:roles:synced", obj(
                reqU32("created"), reqU32("updated"), reqU32("removed"), reqU32("total"),
                optStr("applicationCode"), optStrArray("syncedCodes")));

        // ── platform:admin:passkey (Go's and Rust's codes and data) ─────────
        m.put("platform:admin:passkey:registered", obj(reqStr("credentialId"), reqStr("userId"), optStr("name")));
        m.put("platform:admin:passkey:authenticated", obj(reqStr("credentialId"), reqStr("userId")));
        m.put("platform:admin:passkey:revoked", obj(reqStr("credentialId"), reqStr("userId")));

        // ── platform:admin:platform-config ──────────────────────────────────
        m.put("platform:admin:platform-config:property-set", obj(
                reqStr("configId"), reqStr("applicationCode"), reqStr("section"), reqStr("property")));

        // ── platform:admin:process ──────────────────────────────────────────
        m.put("platform:admin:process:created", obj(reqStr("processId"), reqStr("code"), reqStr("name")));
        m.put("platform:admin:process:updated", obj(reqStr("processId"), reqStr("name")));
        m.put("platform:admin:process:archived", obj(reqStr("processId"), reqStr("code")));
        m.put("platform:admin:process:deleted", obj(reqStr("processId"), reqStr("code")));
        m.put("platform:admin:processes:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedCodes")));

        // ── platform:admin:scheduled-job ────────────────────────────────────
        for (String ev : List.of("created", "updated", "paused", "resumed", "archived", "deleted")) {
            m.put("platform:admin:scheduled-job:" + ev, obj(reqStr("scheduledJobId"), reqStr("code")));
        }
        m.put("platform:admin:scheduled-job:fired-manually", obj(
                reqStr("scheduledJobId"), reqStr("code"), reqStr("instanceId")));
        // Each list holds scheduled-job ids. Go writes an empty one as null;
        // Java writes [], which the schema also admits.
        m.put("platform:admin:scheduledjobs:synced", obj(
                reqStr("applicationCode"),
                reqNullableStrArray("created"), reqNullableStrArray("updated"), reqNullableStrArray("archived")));

        // ── platform:admin:dispatchjob (Java only) ──────────────────────────
        m.put("platform:admin:dispatchjob:requeued", obj(
                reqStr("dispatchJobId"), reqStr("code"), reqStr("previousStatus"),
                optStr("messageGroup"), optStr("clientId")));
        m.put("platform:admin:dispatchjobs:requeued", obj(
                reqStr("batchId"), reqStrArray("dispatchJobIds"), reqU32("requeued")));

        // ── platform:admin:audit-log (Java only) ────────────────────────────
        m.put("platform:admin:audit-log:redacted", obj(reqU32("scanned"), reqU32("redacted")));

        // ── platform:messaging:dispatch-job ─────────────────────────────────
        m.put("platform:messaging:dispatch-job:cancelled", obj(reqStr("dispatchJobId")));
        m.put("platform:messaging:dispatch-job:completed", obj(reqStr("dispatchJobId")));

        // ── platform:developer:application-openapi ──────────────────────────
        m.put("platform:developer:application-openapi:synced", obj(
                reqStr("applicationId"), reqStr("applicationCode"), reqStr("specId"),
                reqStr("version"), reqStr("specHash"), optStr("archivedPriorVersion"),
                reqBool("hasBreaking"), reqBool("unchanged")));

        // ── platform:portal ─────────────────────────────────────────────────
        for (String ev : List.of("created", "updated", "deleted")) {
            m.put("platform:portal:app:" + ev, obj(
                    reqStr("portalAppId"), reqStr("clientId"), reqStr("code"), reqStr("name")));
        }
        m.put("platform:portal:identity:ensured", obj(
                reqStr("identityId"), reqStr("clientId"), reqStr("email"), reqBool("created"),
                reqStr("source"), optStr("portalAppId"), optStr("portalAppCode")));
        m.put("platform:portal:identity:status-set", obj(reqStr("identityId"), reqStr("clientId"), reqStr("status")));
        m.put("platform:portal:identity:deleted", obj(reqStr("identityId"), reqStr("clientId"), reqStr("email")));
        m.put("platform:portal:identity:app-granted", obj(
                reqStr("identityId"), reqStr("clientId"), reqStr("portalAppId"), reqStr("portalAppCode"),
                reqStr("source")));
        m.put("platform:portal:identity:app-revoked", obj(
                reqStr("identityId"), reqStr("clientId"), reqStr("portalAppId"), reqStr("portalAppCode")));

        // ── platform:function (Java only; FunctionEvents) ───────────────────
        m.put("platform:function:function:created", obj(
                reqStr("functionId"), reqStr("address"), reqStr("applicationId"), optStr("clientId"),
                reqStr("runtime")));
        m.put("platform:function:function:updated", obj(
                reqStr("functionId"), reqStr("address"), optStr("description"), reqStr("status")));
        m.put("platform:function:function:deleted", obj(reqStr("functionId"), reqStr("address")));
        // The signer pair is absent together (a version published unsigned).
        m.put("platform:function:version:published", obj(
                reqStr("functionId"), reqStr("address"), reqStr("versionId"), reqU32("version"),
                reqStr("digest"), reqStr("pool"), optStr("signerIssuer"), optStr("signerSubject")));
        m.put("platform:function:version:ready", obj(
                reqStr("functionId"), reqStr("address"), reqStr("versionId"), reqU32("version"),
                reqStr("hostId")));
        m.put("platform:function:version:retired", obj(
                reqStr("functionId"), reqStr("address"), reqStr("versionId"), reqU32("version")));
        // previousVersionId is absent on a first promotion.
        m.put("platform:function:alias:changed", obj(
                reqStr("functionId"), reqStr("address"), reqStr("alias"), reqStr("versionId"),
                reqU32("version"), optStr("previousVersionId")));
        m.put("platform:function:alias:removed", obj(
                reqStr("functionId"), reqStr("address"), reqStr("alias"), reqStr("versionId"),
                reqU32("version")));
        // Keys only, never values.
        m.put("platform:function:config:updated", obj(
                reqStr("functionId"), reqStr("address"), reqStrArray("keys")));
        m.put("platform:function:secret:set", obj(reqStr("functionId"), reqStr("address"), reqStr("key")));
        m.put("platform:function:secret:deleted", obj(reqStr("functionId"), reqStr("address"), reqStr("key")));
        // owner is "platform" or the owning client's id; the count, never the signers.
        m.put("platform:function:policy:updated", obj(reqStr("owner"), reqU32("signerCount")));
        m.put("platform:function:domain:claimed", obj(reqStr("domainId"), reqStr("hostname"), reqStr("owner")));
        m.put("platform:function:domain:released", obj(reqStr("domainId"), reqStr("hostname"), reqStr("owner")));

        return Collections.unmodifiableMap(m);
    }

    /// The hand-written `platform:iam:user:logged-in` schema (Go's `mustRaw`
    /// literal): `flowcatalystClaims` object, `federatedClaims` oneOf
    /// object/null, `additionalProperties: true`.
    private static JsonNode loggedIn() {
        ObjectNode claims = F.objectNode();
        claims.put("type", "object");
        ObjectNode claimProps = claims.putObject("properties");
        claimProps.set("email", type("string"));
        claimProps.set("type", type("string"));
        claimProps.set("roles", stringArray());
        claimProps.set("clients", stringArray());
        claimProps.set("applications", stringArray());
        claims.set("required", strings("email", "type", "roles", "clients", "applications"));

        ObjectNode federatedObject = F.objectNode();
        federatedObject.put("type", "object");
        ObjectNode federatedProps = federatedObject.putObject("properties");
        federatedProps.set("accessToken", type("object"));
        federatedProps.set("idToken", type("object"));
        federatedObject.set("required", strings("accessToken", "idToken"));
        ObjectNode federated = F.objectNode();
        federated.putArray("oneOf").add(federatedObject).add(type("null"));

        ObjectNode loginMethod = type("string");
        loginMethod.set("enum", strings("INTERNAL", "OIDC"));

        ObjectNode root = F.objectNode();
        root.put("$schema", DRAFT_07);
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.set("userId", type("string"));
        props.set("email", type("string"));
        props.set("loginMethod", loginMethod);
        props.set("identityProviderCode", nullable("string"));
        props.set("flowcatalystClaims", claims);
        props.set("federatedClaims", federated);
        root.set("required", strings("userId", "email", "loginMethod", "flowcatalystClaims"));
        root.put("additionalProperties", true);
        return root;
    }

    // ── DSL — JSON-Schema builder helpers ────────────────────────────────

    /// One property of an `obj(...)` schema: name, its schema, required?
    record Prop(String name, JsonNode schema, boolean required) {
    }

    static JsonNode obj(Prop... props) {
        ObjectNode properties = F.objectNode();
        ArrayNode required = F.arrayNode();
        for (Prop p : props) {
            properties.set(p.name(), p.schema());
            if (p.required()) {
                required.add(p.name());
            }
        }
        ObjectNode root = F.objectNode();
        root.put("$schema", DRAFT_07);
        root.put("type", "object");
        root.set("properties", properties);
        root.set("required", required);
        root.put("additionalProperties", false);
        return root;
    }

    static Prop reqStr(String name) {
        return new Prop(name, type("string"), true);
    }

    static Prop optStr(String name) {
        return new Prop(name, nullable("string"), false);
    }

    static Prop reqBool(String name) {
        return new Prop(name, type("boolean"), true);
    }

    static Prop reqU32(String name) {
        ObjectNode n = type("integer");
        n.put("minimum", 0);
        return new Prop(name, n, true);
    }

    static Prop reqStrArray(String name) {
        return new Prop(name, stringArray(), true);
    }

    /// A string array the producer leaves out when empty.
    static Prop optStrArray(String name) {
        return new Prop(name, stringArray(), false);
    }

    /// A string array always present, null when empty (Go's nil slice).
    static Prop reqNullableStrArray(String name) {
        ObjectNode n = nullable("array");
        n.set("items", type("string"));
        return new Prop(name, n, true);
    }

    /// An RFC 3339 timestamp the producer leaves out when unset.
    static Prop optDateTime(String name) {
        ObjectNode n = type("string");
        n.put("format", "date-time");
        return new Prop(name, n, false);
    }

    private static ObjectNode type(String t) {
        return F.objectNode().put("type", t);
    }

    private static ObjectNode nullable(String t) {
        ObjectNode n = F.objectNode();
        n.set("type", strings(t, "null"));
        return n;
    }

    private static ObjectNode stringArray() {
        ObjectNode n = type("array");
        n.set("items", type("string"));
        return n;
    }

    private static ArrayNode strings(String... values) {
        ArrayNode a = F.arrayNode();
        for (String v : List.of(values)) {
            a.add(v);
        }
        return a;
    }
}
