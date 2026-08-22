package io.flowcatalyst.sdk.sync;

import io.flowcatalyst.sdk.http.Transport;
import io.flowcatalyst.sdk.sync.Definitions.DefinitionSet;
import io.flowcatalyst.sdk.sync.SyncOptions.SyncCategory;
import io.flowcatalyst.sdk.sync.SyncResult.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DefinitionSynchronizer — orchestrates syncing a {@link DefinitionSet} to
 * the platform's application-scoped sync API
 * ({@code /api/applications/{app}/*}{@code /sync}).
 *
 * <p>Categories are sync'd in a fixed order — roles, event types,
 * subscriptions, dispatch pools, principals, processes, scheduled jobs,
 * OpenAPI — so that subscriptions can reference the event types and dispatch
 * pools that were just created. Each category sync is an independent HTTP
 * call; a failure in one does NOT roll back earlier successes.
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

    /** Sync one application's definitions. */
    public SyncResult sync(DefinitionSet set, SyncOptions options) {
        String app = set.applicationCode();
        boolean removeUnlisted = options.removeUnlisted();

        Category roles = options.skips(SyncCategory.ROLES) || set.roles().isEmpty()
                ? Category.SKIPPED
                : syncRoles(app, set.roles(), removeUnlisted);
        Category eventTypes = options.skips(SyncCategory.EVENT_TYPES) || set.eventTypes().isEmpty()
                ? Category.SKIPPED
                : post(app, "event-types", Map.of("eventTypes", set.eventTypes()), removeUnlisted);
        Category subscriptions =
                options.skips(SyncCategory.SUBSCRIPTIONS) || set.subscriptions().isEmpty()
                        ? Category.SKIPPED
                        : post(app, "subscriptions",
                                Map.of("subscriptions", set.subscriptions()), removeUnlisted);
        Category dispatchPools =
                options.skips(SyncCategory.DISPATCH_POOLS) || set.dispatchPools().isEmpty()
                        ? Category.SKIPPED
                        : post(app, "dispatch-pools",
                                Map.of("pools", set.dispatchPools()), removeUnlisted);
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

        return new SyncResult(app, roles, eventTypes, subscriptions, dispatchPools, principals,
                processes, scheduledJobs, openapi);
    }

    /**
     * Sync multiple applications' definitions sequentially; results are
     * returned in the same order. A failure in one set short-circuits the
     * rest (the thrown exception propagates).
     */
    public List<SyncResult> syncAll(List<DefinitionSet> sets, SyncOptions options) {
        List<SyncResult> results = new ArrayList<>(sets.size());
        for (DefinitionSet set : sets) {
            results.add(sync(set, options));
        }
        return results;
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
