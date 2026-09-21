package io.flowcatalyst.sdk.sync;

import io.flowcatalyst.sdk.error.FlowCatalystException;
import io.flowcatalyst.sdk.http.Transport;
import io.flowcatalyst.sdk.sync.Definitions.DefinitionSet;
import io.flowcatalyst.sdk.sync.SyncOptions.SyncCategory;
import io.flowcatalyst.sdk.sync.SyncResult.Category;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DefinitionSynchronizer — orchestrates syncing a {@link DefinitionSet} to
 * the platform's application-scoped sync API
 * ({@code /api/applications/{app}/*}{@code /sync}).
 *
 * <p>Categories are sync'd in a fixed order — roles, event types,
 * connections, subscriptions, dispatch pools, principals, processes,
 * scheduled jobs, OpenAPI — so that subscriptions can reference the
 * connections, event types and dispatch pools that were just created. Each
 * category sync is an independent HTTP call; a failure in one does NOT roll
 * back earlier successes.
 *
 * <p>Connections and subscriptions are additionally scoped by client
 * ({@link DefinitionSet#forClient}): the platform treats each
 * {@code (application, client)} sync call as the COMPLETE list for that
 * scope and, with {@code removeUnlisted}, deletes everything else in it. A
 * sync spanning several sets scoped to different clients (via
 * {@link #syncGrouped}) issues one platform call per distinct client — the
 * global scope (no client) first, then each client scope in first-seen
 * order — connections before subscriptions within each. If a scope's
 * connection sync fails, that scope's subscription sync is skipped and
 * reported as a failure rather than sent as a request that cannot resolve
 * its connections.
 *
 * <p>{@link #sync} and {@link #syncAll} keep their single-set behaviour —
 * they do NOT merge sets. {@link #syncGrouped} MERGES every set sharing an
 * application code into one combined sync before calling the platform,
 * because two sets syncing the SAME {@code (application, client)} scope
 * separately would let the second call's {@code removeUnlisted} delete what
 * the first call just created. The same code appearing twice in one merged
 * scope is a configuration error: that type's sync for that scope fails
 * LOCALLY, naming the code and the scope, and nothing is sent for it —
 * other types and other scopes still sync.
 *
 * <p>If ANY category of ANY application ends up {@link Category.Failed}, the
 * call throws {@link DefinitionSyncException} rather than returning
 * normally — a caller that doesn't inspect every category must not be able
 * to mistake a partial failure for success. {@link #syncAll} and
 * {@link #syncGrouped} still run every set/application to completion first
 * and throw once at the end, carrying every result (including the ones that
 * DID sync); see {@link DefinitionSyncException} for how to read the partial
 * outcome.
 */
public final class DefinitionSynchronizer {

    private final Transport transport;

    public DefinitionSynchronizer(Transport transport) {
        this.transport = transport;
    }

    /** Sync one application's definitions with default options. */
    public SyncResult sync(DefinitionSet set) {
        return sync(set, SyncOptions.defaults());
    }

    /**
     * Sync one application's definitions.
     *
     * @throws DefinitionSyncException if any category came back {@link
     *         Category.Failed} — {@link DefinitionSyncException#result()}
     *         carries the full result, including every category that DID
     *         sync
     */
    public SyncResult sync(DefinitionSet set, SyncOptions options) {
        return DefinitionSyncException.throwIfFailed(syncInternal(set, options));
    }

    /** The actual single-set sync; never throws for a {@link Category.Failed} — {@link #sync} does that. */
    private SyncResult syncInternal(DefinitionSet set, SyncOptions options) {
        String app = set.applicationCode();
        boolean removeUnlisted = options.removeUnlisted();

        Category roles = options.skips(SyncCategory.ROLES) || set.roles().isEmpty()
                ? Category.SKIPPED
                : syncRoles(app, set.roles(), removeUnlisted);
        Category eventTypes = options.skips(SyncCategory.EVENT_TYPES) || set.eventTypes().isEmpty()
                ? Category.SKIPPED
                : post(app, "event-types", Map.of("eventTypes", set.eventTypes()), removeUnlisted);

        ScopedResult scoped = syncConnectionsAndSubscriptions(app, List.of(set), options);

        Category dispatchPools =
                options.skips(SyncCategory.DISPATCH_POOLS) || set.dispatchPools().isEmpty()
                        ? Category.SKIPPED
                        : post(app, "dispatch-pools", Map.of("pools", set.dispatchPools()), removeUnlisted);
        Category principals = options.skips(SyncCategory.PRINCIPALS) || set.principals().isEmpty()
                ? Category.SKIPPED
                : post(app, "principals", Map.of("principals", set.principals()), removeUnlisted);
        Category processes = options.skips(SyncCategory.PROCESSES) || set.processes().isEmpty()
                ? Category.SKIPPED
                : post(app, "processes", Map.of("processes", set.processes()), removeUnlisted);
        Category scheduledJobs =
                options.skips(SyncCategory.SCHEDULED_JOBS) || set.scheduledJobs().isEmpty()
                        ? Category.SKIPPED
                        : syncScheduledJobs(app, set.scheduledJobs(), removeUnlisted);
        Category openapi = options.skips(SyncCategory.OPENAPI) || set.openapiSpec() == null
                ? Category.SKIPPED
                : syncOpenapi(app, set.openapiSpec());

        return new SyncResult(app, roles, eventTypes, scoped.connections(), scoped.subscriptions(),
                dispatchPools, principals, processes, scheduledJobs, openapi);
    }

    /**
     * Sync multiple applications' definitions sequentially; results are
     * returned in the same order.
     *
     * <p>Unlike {@link #syncGrouped}, sets are NOT merged — two sets for the
     * same application are synced as two separate calls; when they share a
     * client scope, the second call's {@code removeUnlisted} will delete
     * what the first just created. Use {@link #syncGrouped} for several sets
     * contributing to one application.
     *
     * <p>Every set is synced before this can throw for a {@link
     * Category.Failed} — one set's duplicate code or failed connection sync
     * does not stop the rest from being attempted. A genuinely uncaught
     * exception (e.g. a network failure from a category that does not catch
     * its own) still propagates immediately and stops the run, exactly as it
     * always has.
     *
     * @throws DefinitionSyncException if any set's any category came back
     *         {@link Category.Failed} — {@link
     *         DefinitionSyncException#results()} carries every set's result,
     *         in order, including the ones that synced fully
     */
    public List<SyncResult> syncAll(List<DefinitionSet> sets, SyncOptions options) {
        List<SyncResult> results = new ArrayList<>(sets.size());
        for (DefinitionSet set : sets) {
            results.add(syncInternal(set, options));
        }
        return DefinitionSyncException.throwIfAnyFailed(results);
    }

    /** {@link #syncGrouped(List, SyncOptions)} with default options. */
    public Map<String, SyncResult> syncGrouped(List<DefinitionSet> sets) {
        return syncGrouped(sets, SyncOptions.defaults());
    }

    /**
     * Sync multiple definition sets, grouping by application code and
     * MERGING every set that shares one into a single combined sync — then
     * issuing each category's platform call exactly ONCE per application
     * (and, for connections/subscriptions, once per {@code (application,
     * client)} scope within it).
     *
     * <p>This is not an optimisation: the platform scopes {@code
     * removeUnlisted} to one {@code (application, client)} PER CALL, so two
     * sets for the same scope (e.g. a hand-built global set plus a
     * per-tenant loop's sets) must never become two separate calls — the
     * second would delete what the first just created.
     *
     * <p>Every application is synced before this can throw for a {@link
     * Category.Failed} — one application's failure does not stop the rest
     * from being attempted.
     *
     * @return results keyed by application code
     * @throws DefinitionSyncException if any application's any category
     *         came back {@link Category.Failed} — {@link
     *         DefinitionSyncException#resultsByApplication()} carries every
     *         application's result, including the ones that synced fully
     */
    public Map<String, SyncResult> syncGrouped(List<DefinitionSet> sets, SyncOptions options) {
        Map<String, List<DefinitionSet>> byApp = new LinkedHashMap<>();
        for (DefinitionSet set : sets) {
            byApp.computeIfAbsent(set.applicationCode(), k -> new ArrayList<>()).add(set);
        }

        Map<String, SyncResult> results = new LinkedHashMap<>();
        for (Map.Entry<String, List<DefinitionSet>> entry : byApp.entrySet()) {
            results.put(entry.getKey(), syncMerged(entry.getKey(), entry.getValue(), options));
        }
        return DefinitionSyncException.throwIfAnyFailed(results);
    }

    /**
     * The actual merge: pool every contributing set's rows per category and
     * sync each category's combined list exactly once — reusing the exact
     * same per-category helpers {@link #sync} calls, so a merged set behaves
     * identically to an equivalent hand-built one.
     */
    private SyncResult syncMerged(String app, List<DefinitionSet> sets, SyncOptions options) {
        boolean removeUnlisted = options.removeUnlisted();

        List<Definitions.Role> roles = new ArrayList<>();
        List<Definitions.EventType> eventTypes = new ArrayList<>();
        List<Definitions.DispatchPool> dispatchPools = new ArrayList<>();
        List<Definitions.Principal> principals = new ArrayList<>();
        List<Definitions.Process> processes = new ArrayList<>();
        List<Definitions.ScheduledJob> scheduledJobs = new ArrayList<>();
        Map<String, Object> openapiSpec = null;

        for (DefinitionSet set : sets) {
            roles.addAll(set.roles());
            eventTypes.addAll(set.eventTypes());
            dispatchPools.addAll(set.dispatchPools());
            principals.addAll(set.principals());
            processes.addAll(set.processes());
            scheduledJobs.addAll(set.scheduledJobs());
            // Only one OpenAPI document makes sense per application; keep
            // whichever set actually attached one (first wins — arbitrary
            // but deterministic).
            if (openapiSpec == null) {
                openapiSpec = set.openapiSpec();
            }
        }

        Category rolesResult = options.skips(SyncCategory.ROLES) || roles.isEmpty()
                ? Category.SKIPPED
                : syncRoles(app, roles, removeUnlisted);
        Category eventTypesResult = options.skips(SyncCategory.EVENT_TYPES) || eventTypes.isEmpty()
                ? Category.SKIPPED
                : post(app, "event-types", Map.of("eventTypes", eventTypes), removeUnlisted);

        ScopedResult scoped = syncConnectionsAndSubscriptions(app, sets, options);

        Category dispatchPoolsResult =
                options.skips(SyncCategory.DISPATCH_POOLS) || dispatchPools.isEmpty()
                        ? Category.SKIPPED
                        : post(app, "dispatch-pools", Map.of("pools", dispatchPools), removeUnlisted);
        Category principalsResult = options.skips(SyncCategory.PRINCIPALS) || principals.isEmpty()
                ? Category.SKIPPED
                : post(app, "principals", Map.of("principals", principals), removeUnlisted);
        Category processesResult = options.skips(SyncCategory.PROCESSES) || processes.isEmpty()
                ? Category.SKIPPED
                : post(app, "processes", Map.of("processes", processes), removeUnlisted);
        Category scheduledJobsResult =
                options.skips(SyncCategory.SCHEDULED_JOBS) || scheduledJobs.isEmpty()
                        ? Category.SKIPPED
                        : syncScheduledJobs(app, scheduledJobs, removeUnlisted);
        Category openapiResult = options.skips(SyncCategory.OPENAPI) || openapiSpec == null
                ? Category.SKIPPED
                : syncOpenapi(app, openapiSpec);

        return new SyncResult(app, rolesResult, eventTypesResult, scoped.connections(),
                scoped.subscriptions(), dispatchPoolsResult, principalsResult, processesResult,
                scheduledJobsResult, openapiResult);
    }

    // ── connections + subscriptions: client grouping ───────────────────

    private record ScopedResult(Category connections, Category subscriptions) {}

    /**
     * Sync connections, then subscriptions — grouped by {@code
     * DefinitionSet#clientId} (one platform call per distinct client per
     * resource; several sets sharing a client are pooled into one call).
     *
     * <p>Ordering, per the platform's ownership model:
     * <ul>
     *   <li>the global group (no client) is processed before any client
     *       group, because a client-scoped subscription may reference a
     *       global connection;
     *   <li>within EACH group, connections are synced before subscriptions,
     *       because a subscription's {@code connectionCode} must resolve in
     *       the same run;
     *   <li>if a group's connection sync fails, that group's subscriptions
     *       are skipped entirely (their connection codes may not resolve) —
     *       recorded as a failure rather than sent as a request that would
     *       404.
     * </ul>
     */
    private ScopedResult syncConnectionsAndSubscriptions(
            String app, List<DefinitionSet> sets, SyncOptions options) {
        boolean removeUnlisted = options.removeUnlisted();
        boolean doConnections = !options.skips(SyncCategory.CONNECTIONS);
        boolean doSubscriptions = !options.skips(SyncCategory.SUBSCRIPTIONS);

        Map<String, List<DefinitionSet>> groups = new LinkedHashMap<>();
        for (DefinitionSet set : sets) {
            String key = set.clientId() == null ? "" : set.clientId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(set);
        }
        List<String> orderedKeys = new ArrayList<>(groups.keySet());
        // Stable sort: the global group ('') moves to the front, client
        // groups keep their relative (first-seen) order after it.
        orderedKeys.sort(Comparator.comparing(k -> !k.isEmpty()));

        int connCreated = 0;
        int connUpdated = 0;
        int connDeleted = 0;
        List<String> connCodes = new ArrayList<>();
        List<String> connErrors = new ArrayList<>();
        int subCreated = 0;
        int subUpdated = 0;
        int subDeleted = 0;
        List<String> subCodes = new ArrayList<>();
        List<String> subErrors = new ArrayList<>();
        Set<String> failedScopes = new HashSet<>();
        boolean anyConnectionsAttempted = false;
        boolean anySubscriptionsAttempted = false;

        for (String key : orderedKeys) {
            String clientId = key.isEmpty() ? null : key;
            List<Definitions.Connection> connections = new ArrayList<>();
            List<Definitions.Subscription> subscriptions = new ArrayList<>();
            for (DefinitionSet set : groups.get(key)) {
                connections.addAll(set.connections());
                subscriptions.addAll(set.subscriptions());
            }

            if (doConnections && !connections.isEmpty()) {
                anyConnectionsAttempted = true;
                Category result = syncConnectionGroup(app, connections, clientId, removeUnlisted);
                if (result instanceof Category.Synced s) {
                    connCreated += s.created();
                    connUpdated += s.updated();
                    connDeleted += s.deleted();
                    connCodes.addAll(s.syncedCodes());
                } else if (result instanceof Category.Failed f) {
                    connCreated += f.created();
                    connUpdated += f.updated();
                    connDeleted += f.deleted();
                    connCodes.addAll(f.syncedCodes());
                    connErrors.add(f.error());
                    failedScopes.add(key);
                }
            }

            if (!doSubscriptions || subscriptions.isEmpty()) {
                continue;
            }
            anySubscriptionsAttempted = true;

            if (failedScopes.contains(key)) {
                subErrors.add("Skipped subscription sync for "
                        + (key.isEmpty() ? "the global scope" : "client \"" + key + "\"")
                        + ": its connection sync failed first");
                continue;
            }

            Category result = syncSubscriptionGroup(app, subscriptions, clientId, removeUnlisted);
            if (result instanceof Category.Synced s) {
                subCreated += s.created();
                subUpdated += s.updated();
                subDeleted += s.deleted();
                subCodes.addAll(s.syncedCodes());
            } else if (result instanceof Category.Failed f) {
                subCreated += f.created();
                subUpdated += f.updated();
                subDeleted += f.deleted();
                subCodes.addAll(f.syncedCodes());
                subErrors.add(f.error());
            }
        }

        Category connectionsResult = !anyConnectionsAttempted
                ? Category.SKIPPED
                : connErrors.isEmpty()
                        ? new Category.Synced(app, connCreated, connUpdated, connDeleted, connCodes)
                        : new Category.Failed(
                                connCreated, connUpdated, connDeleted, connCodes,
                                String.join("; ", connErrors));
        Category subscriptionsResult = !anySubscriptionsAttempted
                ? Category.SKIPPED
                : subErrors.isEmpty()
                        ? new Category.Synced(app, subCreated, subUpdated, subDeleted, subCodes)
                        : new Category.Failed(
                                subCreated, subUpdated, subDeleted, subCodes,
                                String.join("; ", subErrors));

        return new ScopedResult(connectionsResult, subscriptionsResult);
    }

    /** Sync one client-group's worth of connections. */
    private Category syncConnectionGroup(
            String app, List<Definitions.Connection> connections, String clientId, boolean removeUnlisted) {
        // Two sets contributing to the SAME (application, client) scope
        // (most commonly after syncGrouped() merges them) defining the same
        // connection code is a configuration error — fail locally, naming
        // the code and scope, rather than sending a request the platform
        // will reject or silently keeping whichever row happened to be last.
        List<String> duplicates =
                findDuplicates(connections.stream().map(Definitions.Connection::code).toList());
        if (!duplicates.isEmpty()) {
            return new Category.Failed(0, 0, 0, List.of(), String.format(
                    "Duplicate connection code(s) for %s: %s", scopeLabel(app, clientId),
                    String.join(", ", duplicates)));
        }
        try {
            return postScoped(app, "connections", clientId, "connections", connections, removeUnlisted);
        } catch (FlowCatalystException e) {
            return new Category.Failed(0, 0, 0, List.of(), e.getMessage());
        }
    }

    /** Sync one client-group's worth of subscriptions. */
    private Category syncSubscriptionGroup(
            String app, List<Definitions.Subscription> subscriptions, String clientId,
            boolean removeUnlisted) {
        // Two sets contributing to the SAME (application, client) scope
        // defining the same subscription code is a configuration error —
        // fail locally rather than sending a request the platform will
        // reject or silently keeping whichever row happened to be last.
        List<String> duplicates =
                findDuplicates(subscriptions.stream().map(Definitions.Subscription::code).toList());
        if (!duplicates.isEmpty()) {
            return new Category.Failed(0, 0, 0, List.of(), String.format(
                    "Duplicate subscription code(s) for %s: %s", scopeLabel(app, clientId),
                    String.join(", ", duplicates)));
        }
        try {
            return postScoped(app, "subscriptions", clientId, "subscriptions", subscriptions, removeUnlisted);
        } catch (FlowCatalystException e) {
            return new Category.Failed(0, 0, 0, List.of(), e.getMessage());
        }
    }

    /**
     * {@code POST /api/applications/{app}/{resource}/sync?removeUnlisted=}
     * with {@code clientId} in the body — OMITTED entirely when null (never
     * sent as JSON {@code null}: the platform's schema validation declares
     * it a string and refuses a null there).
     */
    private Category.Synced postScoped(
            String app, String resource, String clientId, String wireKey, List<?> entries,
            boolean removeUnlisted) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (clientId != null) {
            body.put("clientId", clientId);
        }
        body.put(wireKey, entries);
        return transport.post(
                "/api/applications/" + Transport.enc(app) + "/" + resource + "/sync",
                Map.of("removeUnlisted", removeUnlisted),
                body,
                Category.Synced.class);
    }

    // ── duplicate-code validation ────────────────────────────────────

    /**
     * Values appearing more than once in {@code values} — a configuration
     * error when it happens (two definitions colliding in the same sync
     * scope), most commonly surfacing after {@link #syncGrouped} merges two
     * otherwise individually-valid sets for the same application (or
     * application + client) into one call. Blank values are ignored —
     * already invalid on their own terms, reported elsewhere.
     */
    private static List<String> findDuplicates(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            counts.merge(value, 1, Integer::sum);
        }
        List<String> duplicates = new ArrayList<>();
        counts.forEach((value, count) -> {
            if (count > 1) {
                duplicates.add(value);
            }
        });
        return duplicates;
    }

    /** Human-readable label for a sync scope, used in duplicate-code error messages. */
    private static String scopeLabel(String app, String clientId) {
        return clientId == null
                ? "application \"" + app + "\""
                : "application \"" + app + "\", client \"" + clientId + "\"";
    }

    // ── per-category callers ────────────────────────────────────────

    private Category syncRoles(String app, List<Definitions.Role> roles, boolean removeUnlisted) {
        // Resolve permission refs to full strings so the wire shape is
        // {name, displayName?, description?, permissions: [string], clientManaged?}.
        List<Map<String, Object>> wire = roles.stream().map(role -> {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", role.name());
            putIfNotNull(entry, "displayName", role.displayName());
            putIfNotNull(entry, "description", role.description());
            if (role.permissions() != null) {
                entry.put("permissions",
                        role.permissions().stream().map(p -> p.resolve(app)).toList());
            }
            putIfNotNull(entry, "clientManaged", role.clientManaged());
            return entry;
        }).toList();
        return post(app, "roles", Map.of("roles", wire), removeUnlisted);
    }

    /** Wire shape of the scheduled-jobs sync response. */
    private record ScheduledJobsWire(
            String applicationCode, List<String> created, List<String> updated,
            List<String> archived) {}

    private Category syncScheduledJobs(
            String app, List<Definitions.ScheduledJob> jobs, boolean removeUnlisted) {
        // Scheduled-jobs sync is the one endpoint that uses `archiveUnlisted`
        // in the body rather than `removeUnlisted` as a query param, and takes
        // one `clientId` per call rather than per job: group jobs by clientId
        // and issue one request per distinct group — `clientId` must NOT ride
        // along inside each job object (the API rejects unknown fields).
        Map<String, List<Definitions.ScheduledJob>> groups = new LinkedHashMap<>();
        for (Definitions.ScheduledJob job : jobs) {
            groups.computeIfAbsent(job.clientId() == null ? "" : job.clientId(),
                    k -> new ArrayList<>()).add(job);
        }

        int created = 0;
        int updated = 0;
        int deleted = 0;
        List<String> syncedCodes = new ArrayList<>();
        for (Map.Entry<String, List<Definitions.ScheduledJob>> group : groups.entrySet()) {
            List<Map<String, Object>> wireJobs = group.getValue().stream().map(job -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> asMap =
                        transport.mapper().convertValue(job, Map.class);
                asMap.remove("clientId");
                return asMap;
            }).toList();

            Map<String, Object> body = new LinkedHashMap<>();
            if (!group.getKey().isEmpty()) {
                body.put("clientId", group.getKey());
            }
            body.put("jobs", wireJobs);
            body.put("archiveUnlisted", removeUnlisted);

            ScheduledJobsWire result = transport.post(
                    "/api/applications/" + Transport.enc(app) + "/scheduled-jobs/sync",
                    body,
                    ScheduledJobsWire.class);
            created += result.created().size();
            updated += result.updated().size();
            deleted += result.archived().size();
            syncedCodes.addAll(result.created());
            syncedCodes.addAll(result.updated());
        }
        return new Category.Synced(app, created, updated, deleted, syncedCodes);
    }

    /** Wire shape of the OpenAPI sync response. */
    private record OpenapiWire(
            String applicationCode, String version, String archivedPriorVersion, Boolean unchanged) {}

    private Category syncOpenapi(String app, Map<String, Object> spec) {
        // OpenAPI sync is one-shot — body is {spec}, not a list; normalise the
        // response to the per-category shape so callers can iterate uniformly.
        OpenapiWire result = transport.post(
                "/api/applications/" + Transport.enc(app) + "/openapi/sync",
                Map.of("spec", spec),
                OpenapiWire.class);
        boolean unchanged = Boolean.TRUE.equals(result.unchanged());
        int created = unchanged || result.archivedPriorVersion() != null ? 0 : 1;
        int updated = result.archivedPriorVersion() != null ? 1 : 0;
        return new Category.Synced(result.applicationCode(), created, updated, 0,
                List.of(result.version()));
    }

    // ── transport ───────────────────────────────────────────────────

    private Category post(String app, String resource, Object body, boolean removeUnlisted) {
        return transport.post(
                "/api/applications/" + Transport.enc(app) + "/" + resource + "/sync",
                Map.of("removeUnlisted", removeUnlisted),
                body,
                Category.Synced.class);
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }
}
