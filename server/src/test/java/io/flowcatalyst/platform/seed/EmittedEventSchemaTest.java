package io.flowcatalyst.platform.seed;

import io.flowcatalyst.platform.application.operations.ApplicationEvents;
import io.flowcatalyst.platform.audit.operations.AuditLogsRedacted;
import io.flowcatalyst.platform.authadmin.operations.AuthAdminEvents;
import io.flowcatalyst.platform.client.operations.ClientEvents;
import io.flowcatalyst.platform.connection.operations.ConnectionEvents;
import io.flowcatalyst.platform.cors.operations.CorsOriginEvents;
import io.flowcatalyst.platform.dispatchjob.operations.DispatchJobEvents;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents;
import io.flowcatalyst.platform.eventtype.operations.EventTypeEvents;
import io.flowcatalyst.platform.function.operations.FunctionEvents;
import io.flowcatalyst.platform.identityprovider.operations.IdentityProviderEvents;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents;
import io.flowcatalyst.platform.openapispecs.operations.OpenApiSpecEvents;
import io.flowcatalyst.platform.passkey.operations.PasskeyEvents;
import io.flowcatalyst.platform.platformconfig.operations.PlatformConfigEvents;
import io.flowcatalyst.platform.portalapp.operations.PortalAppEvents;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents;
import io.flowcatalyst.platform.process.operations.ProcessEvents;
import io.flowcatalyst.platform.resetapproval.operations.ResetApprovalEvents;
import io.flowcatalyst.platform.role.operations.RoleEvents;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// The catalogued schema of every catalogued code admits the data its event
/// really emits, and makes optional exactly the fields its operation can leave
/// out. Written for the 81 codes catalogued for F9 (Go 8ddaed4; ledger
/// docs/go-mirror/2026-10-11-event-catalogue.md); extended to the older 59
/// when 28 of their schemas were corrected (Go 4c23adf, V27; ledger
/// docs/go-mirror/2026-10-12-platform-bug-fixes-3.md). Each event record is
/// built with every component set, and
/// again with its [#MAY_BE_ABSENT] components null (a list empty), its
/// `data()` serialised through [Json#MAPPER] (as `PlatformSink` writes it)
/// and checked against the schema by [#validate], which covers the subset of
/// JSON Schema the seed's DSL writes.
class EmittedEventSchemaTest {

    /// Code → the record that emits it.
    private static final Map<String, Class<? extends DomainEvent>> EMITTERS = emitters();

    /// Code → the data fields its operation can leave out (a null value, which
    /// `Json.MAPPER` drops, or an empty list mapped to null), read off the
    /// event factories and the aggregates. Exactly these are optional in the
    /// schema; every other field is required.
    private static final Map<String, Set<String>> MAY_BE_ABSENT = Map.ofEntries(
            // no principal linked to the account
            Map.entry("platform:iam:serviceaccount:token-minted", Set.of("servicePrincipalId")),
            Map.entry("platform:iam:passkey:registered", Set.of("name")),
            // no previous secret kept
            Map.entry("platform:admin:oauth-client:secret-rotated", Set.of("previousSecretExpiresAt")),
            // the platform-catalogue sync: no application, no code list
            Map.entry("platform:admin:roles:synced", Set.of("applicationCode", "syncedCodes")),
            // optional FIFO key; a platform-scoped job has no client
            Map.entry("platform:admin:dispatchjob:requeued", Set.of("messageGroup", "clientId")),
            Map.entry("platform:developer:application-openapi:synced", Set.of("archivedPriorVersion")),
            // ensured without a portal app
            Map.entry("platform:portal:identity:ensured", Set.of("portalAppId", "portalAppCode")),
            // a platform-owned function
            Map.entry("platform:function:function:created", Set.of("clientId")),
            Map.entry("platform:function:function:updated", Set.of("description")),
            // published unsigned
            Map.entry("platform:function:version:published", Set.of("signerIssuer", "signerSubject")),
            // a first promotion
            Map.entry("platform:function:alias:changed", Set.of("previousVersionId")),
            // the older codes
            Map.entry("platform:admin:client:deactivated", Set.of("reason")),
            // description is optional; clientId is never set since F2
            Map.entry("platform:admin:eventtype:created", Set.of("description", "clientId")),
            Map.entry("platform:admin:eventtype:updated", Set.of("description")),
            // the first finalised version deprecates none
            Map.entry("platform:admin:eventtype:schema-finalised", Set.of("deprecatedVersion")),
            // a sync for no client
            Map.entry("platform:admin:connection:synced", Set.of("clientId")),
            Map.entry("platform:admin:subscription:synced", Set.of("clientId")),
            // an internal login: no identity provider, no federated claims
            Map.entry("platform:iam:user:logged-in", Set.of("identityProviderCode", "federatedClaims")));

