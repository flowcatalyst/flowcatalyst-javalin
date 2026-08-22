package io.flowcatalyst.platform.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The JSON-Schema map for the platform event-type catalogue —
/// `internal/platform/seed/event_schemas.go`. Keys match event-type codes
/// exactly. The DSL helpers (`obj` / `reqStr` / `optStr` / …) build the
/// schemas consumed cross-language — keep the emitted shapes stable for
/// existing consumers.
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
        m.put("platform:iam:user:created", obj(
                reqStr("principalId"),
                reqStr("email"),
                reqStr("emailDomain"),
                reqStr("name"),
                reqStr("scope"),
                optStr("clientId"),
                reqBool("isAnchorUser")));
        m.put("platform:iam:user:updated", obj(
                reqStr("principalId"),
                optStr("name"),
                optStr("email")));
        m.put("platform:iam:user:activated", obj(reqStr("principalId")));
        m.put("platform:iam:user:deactivated", obj(reqStr("principalId"), optStr("reason")));
        m.put("platform:iam:user:deleted", obj(reqStr("principalId")));
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
        m.put("platform:iam:user:password-reset-requested", obj(reqStr("principalId"), reqStr("email")));
        m.put("platform:iam:user:password-reset-completed", obj(reqStr("principalId"), reqStr("email")));

        // ── platform:iam:principals (sync — plural aggregate) ───────────────
        m.put("platform:iam:principals:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"),
                reqU32("updated"),
                reqU32("deactivated"),
                reqStrArray("syncedEmails")));

        // ── platform:iam:serviceaccount ─────────────────────────────────────
        m.put("platform:iam:serviceaccount:created", obj(
                reqStr("serviceAccountId"), reqStr("code"), reqStr("name"),
                optStr("applicationId"), reqStrArray("clientIds")));
        m.put("platform:iam:serviceaccount:updated", obj(
                reqStr("serviceAccountId"), optStr("name"), optStr("description"),
                reqStrArray("clientIdsAdded"), reqStrArray("clientIdsRemoved")));
        m.put("platform:iam:serviceaccount:deleted", obj(reqStr("serviceAccountId"), reqStr("code")));
        m.put("platform:iam:serviceaccount:roles-assigned", obj(
                reqStr("serviceAccountId"),
                reqStrArray("rolesAdded"),
                reqStrArray("rolesRemoved")));
        m.put("platform:iam:serviceaccount:token-regenerated", obj(
                reqStr("serviceAccountId"), reqStr("code")));
        m.put("platform:iam:serviceaccount:secret-regenerated", obj(
                reqStr("serviceAccountId"), reqStr("code")));

        // ── platform:iam:client ─────────────────────────────────────────────
        m.put("platform:iam:client:created", obj(
                reqStr("clientId"), reqStr("name"), reqStr("identifier"), optStr("description")));
        m.put("platform:iam:client:updated", obj(
                reqStr("clientId"), optStr("name"), optStr("description")));
        m.put("platform:iam:client:activated", obj(reqStr("clientId"), reqStr("previousStatus")));
        m.put("platform:iam:client:suspended", obj(reqStr("clientId"), reqStr("reason")));
        m.put("platform:iam:client:deleted", obj(reqStr("clientId"), reqStr("name"), reqStr("identifier")));
        m.put("platform:iam:client:note-added", obj(
                reqStr("clientId"), reqStr("category"), reqStr("text"), reqStr("author")));

        // ── platform:iam:role ───────────────────────────────────────────────
        m.put("platform:iam:role:created", obj(
                reqStr("roleId"), reqStr("code"), reqStr("displayName"),
                reqStr("applicationCode"), reqStrArray("permissions")));
        m.put("platform:iam:role:updated", obj(
                reqStr("roleId"), optStr("displayName"), optStr("description"),
                reqStrArray("permissionsAdded"), reqStrArray("permissionsRemoved")));
        m.put("platform:iam:role:deleted", obj(reqStr("roleId"), reqStr("code")));
        m.put("platform:iam:roles:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedNames")));

        // ── platform:iam:application ────────────────────────────────────────
        m.put("platform:iam:application:created", obj(
                reqStr("applicationId"), reqStr("code"), reqStr("name"), reqStr("applicationType")));
        m.put("platform:iam:application:updated", obj(
                reqStr("applicationId"), optStr("name"), optStr("description")));
        m.put("platform:iam:application:activated", obj(reqStr("applicationId"), reqStr("code")));
        m.put("platform:iam:application:deactivated", obj(reqStr("applicationId"), reqStr("code")));
        m.put("platform:iam:application:deleted", obj(
                reqStr("applicationId"), reqStr("code"), reqStr("name")));
        m.put("platform:iam:application:service-account-provisioned", obj(
                reqStr("applicationId"), reqStr("applicationCode"),
                reqStr("serviceAccountId"), reqStr("serviceAccountCode")));
        m.put("platform:iam:application:enabled-for-client", obj(
                reqStr("applicationId"), reqStr("clientId"), reqStr("configId")));
        m.put("platform:iam:application:disabled-for-client", obj(
                reqStr("applicationId"), reqStr("clientId"), reqStr("configId")));

        // ── platform:iam:anchor-domain ──────────────────────────────────────
        m.put("platform:iam:anchor-domain:created", obj(reqStr("anchorDomainId"), reqStr("domain")));
        m.put("platform:iam:anchor-domain:deleted", obj(reqStr("anchorDomainId"), reqStr("domain")));

        // ── platform:iam:auth-config ────────────────────────────────────────
        m.put("platform:iam:auth-config:created", obj(
                reqStr("authConfigId"), reqStr("emailDomain"), reqStr("configType")));
        m.put("platform:iam:auth-config:updated", obj(reqStr("authConfigId"), reqStr("emailDomain")));
        m.put("platform:iam:auth-config:deleted", obj(reqStr("authConfigId"), reqStr("emailDomain")));

        // ── platform:admin:cors ─────────────────────────────────────────────
        m.put("platform:admin:cors:origin-added", obj(reqStr("originId"), reqStr("origin")));
        m.put("platform:admin:cors:origin-deleted", obj(reqStr("originId"), reqStr("origin")));

        // ── platform:admin:idp ──────────────────────────────────────────────
        m.put("platform:admin:idp:created", obj(
                reqStr("idpId"), reqStr("code"), reqStr("name"), reqStr("idpType")));
        m.put("platform:admin:idp:updated", obj(reqStr("idpId"), optStr("name")));
        m.put("platform:admin:idp:deleted", obj(reqStr("idpId"), reqStr("code")));

        // ── platform:admin:edm ──────────────────────────────────────────────
        m.put("platform:admin:edm:created", obj(
                reqStr("mappingId"), reqStr("emailDomain"),
                reqStr("identityProviderId"), reqStr("scopeType")));
        m.put("platform:admin:edm:updated", obj(reqStr("mappingId"), reqStr("emailDomain")));
        m.put("platform:admin:edm:deleted", obj(reqStr("mappingId"), reqStr("emailDomain")));

        // ── platform:admin:eventtype ────────────────────────────────────────
        m.put("platform:admin:eventtype:created", obj(
                reqStr("eventTypeId"), reqStr("code"), reqStr("name"), optStr("description"),
                reqStr("application"), reqStr("subdomain"), reqStr("aggregate"),
                reqStr("eventName"), optStr("clientId")));
        m.put("platform:admin:eventtype:updated", obj(
                reqStr("eventTypeId"), optStr("name"), optStr("description")));
        m.put("platform:admin:eventtype:archived", obj(reqStr("eventTypeId"), reqStr("code")));
        m.put("platform:admin:eventtype:deleted", obj(reqStr("eventTypeId"), reqStr("code")));
        m.put("platform:admin:eventtype:schema-added", obj(
                reqStr("eventTypeId"), reqStr("version"), reqStr("mimeType"), reqStr("schemaType")));
        m.put("platform:admin:eventtype:schema-finalised", obj(
                reqStr("eventTypeId"), reqStr("version"), optStr("deprecatedVersion")));
        m.put("platform:admin:eventtype:schema-deprecated", obj(reqStr("eventTypeId"), reqStr("version")));
        m.put("platform:admin:eventtypes:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedCodes")));

        // ── platform:admin:connection ───────────────────────────────────────
        m.put("platform:admin:connection:created", obj(
                reqStr("connectionId"), reqStr("code"), reqStr("name"), reqStr("endpoint"),
                reqStr("serviceAccountId"), optStr("clientId")));
        m.put("platform:admin:connection:updated", obj(
                reqStr("connectionId"), reqStr("code"),
                optStr("name"), optStr("endpoint"), optStr("status")));
        m.put("platform:admin:connection:deleted", obj(
                reqStr("connectionId"), reqStr("code"), optStr("clientId")));

        // ── platform:admin:dispatch-pool ────────────────────────────────────
        m.put("platform:admin:dispatch-pool:created", obj(
                reqStr("dispatchPoolId"), reqStr("code"), reqStr("name"), optStr("clientId")));
        m.put("platform:admin:dispatch-pool:updated", obj(
                reqStr("dispatchPoolId"), optStr("name"),
                optU32("rateLimit"), optU32("concurrency")));
        m.put("platform:admin:dispatch-pool:archived", obj(reqStr("dispatchPoolId"), reqStr("code")));
        m.put("platform:admin:dispatch-pool:deleted", obj(reqStr("dispatchPoolId"), reqStr("code")));
        m.put("platform:admin:dispatch-pools:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedCodes")));

        // ── platform:admin:subscription ─────────────────────────────────────
        m.put("platform:admin:subscription:created", obj(
                reqStr("subscriptionId"), reqStr("code"), reqStr("name"),
                reqStr("connectionId"), reqStrArray("eventTypes"), optStr("clientId")));
        m.put("platform:admin:subscription:updated", obj(
                reqStr("subscriptionId"), optStr("name"),
                reqStrArray("eventTypesAdded"), reqStrArray("eventTypesRemoved")));
        m.put("platform:admin:subscription:paused", obj(reqStr("subscriptionId"), reqStr("code")));
        m.put("platform:admin:subscription:resumed", obj(reqStr("subscriptionId"), reqStr("code")));
        m.put("platform:admin:subscription:deleted", obj(reqStr("subscriptionId"), reqStr("code")));
        m.put("platform:admin:subscription:synced", obj(
                reqStr("applicationCode"),
                reqU32("created"), reqU32("updated"), reqU32("deleted"),
                reqStrArray("syncedCodes")));

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

    static Prop optU32(String name) {
        ObjectNode n = nullable("integer");
        n.put("minimum", 0);
        return new Prop(name, n, false);
    }

    static Prop reqStrArray(String name) {
        return new Prop(name, stringArray(), true);
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
