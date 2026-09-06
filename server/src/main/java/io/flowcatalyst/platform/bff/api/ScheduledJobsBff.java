package io.flowcatalyst.platform.bff.api;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.ClientStatus;
import io.flowcatalyst.platform.scheduledjob.InstanceStatus;
import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstance;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceLog;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.ClientFilter;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository.ListFilter;
import io.flowcatalyst.platform.scheduledjob.TriggerKind;
import io.flowcatalyst.platform.shared.apicommon.PageQuery;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static io.flowcatalyst.platform.shared.auth.Permission.SCHEDULED_JOB_VIEW;

/// The `/bff/scheduled-jobs` surface (bff spec §7): the SPA's paginated,
/// name-joined shape for scheduled jobs and their firing history. Reads
/// only — writes go through `/api/scheduled-jobs`
/// ([io.flowcatalyst.platform.scheduledjob.api.ScheduledJobApi]).
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/bff/scheduled-jobs` | 200 [Page] of [JobResponse] |
/// | GET | `/bff/scheduled-jobs/filter-options` | 200 [FilterOptionsResponse] |
/// | GET | `/bff/scheduled-jobs/{id}` | 200 [JobResponse]; 404 (incl. inaccessible client, spec §9 D5) |
/// | GET | `/bff/scheduled-jobs/{id}/instances` | 200 [Page] of [InstanceResponse] |
/// | GET | `/bff/scheduled-jobs/instances/{instanceId}` | 200 [InstanceResponse]; 404 (incl. inaccessible) |
/// | GET | `/bff/scheduled-jobs/instances/{instanceId}/logs` | 200 bare array of [InstanceLogResponse]; 404 (incl. inaccessible) |
public final class ScheduledJobsBff {

    private ScheduledJobsBff() {
    }

    public record State(ScheduledJobRepository repo, ScheduledJobInstanceRepository instances,
                        ClientRepository clients, ApplicationRepository applications) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(instances, "instances");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(applications, "applications");
        }
    }

    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/bff/scheduled-jobs", Auth.scoped(ctx -> list(ctx, s)));
        routes.get("/bff/scheduled-jobs/filter-options", Auth.scoped(ctx -> filterOptions(ctx, s)));
        routes.get("/bff/scheduled-jobs/instances/{instanceId}", Auth.scoped(ctx -> getInstance(ctx, s)));
        routes.get("/bff/scheduled-jobs/instances/{instanceId}/logs", Auth.scoped(ctx -> listInstanceLogs(ctx, s)));
        routes.get("/bff/scheduled-jobs/{id}", Auth.scoped(ctx -> getJob(ctx, s)));
        routes.get("/bff/scheduled-jobs/{id}/instances", Auth.scoped(ctx -> listInstances(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        PageQuery page = PageQuery.from(ctx);
        ListFilter filter = listFilter(ctx, ac);
        if (filter == null) {
            ctx.json(Page.of(List.of(), page, 0));
            return;
        }
        List<ScheduledJob> rows = s.repo().findWithFilters(filter, page.pageSize(), (int) page.offset());
        long total = s.repo().countWithFilters(filter);

        Map<String, Client> clients = byId(s.clients().findAll(), Client::id);
        Map<String, Application> apps = byId(s.applications().findWithFilters(new ApplicationRepository.ListFilter(null, null)), Application::id);
        var out = rows.stream().map(j -> toJobResponse(s, j, clients, apps)).toList();
        ctx.json(Page.of(out, page, total));
    }

    private static void getJob(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        ScheduledJob job = visible(ac, job(s, ctx.pathParam("id")));
        Map<String, Client> clients = byId(s.clients().findAll(), Client::id);
        Map<String, Application> apps = byId(s.applications().findWithFilters(new ApplicationRepository.ListFilter(null, null)), Application::id);
        ctx.json(toJobResponse(s, job, clients, apps));
    }

    private static void listInstances(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        String jobId = ctx.pathParam("id");
        visible(ac, job(s, jobId));

        PageQuery page = PageQuery.from(ctx);
        InstanceStatus status = parseInstanceStatus(queryParam(ctx, "status"));
        TriggerKind triggerKind = parseTriggerKind(queryParam(ctx, "triggerKind"));
        Instant from = timestamp(queryParam(ctx, "from"));
        Instant to = timestamp(queryParam(ctx, "to"));
        var filter = new ScheduledJobInstanceRepository.ListFilter(jobId, null, status, triggerKind, from, to);
        var rows = s.instances().list(filter, page.pageSize(), (int) page.offset());
        long total = s.instances().count(filter);
        ctx.json(Page.of(rows.stream().map(InstanceResponse::from).toList(), page, total));
    }

    private static void getInstance(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        ctx.json(InstanceResponse.from(visibleInstance(ac, instance(s, ctx.pathParam("instanceId")))));
    }

    private static void listInstanceLogs(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);
        ScheduledJobInstance inst = visibleInstance(ac, instance(s, ctx.pathParam("instanceId")));
        ctx.json(s.instances().listLogs(inst.id(), ScheduledJobInstanceRepository.MAX_LOGS).stream()
                .map(InstanceLogResponse::from).toList());
    }

    /// `clients`: (anchor only) a `platform` pseudo-option first, then every
    /// ACTIVE client the caller can access, sorted by label. `applications`:
    /// every active application, sorted by label. `statuses`: the fixed
    /// three-value catalogue (bff spec §7).
    private static void filterOptions(Context ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SCHEDULED_JOB_VIEW);

        var clientOptions = new java.util.ArrayList<FilterOption>();
        if (ac.isAnchor()) clientOptions.add(new FilterOption("platform", "Platform-scoped"));
        s.clients().findAll().stream()
                .filter(c -> c.status() == ClientStatus.ACTIVE)
                .filter(c -> ac.isAnchor() || ac.canAccessClient(c.id()))
                .map(c -> new FilterOption(c.id(), c.name()))
                .sorted(Comparator.comparing(FilterOption::label))
                .forEach(clientOptions::add);

        var appOptions = s.applications().findWithFilters(new ApplicationRepository.ListFilter(null, true)).stream()
                .map(a -> new FilterOption(a.id(), a.name()))
                .sorted(Comparator.comparing(FilterOption::label))
                .toList();

        var statusOptions = List.of(
                new FilterOption("ACTIVE", "Active"),
                new FilterOption("PAUSED", "Paused"),
                new FilterOption("ARCHIVED", "Archived"));

        ctx.json(new FilterOptionsResponse(clientOptions, appOptions, statusOptions));
    }

    // ── Read-side helpers ────────────────────────────────────────────────

    /// Query params → filter (bff spec §7): `clientIds` is a CSV multi-select
    /// where the literal `platform` additionally matches platform-scoped
    /// jobs; the caller's [io.flowcatalyst.platform.shared.auth.Visibility]
    /// is enforced in SQL alongside it, so `total` and the page always agree
    /// (Go filters accessibility in memory after paging — spec §1 "Java may
    /// filter in SQL").
    /// The client filter of the list, folded with the caller's confinement.
    /// A non-anchor caller sees only jobs of clients it can access —
    /// platform-scoped jobs (`client_id IS NULL`) are anchor-only (owner
    /// ruling 2026-09-06 #6; Go 491d961 puts the same rule in its SQL filter
    /// so `total` and the pages agree with the visible rows). `null` means
    /// the caller can see nothing at all: the handler answers an empty page
    /// without a query.
    private static ListFilter listFilter(Context ctx, AuthContext ac) {
        List<String> clientIds = csv(queryParam(ctx, "clientIds"));
        if (!ac.isAnchor()) {
            List<String> allowed = clientIds.isEmpty()
                    ? ac.clients()
                    : clientIds.stream().filter(id -> !id.equals("platform") && ac.canAccessClient(id)).toList();
            if (allowed.isEmpty()) return null;
            return new ListFilter(new ClientFilter.OfMany(allowed, false), null, queryParam(ctx, "search"), ac.visibility(),
                    csv(queryParam(ctx, "statuses")), csv(queryParam(ctx, "applicationIds")));
        }
        ClientFilter client;
        if (clientIds.isEmpty()) {
            client = new ClientFilter.Any();
        } else {
            boolean includesPlatform = clientIds.contains("platform");
            List<String> explicit = clientIds.stream().filter(id -> !id.equals("platform")).toList();
            client = new ClientFilter.OfMany(explicit, includesPlatform);
        }
        return new ListFilter(client, null, queryParam(ctx, "search"), ac.visibility(),
                csv(queryParam(ctx, "statuses")), csv(queryParam(ctx, "applicationIds")));
    }

    private static ScheduledJob job(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("ScheduledJob", id));
    }

    private static ScheduledJobInstance instance(State s, String id) {
        return s.instances().findById(id).orElseThrow(() -> HttpError.notFound("ScheduledJobInstance", id));
    }

    /// An inaccessible client's job answers 404, not 403 (spec §9 D5: align
    /// to the PR-3 404 ruling).
    private static ScheduledJob visible(AuthContext ac, ScheduledJob j) {
        if (j.isPlatformScoped() || ac.canAccessClient(j.clientId())) return j;
        throw HttpError.notFound("ScheduledJob", j.id());
    }

    private static ScheduledJobInstance visibleInstance(AuthContext ac, ScheduledJobInstance i) {
        if (i.isPlatformScoped() || ac.canAccessClient(i.clientId())) return i;
        throw HttpError.notFound("ScheduledJobInstance", i.id());
    }

    private static JobResponse toJobResponse(State s, ScheduledJob j, Map<String, Client> clients, Map<String, Application> apps) {
        Client client = j.clientId() == null ? null : clients.get(j.clientId());
        Application app = j.applicationId() == null ? null : apps.get(j.applicationId());
        boolean active = s.instances().hasActiveInstance(j.id(), j.tracksCompletion());
        return JobResponse.from(j, client == null ? null : client.name(), app == null ? null : app.name(), active);
    }

    private static <T> Map<String, T> byId(List<T> rows, Function<T, String> id) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(id, Function.identity(), (a, _) -> a));
    }

    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static List<String> csv(String value) {
        if (value == null) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    private static Instant timestamp(String raw) {
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static InstanceStatus parseInstanceStatus(String raw) {
        if (raw == null) return null;
        try {
            return InstanceStatus.parse(raw);
        } catch (InstanceStatus.UnrecognisedInstanceStatusException e) {
            throw HttpError.badRequest("INVALID_STATUS", "status must be a known instance status");
        }
    }

    private static TriggerKind parseTriggerKind(String raw) {
        if (raw == null) return null;
        try {
            return TriggerKind.parse(raw);
        } catch (TriggerKind.UnrecognisedTriggerKindException e) {
            throw HttpError.badRequest("INVALID_TRIGGER_KIND", "triggerKind must be CRON or MANUAL");
        }
    }

    // ── Wire DTOs (SPA shape, bff spec §7) ──────────────────────────────────

    /// `{value, label}`.
    public record FilterOption(String value, String label) {
    }

    public record FilterOptionsResponse(List<FilterOption> clients, List<FilterOption> applications, List<FilterOption> statuses) {
        public FilterOptionsResponse {
            clients = clients == null ? List.of() : List.copyOf(clients);
            applications = applications == null ? List.of() : List.copyOf(applications);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
        }
    }

    /// `{data, page, size, total, totalPages}` — camelCase `totalPages`,
    /// deliberately NOT [io.flowcatalyst.platform.shared.apicommon.OffsetPage]
    /// (whose `total_pages` is snake_case, the platform API's legacy shape;
    /// the SPA's own `scheduled-jobs.ts` documents the two envelopes differ).
    public record Page<T>(List<T> data, int page, int size, long total, int totalPages) {
        public Page {
            data = data == null ? List.of() : List.copyOf(data);
        }

        static <T> Page<T> of(List<T> data, PageQuery query, long total) {
            int size = query.pageSize();
            int totalPages = size > 0 ? (int) ((total + size - 1) / size) : 0;
            return new Page<>(data, query.pageIndex(), size, total, totalPages);
        }
    }

    public record JobResponse(
            String id,
            String clientId,
            String clientName,
            String applicationId,
            String applicationName,
            String code,
            String name,
            String description,
            String status,
            List<String> crons,
            String timezone,
            JsonNode payload,
            boolean concurrent,
            boolean tracksCompletion,
            Integer timeoutSeconds,
            int deliveryMaxAttempts,
            String targetUrl,
            Instant lastFiredAt,
            Instant createdAt,
            Instant updatedAt,
            int version,
            boolean hasActiveInstance) {

        static JobResponse from(ScheduledJob j, String clientName, String applicationName, boolean hasActiveInstance) {
            return new JobResponse(j.id(), j.clientId(), clientName, j.applicationId(), applicationName, j.code(),
                    j.name(), j.description(), j.status().name(), j.crons(), j.timezone(), j.payload(),
                    j.concurrent(), j.tracksCompletion(), j.timeoutSeconds(), j.deliveryMaxAttempts(), j.targetUrl(),
                    j.lastFiredAt(), j.createdAt(), j.updatedAt(), j.version(), hasActiveInstance);
        }
    }

    public record InstanceResponse(
            String id,
            String scheduledJobId,
            String jobCode,
            String clientId,
            String triggerKind,
            Instant scheduledFor,
            Instant firedAt,
            Instant deliveredAt,
            Instant completedAt,
            String status,
            int deliveryAttempts,
            String deliveryError,
            String completionStatus,
            JsonNode completionResult,
            String correlationId,
            Instant createdAt) {

        static InstanceResponse from(ScheduledJobInstance i) {
            return new InstanceResponse(i.id(), i.scheduledJobId(), i.jobCode(), i.clientId(), i.triggerKind().name(),
                    i.scheduledFor(), i.firedAt(), i.deliveredAt(), i.completedAt(), i.status().name(),
                    i.deliveryAttempts(), i.deliveryError(), i.completionStatus(), i.completionResult(),
                    i.correlationId(), i.createdAt());
        }
    }

    public record InstanceLogResponse(String id, String instanceId, String level, String message, JsonNode metadata,
                                      Instant createdAt) {
        static InstanceLogResponse from(ScheduledJobInstanceLog l) {
            return new InstanceLogResponse(l.id(), l.instanceId(), l.level(), l.message(), l.metadata(), l.createdAt());
        }
    }
}