    private static Map<String, Class<? extends DomainEvent>> emitters() {
        var m = new LinkedHashMap<String, Class<? extends DomainEvent>>();
        // The codes catalogued before F9.
        m.put("platform:iam:user:created", PrincipalEvents.UserCreated.class);
        m.put("platform:iam:user:updated", PrincipalEvents.UserUpdated.class);
        m.put("platform:iam:user:activated", PrincipalEvents.UserActivated.class);
        m.put("platform:iam:user:deactivated", PrincipalEvents.UserDeactivated.class);
        m.put("platform:iam:user:deleted", PrincipalEvents.UserDeleted.class);
        m.put("platform:iam:user:roles-assigned", PrincipalEvents.RolesAssigned.class);
        m.put("platform:iam:user:application-access-assigned", PrincipalEvents.ApplicationAccessAssigned.class);
        m.put("platform:iam:user:client-access-granted", PrincipalEvents.ClientAccessGranted.class);
        m.put("platform:iam:user:client-access-revoked", PrincipalEvents.ClientAccessRevoked.class);
        m.put("platform:iam:user:logged-in", PrincipalEvents.UserLoggedIn.class);
        m.put("platform:iam:user:password-reset-completed", PrincipalEvents.UserPasswordReset.class);
        m.put("platform:iam:principals:synced", PrincipalEvents.PrincipalsSynced.class);
        m.put("platform:iam:serviceaccount:created", ServiceAccountEvents.ServiceAccountCreated.class);
        m.put("platform:iam:serviceaccount:updated", ServiceAccountEvents.ServiceAccountUpdated.class);
        m.put("platform:iam:serviceaccount:deleted", ServiceAccountEvents.ServiceAccountDeleted.class);
        m.put("platform:iam:serviceaccount:roles-assigned", ServiceAccountEvents.ServiceAccountRolesAssigned.class);
        m.put("platform:iam:serviceaccount:token-regenerated", ServiceAccountEvents.ServiceAccountTokenRegenerated.class);
        m.put("platform:iam:serviceaccount:secret-regenerated", ServiceAccountEvents.ServiceAccountSecretRegenerated.class);
        m.put("platform:iam:client:applications-updated", ApplicationEvents.ClientApplicationsUpdated.class);
        m.put("platform:iam:application:created", ApplicationEvents.ApplicationCreated.class);
        m.put("platform:iam:application:updated", ApplicationEvents.ApplicationUpdated.class);
        m.put("platform:iam:application:activated", ApplicationEvents.ApplicationActivated.class);
        m.put("platform:iam:application:deactivated", ApplicationEvents.ApplicationDeactivated.class);
        m.put("platform:iam:application:deleted", ApplicationEvents.ApplicationDeleted.class);
        m.put("platform:iam:application:service-account-provisioned", ApplicationEvents.ApplicationServiceAccountProvisioned.class);
        m.put("platform:iam:application:enabled-for-client", ApplicationEvents.ApplicationEnabledForClient.class);
        m.put("platform:iam:application:disabled-for-client", ApplicationEvents.ApplicationDisabledForClient.class);
        m.put("platform:admin:client:created", ClientEvents.ClientCreated.class);
        m.put("platform:admin:client:updated", ClientEvents.ClientUpdated.class);
        m.put("platform:admin:client:activated", ClientEvents.ClientActivated.class);
        m.put("platform:admin:client:suspended", ClientEvents.ClientSuspended.class);
        m.put("platform:admin:client:deactivated", ClientEvents.ClientDeactivated.class);
        m.put("platform:admin:client:deleted", ClientEvents.ClientDeleted.class);
        m.put("platform:admin:client:note-added", ClientEvents.ClientNoteAdded.class);
        m.put("platform:admin:cors:origin-added", CorsOriginEvents.CorsOriginAdded.class);
        m.put("platform:admin:cors:origin-deleted", CorsOriginEvents.CorsOriginDeleted.class);
        m.put("platform:admin:eventtype:created", EventTypeEvents.EventTypeCreated.class);
        m.put("platform:admin:eventtype:updated", EventTypeEvents.EventTypeUpdated.class);
        m.put("platform:admin:eventtype:archived", EventTypeEvents.EventTypeArchived.class);
        m.put("platform:admin:eventtype:deleted", EventTypeEvents.EventTypeDeleted.class);
        m.put("platform:admin:eventtype:schema-added", EventTypeEvents.EventTypeSchemaAdded.class);
        m.put("platform:admin:eventtype:schema-finalised", EventTypeEvents.EventTypeSchemaFinalised.class);
        m.put("platform:admin:eventtype:schema-deprecated", EventTypeEvents.EventTypeSchemaDeprecated.class);
        m.put("platform:admin:eventtypes:synced", EventTypeEvents.EventTypesSynced.class);
        m.put("platform:admin:connection:created", ConnectionEvents.ConnectionCreated.class);
        m.put("platform:admin:connection:updated", ConnectionEvents.ConnectionUpdated.class);
        m.put("platform:admin:connection:deleted", ConnectionEvents.ConnectionDeleted.class);
        m.put("platform:admin:connection:synced", ConnectionEvents.ConnectionsSynced.class);
        m.put("platform:admin:dispatch-pool:created", DispatchPoolEvents.DispatchPoolCreated.class);
        m.put("platform:admin:dispatch-pool:updated", DispatchPoolEvents.DispatchPoolUpdated.class);
        m.put("platform:admin:dispatch-pool:archived", DispatchPoolEvents.DispatchPoolArchived.class);
        m.put("platform:admin:dispatch-pool:deleted", DispatchPoolEvents.DispatchPoolDeleted.class);
        m.put("platform:admin:dispatch-pools:synced", DispatchPoolEvents.DispatchPoolsSynced.class);
        m.put("platform:admin:subscription:created", SubscriptionEvents.SubscriptionCreated.class);
        m.put("platform:admin:subscription:updated", SubscriptionEvents.SubscriptionUpdated.class);
        m.put("platform:admin:subscription:paused", SubscriptionEvents.SubscriptionPaused.class);
        m.put("platform:admin:subscription:resumed", SubscriptionEvents.SubscriptionResumed.class);
        m.put("platform:admin:subscription:deleted", SubscriptionEvents.SubscriptionDeleted.class);
        m.put("platform:admin:subscription:synced", SubscriptionEvents.SubscriptionsSynced.class);
        // The codes catalogued for F9.
        m.put("platform:iam:user:developer-credential-set", PrincipalEvents.DeveloperCredentialSet.class);
        m.put("platform:iam:user:developer-credential-revoked", PrincipalEvents.DeveloperCredentialRevoked.class);
        m.put("platform:iam:serviceaccount:deactivated", ServiceAccountEvents.ServiceAccountDeactivated.class);
        m.put("platform:iam:serviceaccount:token-minted", ServiceAccountEvents.ServiceAccountTokenMinted.class);
        m.put("platform:iam:passkey:registered", PasskeyEvents.PasskeyRegistered.class);
        m.put("platform:iam:passkey:authenticated", PasskeyEvents.PasskeyAuthenticated.class);
        m.put("platform:iam:passkey:revoked", PasskeyEvents.PasskeyRevoked.class);
        m.put("platform:iam:reset-approval:queued", ResetApprovalEvents.ResetApprovalQueued.class);
        m.put("platform:iam:reset-approval:decided", ResetApprovalEvents.ResetApprovalDecided.class);
        m.put("platform:admin:dispatch-pool:suspended", DispatchPoolEvents.DispatchPoolSuspended.class);
        m.put("platform:admin:dispatch-pool:activated", DispatchPoolEvents.DispatchPoolActivated.class);
        m.put("platform:admin:anchor-domain:created", AuthAdminEvents.AnchorDomainCreated.class);
        m.put("platform:admin:anchor-domain:updated", AuthAdminEvents.AnchorDomainUpdated.class);
        m.put("platform:admin:anchor-domain:deleted", AuthAdminEvents.AnchorDomainDeleted.class);
        m.put("platform:admin:auth-config:created", AuthAdminEvents.AuthConfigCreated.class);
        m.put("platform:admin:auth-config:updated", AuthAdminEvents.AuthConfigUpdated.class);
        m.put("platform:admin:auth-config:deleted", AuthAdminEvents.AuthConfigDeleted.class);
        m.put("platform:admin:oauth-client:created", OAuthClientEvents.OAuthClientCreated.class);
        m.put("platform:admin:oauth-client:updated", OAuthClientEvents.OAuthClientUpdated.class);
        m.put("platform:admin:oauth-client:activated", OAuthClientEvents.OAuthClientActivated.class);
        m.put("platform:admin:oauth-client:deactivated", OAuthClientEvents.OAuthClientDeactivated.class);
        m.put("platform:admin:oauth-client:deleted", OAuthClientEvents.OAuthClientDeleted.class);
        m.put("platform:admin:oauth-client:secret-rotated", OAuthClientEvents.OAuthClientSecretRotated.class);
        m.put("platform:admin:oauth-client:previous-secret-revoked", OAuthClientEvents.OAuthClientPreviousSecretRevoked.class);
        m.put("platform:admin:identity-provider:created", IdentityProviderEvents.IdentityProviderCreated.class);
        m.put("platform:admin:identity-provider:updated", IdentityProviderEvents.IdentityProviderUpdated.class);
        m.put("platform:admin:identity-provider:deleted", IdentityProviderEvents.IdentityProviderDeleted.class);
        m.put("platform:admin:email-domain-mapping:created", EmailDomainMappingEvents.EmailDomainMappingCreated.class);
        m.put("platform:admin:email-domain-mapping:updated", EmailDomainMappingEvents.EmailDomainMappingUpdated.class);
        m.put("platform:admin:email-domain-mapping:provider-changed", EmailDomainMappingEvents.EmailDomainMappingProviderChanged.class);
        m.put("platform:admin:email-domain-mapping:deleted", EmailDomainMappingEvents.EmailDomainMappingDeleted.class);
        m.put("platform:admin:idp-role-mapping:created", AuthAdminEvents.IdpRoleMappingCreated.class);
        m.put("platform:admin:idp-role-mapping:deleted", AuthAdminEvents.IdpRoleMappingDeleted.class);
        m.put("platform:admin:role:created", RoleEvents.RoleCreated.class);
        m.put("platform:admin:role:updated", RoleEvents.RoleUpdated.class);
        m.put("platform:admin:role:deleted", RoleEvents.RoleDeleted.class);
        m.put("platform:admin:role:permission-granted", RoleEvents.RolePermissionGranted.class);
        m.put("platform:admin:role:permission-revoked", RoleEvents.RolePermissionRevoked.class);
        m.put("platform:admin:roles:synced", RoleEvents.RolesSynced.class);
        m.put("platform:admin:platform-config:property-set", PlatformConfigEvents.PropertySet.class);
        m.put("platform:admin:process:created", ProcessEvents.ProcessCreated.class);
        m.put("platform:admin:process:updated", ProcessEvents.ProcessUpdated.class);
        m.put("platform:admin:process:archived", ProcessEvents.ProcessArchived.class);
        m.put("platform:admin:process:deleted", ProcessEvents.ProcessDeleted.class);
        m.put("platform:admin:processes:synced", ProcessEvents.ProcessesSynced.class);
        m.put("platform:admin:scheduled-job:created", ScheduledJobEvents.ScheduledJobCreated.class);
        m.put("platform:admin:scheduled-job:updated", ScheduledJobEvents.ScheduledJobUpdated.class);
        m.put("platform:admin:scheduled-job:paused", ScheduledJobEvents.ScheduledJobPaused.class);
        m.put("platform:admin:scheduled-job:resumed", ScheduledJobEvents.ScheduledJobResumed.class);
        m.put("platform:admin:scheduled-job:archived", ScheduledJobEvents.ScheduledJobArchived.class);
        m.put("platform:admin:scheduled-job:deleted", ScheduledJobEvents.ScheduledJobDeleted.class);
        m.put("platform:admin:scheduled-job:fired-manually", ScheduledJobEvents.ScheduledJobFiredManually.class);
        m.put("platform:admin:scheduledjobs:synced", ScheduledJobEvents.ScheduledJobsSynced.class);
        m.put("platform:admin:dispatchjob:requeued", DispatchJobEvents.DispatchJobRequeued.class);
        m.put("platform:admin:dispatchjobs:requeued", DispatchJobEvents.DispatchJobsRequeued.class);
        m.put("platform:admin:audit-log:redacted", AuditLogsRedacted.class);
        m.put("platform:messaging:dispatch-job:cancelled", DispatchJobEvents.DispatchJobCancelled.class);
        m.put("platform:messaging:dispatch-job:completed", DispatchJobEvents.DispatchJobCompleted.class);
        m.put("platform:developer:application-openapi:synced", OpenApiSpecEvents.ApplicationOpenApiSpecSynced.class);
        m.put("platform:portal:app:created", PortalAppEvents.PortalAppCreated.class);
        m.put("platform:portal:app:updated", PortalAppEvents.PortalAppUpdated.class);
        m.put("platform:portal:app:deleted", PortalAppEvents.PortalAppDeleted.class);
        m.put("platform:portal:identity:ensured", PortalIdentityEvents.PortalIdentityEnsured.class);
        m.put("platform:portal:identity:status-set", PortalIdentityEvents.PortalIdentityStatusSet.class);
        m.put("platform:portal:identity:deleted", PortalIdentityEvents.PortalIdentityDeleted.class);
        m.put("platform:portal:identity:app-granted", PortalIdentityEvents.PortalIdentityAppGranted.class);
        m.put("platform:portal:identity:app-revoked", PortalIdentityEvents.PortalIdentityAppRevoked.class);
        m.put("platform:function:function:created", FunctionEvents.FunctionCreated.class);
        m.put("platform:function:function:updated", FunctionEvents.FunctionUpdated.class);
        m.put("platform:function:function:deleted", FunctionEvents.FunctionDeleted.class);
        m.put("platform:function:version:published", FunctionEvents.VersionPublished.class);
        m.put("platform:function:version:ready", FunctionEvents.VersionReady.class);
        m.put("platform:function:version:retired", FunctionEvents.VersionRetired.class);
        m.put("platform:function:alias:changed", FunctionEvents.AliasChanged.class);
        m.put("platform:function:alias:removed", FunctionEvents.AliasRemoved.class);
        m.put("platform:function:config:updated", FunctionEvents.ConfigUpdated.class);
        m.put("platform:function:secret:set", FunctionEvents.SecretSet.class);
        m.put("platform:function:secret:deleted", FunctionEvents.SecretDeleted.class);
        m.put("platform:function:policy:updated", FunctionEvents.PolicyUpdated.class);
        m.put("platform:function:domain:claimed", FunctionEvents.DomainClaimed.class);
        m.put("platform:function:domain:released", FunctionEvents.DomainReleased.class);
        return m;
    }

