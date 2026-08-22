package io.flowcatalyst.sdk.sync;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Definition types for syncing FlowCatalyst primitives to the platform:
 * the roles an application needs, the event types it publishes, the
 * subscriptions it consumes, the dispatch pools it expects, the principals it
 * manages, its process documentation and scheduled jobs.
 *
 * <p>Build a {@link DefinitionSet} (one per application) via
 * {@link DefinitionSet#define} and pass it to
 * {@code client.definitions().sync(...)}. Mirrors the TypeScript SDK's
 * {@code sync/definitions.ts}.
 */
public final class Definitions {

    private Definitions() {}

    /**
     * A permission reference on a role: either an already-formatted 4-part
     * string ({@link #raw}) or a structured {@link Permission} whose
     * application segment defaults to the set's application code.
     */
    public sealed interface PermissionRef permits Permission, RawPermission {
        /** Resolve to the full {@code application:context:aggregate:action} form, lower-cased. */
        String resolve(String defaultApplication);

        static PermissionRef raw(String value) {
            return new RawPermission(value);
        }
    }

    /** An already-formatted permission string. */
    public record RawPermission(String value) implements PermissionRef {
        @Override
        public String resolve(String defaultApplication) {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A structured permission — the 4-part
     * {@code <application>:<context>:<aggregate>:<action>} identity, defined
     * once and linkable from any number of roles. {@code application} may be
     * null, defaulting to the application code of the {@link DefinitionSet}
     * it is resolved against. FlowCatalyst has no standalone "create
     * permission" — permissions reach the platform via the roles that grant
     * them; the standalone catalogue is client-side documentation/reuse.
     */
    public record Permission(
            String application, String context, String aggregate, String action, String description)
            implements PermissionRef {

        public static Permission of(String context, String aggregate, String action) {
            return new Permission(null, context, aggregate, action, null);
        }

        public Permission withApplication(String application) {
            return new Permission(application, context, aggregate, action, description);
        }

        public Permission withDescription(String description) {
            return new Permission(application, context, aggregate, action, description);
        }

        @Override
        public String resolve(String defaultApplication) {
            String app = application != null ? application : defaultApplication;
            if (app == null) {
                throw new IllegalArgumentException(
                        "permission requires an application: set `application` on the permission "
                                + "or resolve it against a DefinitionSet/application code.");
            }
            return (app + ":" + context + ":" + aggregate + ":" + action).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A role declaration. Names are stored with the application code prefix:
     * given name {@code admin} under application {@code orders}, the role is
     * persisted as {@code orders:admin} — do not include the prefix yourself.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Role(
            String name,
            String displayName,
            String description,
            List<PermissionRef> permissions,
            Boolean clientManaged) {

        public static Role of(String name) {
            return new Role(name, null, null, null, null);
        }

        public Role withDisplayName(String displayName) {
            return new Role(name, displayName, description, permissions, clientManaged);
        }

        public Role withDescription(String description) {
            return new Role(name, displayName, description, permissions, clientManaged);
        }

        public Role withPermissions(List<PermissionRef> permissions) {
            return new Role(name, displayName, description, permissions, clientManaged);
        }

        /**
         * When true, client admins can assign this role to their own users;
         * when false, only platform admins can.
         */
        public Role withClientManaged(boolean clientManaged) {
            return new Role(name, displayName, description, permissions, clientManaged);
        }
    }

    /**
     * An event type declaration. {@code code} is the full 4-part identifier
     * {@code <app>:<subdomain>:<aggregate>:<event>}; the first segment MUST
     * match the application code being synced. JSON Schema is not sync'd via
     * this endpoint — attach schemas through the admin UI or
     * {@code eventTypes().addSchemaVersion(...)}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventType(String code, String name, String description) {
        public static EventType of(String code, String name) {
            return new EventType(code, name, null);
        }

        public EventType withDescription(String description) {
            return new EventType(code, name, description);
        }
    }

    /** How dispatch job failures interact with a subscription's delivery order. */
    public enum SubscriptionMode {
        /** Deliver independently; failures don't block other deliveries. */
        IMMEDIATE,
        /** On failure, hold subsequent deliveries for the same message group. */
        BLOCK_ON_ERROR
    }

    /** A single event-type binding inside a subscription. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubscriptionEventType(String eventTypeCode, String filter) {
        public static SubscriptionEventType of(String eventTypeCode) {
            return new SubscriptionEventType(eventTypeCode, null);
        }
    }

    /**
     * A subscription declaration: where to deliver ({@code target} URL or
     * {@code connectionId} reference), which event types trigger it, and how
     * to handle failures.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subscription(
            String code,
            String name,
            String description,
            String target,
            String connectionId,
            List<SubscriptionEventType> eventTypes,
            String dispatchPoolCode,
            SubscriptionMode mode,
            Integer maxRetries,
            Integer timeoutSeconds,
            Boolean dataOnly) {

        public static Subscription of(
                String code, String name, String target, List<SubscriptionEventType> eventTypes) {
            return new Subscription(
                    code, name, null, target, null, eventTypes, null, null, null, null, null);
        }

        public Subscription withDescription(String description) {
            return new Subscription(code, name, description, target, connectionId, eventTypes,
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
        }

        public Subscription withConnectionId(String connectionId) {
            return new Subscription(code, name, description, target, connectionId, eventTypes,
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
        }

        public Subscription withDispatchPoolCode(String dispatchPoolCode) {
            return new Subscription(code, name, description, target, connectionId, eventTypes,
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
        }

        public Subscription withMode(SubscriptionMode mode) {
            return new Subscription(code, name, description, target, connectionId, eventTypes,
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
        }

        public Subscription withMaxRetries(int maxRetries) {
            return new Subscription(code, name, description, target, connectionId, eventTypes,
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
        }

        public Subscription withTimeoutSeconds(int timeoutSeconds) {
            return new Subscription(code, name, description, target, connectionId, eventTypes,
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
        }

        /** When true, only the event's {@code data} field is POSTed (no metadata envelope). */
        public Subscription withDataOnly(boolean dataOnly) {
            return new Subscription(code, name, description, target, connectionId, eventTypes,
                    dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
        }
    }

    /**
     * A dispatch pool declaration — concurrency cap and per-minute rate limit
     * for outbound delivery.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DispatchPool(
            String code, String name, String description, Integer rateLimit, Integer concurrency) {

        public static DispatchPool of(String code, String name) {
            return new DispatchPool(code, name, null, null, null);
        }

        public DispatchPool withDescription(String description) {
            return new DispatchPool(code, name, description, rateLimit, concurrency);
        }

        /** Rate limit in requests per minute; platform default 100. */
        public DispatchPool withRateLimit(int rateLimit) {
            return new DispatchPool(code, name, description, rateLimit, concurrency);
        }

        /** Concurrency cap; platform default 10. */
        public DispatchPool withConcurrency(int concurrency) {
            return new DispatchPool(code, name, description, rateLimit, concurrency);
        }
    }

    /**
     * A principal (user) declaration, matched by email. {@code roles} lists
     * role short names WITHOUT the application prefix (the platform adds
     * {@code <app>:} per role).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Principal(String email, String name, List<String> roles, Boolean active) {
        public static Principal of(String email, String name) {
            return new Principal(email, name, null, null);
        }

        public Principal withRoles(List<String> roles) {
            return new Principal(email, name, roles, active);
        }

        public Principal withActive(boolean active) {
            return new Principal(email, name, roles, active);
        }
    }

    /**
     * A process documentation declaration. {@code code} is the three-segment
     * identifier {@code <app>:<subdomain>:<process>}; {@code body} carries the
     * diagram source verbatim (typically Mermaid).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Process(
            String code,
            String name,
            String description,
            String body,
            String diagramType,
            List<String> tags) {

        public static Process of(String code, String name) {
            return new Process(code, name, null, null, null, null);
        }

        public Process withDescription(String description) {
            return new Process(code, name, description, body, diagramType, tags);
        }

        public Process withBody(String body) {
            return new Process(code, name, description, body, diagramType, tags);
        }

        /** Diagram language; platform applies {@code mermaid} when omitted. */
        public Process withDiagramType(String diagramType) {
            return new Process(code, name, description, body, diagramType, tags);
        }

        public Process withTags(List<String> tags) {
            return new Process(code, name, description, body, diagramType, tags);
        }
    }

    /**
     * A scheduled-job declaration. {@code crons} requires 6-field,
     * seconds-first cron expressions ({@code sec min hour dom month dow}) — a
     * standard 5-field cron passes validation but never fires. {@code clientId}
     * scopes the job to a client/tenant; omit it only for platform-wide jobs
     * (anchor-only).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScheduledJob(
            String code,
            String name,
            String description,
            List<String> crons,
            String timezone,
            Object payload,
            Boolean concurrent,
            Boolean tracksCompletion,
            Integer timeoutSeconds,
            Integer deliveryMaxAttempts,
            String targetUrl,
            String clientId) {

        public static ScheduledJob of(String code, String name, List<String> crons) {
            return new ScheduledJob(code, name, null, crons, null, null, null, null, null, null,
                    null, null);
        }

        public ScheduledJob withDescription(String description) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }

        public ScheduledJob withTimezone(String timezone) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }

        public ScheduledJob withPayload(Object payload) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }

        /** Most apps want false — allows a new tick while a previous invocation still runs. */
        public ScheduledJob withConcurrent(boolean concurrent) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }

        /**
         * When true, the consumer must POST back to
         * {@code /api/scheduled-jobs/instances/{id}/complete}, enabling
         * per-instance status tracking.
         */
        public ScheduledJob withTracksCompletion(boolean tracksCompletion) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }

        public ScheduledJob withTimeoutSeconds(int timeoutSeconds) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }

        public ScheduledJob withDeliveryMaxAttempts(int deliveryMaxAttempts) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }

        /** Override the application's default callback URL for this job. */
        public ScheduledJob withTargetUrl(String targetUrl) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }

        /** Client/tenant that owns this job; null = platform-scoped (anchor only). */
        public ScheduledJob withClientId(String clientId) {
            return new ScheduledJob(code, name, description, crons, timezone, payload, concurrent,
                    tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, clientId);
        }
    }

    /** Container for all definitions belonging to one application. */
    public static final class DefinitionSet {
        private final String applicationCode;
        private final List<Role> roles = new ArrayList<>();
        private final List<Permission> permissions = new ArrayList<>();
        private final List<EventType> eventTypes = new ArrayList<>();
        private final List<Subscription> subscriptions = new ArrayList<>();
        private final List<DispatchPool> dispatchPools = new ArrayList<>();
        private final List<Principal> principals = new ArrayList<>();
        private final List<Process> processes = new ArrayList<>();
        private final List<ScheduledJob> scheduledJobs = new ArrayList<>();
        private Map<String, Object> openapiSpec;

        private DefinitionSet(String applicationCode) {
            this.applicationCode = applicationCode;
        }

        /** Start building definitions for {@code applicationCode}. */
        public static DefinitionSet define(String applicationCode) {
            return new DefinitionSet(applicationCode);
        }

        public DefinitionSet withRoles(List<Role> roles) {
            this.roles.addAll(roles);
            return this;
        }

        /**
         * Declare standalone permissions (reusable across roles). Their
         * application segment defaults to this set's applicationCode.
         */
        public DefinitionSet withPermissions(List<Permission> permissions) {
            this.permissions.addAll(permissions);
            return this;
        }

        public DefinitionSet withEventTypes(List<EventType> eventTypes) {
            this.eventTypes.addAll(eventTypes);
            return this;
        }

        public DefinitionSet withSubscriptions(List<Subscription> subscriptions) {
            this.subscriptions.addAll(subscriptions);
            return this;
        }

        public DefinitionSet withDispatchPools(List<DispatchPool> pools) {
            this.dispatchPools.addAll(pools);
            return this;
        }

        public DefinitionSet withPrincipals(List<Principal> principals) {
            this.principals.addAll(principals);
            return this;
        }

        public DefinitionSet withProcesses(List<Process> processes) {
            this.processes.addAll(processes);
            return this;
        }

        public DefinitionSet withScheduledJobs(List<ScheduledJob> jobs) {
            this.scheduledJobs.addAll(jobs);
            return this;
        }

        /**
         * Attach an OpenAPI document (parsed JSON) to publish alongside the
         * rest of the application's definitions. Each sync replaces the
         * previously published version.
         */
        public DefinitionSet withOpenapiSpec(Map<String, Object> spec) {
            this.openapiSpec = spec;
            return this;
        }

        public String applicationCode() {
            return applicationCode;
        }

        public List<Role> roles() {
            return roles;
        }

        public List<Permission> permissions() {
            return permissions;
        }

        public List<EventType> eventTypes() {
            return eventTypes;
        }

        public List<Subscription> subscriptions() {
            return subscriptions;
        }

        public List<DispatchPool> dispatchPools() {
            return dispatchPools;
        }

        public List<Principal> principals() {
            return principals;
        }

        public List<Process> processes() {
            return processes;
        }

        public List<ScheduledJob> scheduledJobs() {
            return scheduledJobs;
        }

        public Map<String, Object> openapiSpec() {
            return openapiSpec;
        }
    }
}