    @Test
    void everyCataloguedCodeIsSampled() {
        assertThat(EMITTERS).hasSize(140);
        Set<String> catalogued = PlatformEventTypes.all().stream()
                .map(PlatformEventTypes.Definition::code).collect(Collectors.toCollection(TreeSet::new));
        assertThat(new TreeSet<>(EMITTERS.keySet())).isEqualTo(catalogued);
    }

    @Test
    void eachSchemaAdmitsTheDataItsEventEmits() throws Exception {
        List<String> errors = new ArrayList<>();
        for (var e : EMITTERS.entrySet()) {
            JsonNode schema = PlatformEventSchemas.all().get(e.getKey());
            Set<String> mayBeAbsent = MAY_BE_ABSENT.getOrDefault(e.getKey(), Set.of());
            if (!optionalProperties(schema).equals(mayBeAbsent)) {
                errors.add(e.getKey() + ": optional properties " + new TreeSet<>(optionalProperties(schema))
                        + ", the operation can leave out " + new TreeSet<>(mayBeAbsent));
            }
            for (boolean optionalsUnset : new boolean[] {false, true}) {
                DomainEvent event = sample(e.getValue(), e.getKey(), optionalsUnset ? mayBeAbsent : Set.of());
                JsonNode data = Json.MAPPER.readTree(Json.write(event.data()));
                validate(e.getKey() + (optionalsUnset ? " (optionals unset)" : "") + " $", schema, data, errors);
            }
        }
        assertThat(errors).as("serialised event data the catalogued schema rejects, or optional fields that are always sent").isEmpty();
    }

    /// [#validate] is not decorative: it rejects an extra property, a missing
    /// required one, a wrong type, a negative count and a malformed date-time.
    @Test
    void theValidatorRejectsWhatTheSchemaForbids() {
        JsonNode schema = PlatformEventSchemas.obj(PlatformEventSchemas.reqStr("id"), PlatformEventSchemas.reqU32("n"),
                PlatformEventSchemas.optDateTime("at"), PlatformEventSchemas.reqNullableStrArray("ids"));
        assertThat(errors(schema, "{\"id\":\"a\",\"n\":1,\"ids\":null}")).isEmpty();
        assertThat(errors(schema, "{\"id\":\"a\",\"n\":1,\"ids\":[\"x\"],\"at\":\"2026-10-11T00:00:00.000000Z\"}")).isEmpty();
        assertThat(errors(schema, "{\"id\":\"a\",\"n\":1,\"ids\":[],\"extra\":true}")).hasSize(1);
        assertThat(errors(schema, "{\"n\":1,\"ids\":[]}")).hasSize(1);
        assertThat(errors(schema, "{\"id\":1,\"n\":1,\"ids\":[]}")).hasSize(1);
        assertThat(errors(schema, "{\"id\":\"a\",\"n\":-1,\"ids\":[]}")).hasSize(1);
        assertThat(errors(schema, "{\"id\":\"a\",\"n\":1,\"ids\":[2]}")).hasSize(1);
        assertThat(errors(schema, "{\"id\":\"a\",\"n\":1,\"ids\":[],\"at\":\"yesterday\"}")).hasSize(1);
    }

    private static List<String> errors(JsonNode schema, String json) {
        List<String> out = new ArrayList<>();
        validate("$", schema, Json.MAPPER.readTree(json), out);
        return out;
    }

    // ── sampling ─────────────────────────────────────────────────────────

    private static Set<String> optionalProperties(JsonNode schema) {
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(n -> required.add(n.asString()));
        Set<String> out = new HashSet<>();
        schema.path("properties").propertyNames().forEach(p -> {
            if (!required.contains(p)) out.add(p);
        });
        return out;
    }

    /// The event built through its canonical constructor: metadata typed
    /// `code`, every other component a sample value, except those named in
    /// `unset` (null, or an empty list).
    private static DomainEvent sample(Class<? extends DomainEvent> type, String code, Set<String> unset) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            types[i] = c.getType();
            if (c.getType() == EventMetadata.class) {
                args[i] = new EventMetadata("evt_1", null, "platform:test", code, "platform.test.1", Instant.now(),
                        null, null, null, null, null);
            } else if (unset.contains(c.getName()) && !c.getType().isPrimitive()) {
                args[i] = List.class.isAssignableFrom(c.getType()) ? List.of() : null;
            } else {
                args[i] = value(c.getGenericType(), c.getName());
            }
        }
        assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                .as("%s components", type.getSimpleName()).containsAll(unset);
        var ctor = type.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private static Object value(Type type, String name) {
        if (type instanceof ParameterizedType p && List.class.isAssignableFrom((Class<?>) p.getRawType())) {
            return List.of(value(p.getActualTypeArguments()[0], name), value(p.getActualTypeArguments()[0], name));
        }
        if (type instanceof ParameterizedType p && Map.class.isAssignableFrom((Class<?>) p.getRawType())) {
            return Map.of(name + "-key", name + "-value");
        }
        Class<?> c = (Class<?>) type;
        if (c.isRecord()) {
            try {
                RecordComponent[] parts = c.getRecordComponents();
                Class<?>[] types = new Class<?>[parts.length];
                Object[] args = new Object[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    types[i] = parts[i].getType();
                    args[i] = value(parts[i].getGenericType(), parts[i].getName());
                }
                var ctor = c.getDeclaredConstructor(types);
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        // UserLoggedIn's loginMethod is an enum on the wire.
        if (c == String.class && name.equals("loginMethod")) return PrincipalEvents.UserLoggedIn.LOGIN_METHOD_OIDC;
        if (c == String.class) return name + "-1";
        if (c == int.class || c == Integer.class) return 3;
        if (c == long.class || c == Long.class) return 3L;
        if (c == boolean.class || c == Boolean.class) return true;
        if (c == Instant.class) return Instant.parse("2026-10-11T12:00:00Z");
        if (c.isEnum()) return c.getEnumConstants()[0];
        throw new IllegalArgumentException("no sample value for " + type + " (" + name + ")");
    }

    // ── validation: the subset of JSON Schema the seed's DSL writes ──────

    static void validate(String path, JsonNode schema, JsonNode value, List<String> errors) {
        JsonNode t = schema.get("type");
        if (t != null) {
            List<String> allowed = new ArrayList<>();
            if (t.isArray()) t.forEach(n -> allowed.add(n.asString()));
            else allowed.add(t.asString());
            if (allowed.stream().noneMatch(a -> isType(value, a))) {
                errors.add(path + ": " + value + " is not " + allowed);
                return;
            }
        }
        if (value.isNull()) return;
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode n : schema.get("enum")) found |= n.equals(value);
            if (!found) errors.add(path + ": " + value + " not in " + schema.get("enum"));
        }
        if (schema.has("minimum") && value.isNumber() && value.asLong() < schema.get("minimum").asLong()) {
            errors.add(path + ": " + value + " below " + schema.get("minimum"));
        }
        if ("date-time".equals(schema.path("format").asString(null)) && value.isString()) {
            try {
                OffsetDateTime.parse(value.asString());
            } catch (DateTimeParseException e) {
                errors.add(path + ": " + value + " is not an RFC 3339 date-time");
            }
        }
        if (value.isArray() && schema.has("items")) {
            int i = 0;
            for (JsonNode item : value) validate(path + "[" + i++ + "]", schema.get("items"), item, errors);
        }
        if (value.isObject()) {
            JsonNode props = schema.path("properties");
            for (JsonNode r : schema.path("required")) {
                if (!value.has(r.asString())) errors.add(path + ": missing required " + r.asString());
            }
            for (String key : value.propertyNames()) {
                if (props.has(key)) {
                    validate(path + "." + key, props.get(key), value.get(key), errors);
                } else if (schema.path("additionalProperties").isBoolean() && !schema.get("additionalProperties").asBoolean()) {
                    errors.add(path + ": property " + key + " is not in the schema");
                }
            }
        }
    }

    private static boolean isType(JsonNode v, String type) {
        return switch (type) {
            case "string" -> v.isString();
            case "integer" -> v.isIntegralNumber();
            case "number" -> v.isNumber();
            case "boolean" -> v.isBoolean();
            case "array" -> v.isArray();
            case "object" -> v.isObject();
            case "null" -> v.isNull();
            default -> throw new IllegalArgumentException("schema type " + type);
        };
    }
}
