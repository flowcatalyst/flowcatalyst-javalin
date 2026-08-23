package io.flowcatalyst.platform.event.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.event.Event;
import io.flowcatalyst.platform.event.EventRepository;
import io.flowcatalyst.platform.event.EventRepository.Facet;
import io.flowcatalyst.platform.event.EventRepository.ListFilter;
import io.flowcatalyst.platform.event.EventRepository.Visibility;
import io.flowcatalyst.platform.shared.apicommon.QueryParams;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.EVENT_VIEW;
import static io.flowcatalyst.platform.shared.auth.Permission.EVENT_VIEW_RAW;

/// The `/api/events` read surface (spec §2) — read-only. Every handler does
/// exactly: coarse permission → repository read → response; there are no use
/// cases on this aggregate. Every handler runs inside [Auth#scoped]. Every
/// route reads the projected `msg_events_read` table.
///
/// | Method | Path | Gate | Status |
/// |---|---|---|---|
/// | GET | `/api/events` | `event:view` | 200 bare array of [EventRead] |
/// | GET | `/api/events/list-raw` | `event:view-raw` | 200 bare array of [EventRead] |
/// | GET | `/api/events/raw` | `event:view-raw` | 200 bare array of [EventRead] (SDK alias) |
/// | GET | `/api/events/filter-options` | `event:view` | 200 [EventFilterOptionsResponse] |
/// | GET | `/api/events/{id}` | `event:view` + client scope | 200 [EventResponse] |
public final class EventApi {

    /// The window of each facet of `/filter-options` (spec §4).
    static final int FACET_LIMIT = 200;

    private EventApi() {
    }

    /// The handlers' dependencies — a repository only; nothing here writes.
    public record State(EventRepository repo) {
        public State {
            Objects.requireNonNull(repo, "repo");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    /// The literal segments are registered before `{id}` so they win.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        Handler raw = Auth.scoped(ctx -> list(ctx, s, EVENT_VIEW_RAW));
        routes.get("/api/events", Auth.scoped(ctx -> list(ctx, s, EVENT_VIEW)));
        routes.get("/api/events/filter-options", Auth.scoped(ctx -> filterOptions(ctx, s)));
        routes.get("/api/events/list-raw", raw);
        routes.get("/api/events/raw", raw); // SDK alias of /list-raw, same handler
        routes.get("/api/events/{id}", Auth.scoped(ctx -> getById(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    /// One handler for the three list routes; only the gate differs (spec §3, §8).
    private static void list(Context ctx, State s, Permission gate) {
        var ac = Auth.current();
        Checks.require(ac, gate);
        var page = Page.from(ctx);
        ctx.json(s.repo().findWithFilters(listFilter(ctx, ac), page.effectiveLimit(), page.offset()).stream().map(EventRead::from).toList());
    }

    private static void getById(Context ctx, State s) {
        var ac = Auth.current();
        Checks.require(ac, EVENT_VIEW);
        String id = ctx.pathParam("id");
        ctx.json(EventResponse.from(visible(ac, s.repo().findById(id).orElseThrow(() -> HttpError.notFound("Event", id)))));
    }

    private static void filterOptions(Context ctx, State s) {
        Checks.require(Auth.current(), EVENT_VIEW);
        ctx.json(new EventFilterOptionsResponse(
                options(s.repo().distinctValues(Facet.APPLICATION, FACET_LIMIT)),
                options(s.repo().distinctValues(Facet.SUBDOMAIN, FACET_LIMIT)),
                options(s.repo().distinctValues(Facet.TYPE, FACET_LIMIT))));
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → filter (spec §3). Absent/empty → no filter; the CSV
    /// lists are trimmed, blanks dropped; `principalId` is accepted and
    /// ignored (no backing column); the caller's visibility is part of the
    /// filter so its own client filters can only narrow within it (spec §8).
    private static ListFilter listFilter(Context ctx, AuthContext ac) {
        return new ListFilter(
                queryParam(ctx, "type"),
                queryParam(ctx, "source"),
                queryParam(ctx, "subject"),
                queryParam(ctx, "clientId"),
                queryParam(ctx, "correlationId"),
                timestamp(queryParam(ctx, "since")),
                timestamp(queryParam(ctx, "until")),
                csv(queryParam(ctx, "types")),
                csv(queryParam(ctx, "clientIds")),
                csv(queryParam(ctx, "applications")),
                csv(queryParam(ctx, "subdomains")),
                csv(queryParam(ctx, "aggregates")),
                visibility(ac));
    }

    /// An anchor sees every row; anyone else platform-scoped rows plus its own clients' (spec §8).
    private static Visibility visibility(AuthContext ac) {
        return ac.isAnchor() ? new Visibility.Everything() : new Visibility.Tenants(ac.clients());
    }

    /// The one-row form of [#visibility] (spec §5): a client-scoped event
    /// outside the caller's clients is 403, not 404.
    private static Event visible(AuthContext ac, Event e) {
        if (e.isPlatformScoped() || ac.canAccessClient(e.clientId())) return e;
        throw HttpError.forbidden("No access to this event");
    }

    /// `limit` / `size` / `offset` as sent (0 = absent): `size` wins when
    /// positive; the repository's guard supplies the default and the
    /// over-max fallback (spec §3, open question 5). A non-integer value is
    /// the [QueryParams] 400 `VALIDATION` envelope listing every bad
    /// parameter, in `limit, offset, size` order.
    record Page(int limit, int offset, int size) {
        static Page from(Context ctx) {
            var errors = new ArrayList<Map<String, Object>>();
            int limit = QueryParams.intParam(ctx, "limit", errors).orElse(0);
            int offset = QueryParams.intParam(ctx, "offset", errors).orElse(0);
            int size = QueryParams.intParam(ctx, "size", errors).orElse(0);
            if (!errors.isEmpty()) throw QueryParams.validation(errors);
            return new Page(limit, offset, size);
        }

        /// The row cap handed to the repository: `size` when positive, else `limit`.
        int effectiveLimit() {
            return size > 0 ? size : limit;
        }
    }

    /// RFC 3339 with any offset → instant; `null` or unparseable → `null` (spec §3, open question 4).
    private static Instant timestamp(String raw) {
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Context ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /// Comma-separated → trimmed, non-blank parts; `null` → empty list (no filter).
    private static List<String> csv(String value) {
        if (value == null) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }

    private static List<EventFilterOption> options(List<String> values) {
        return values.stream().map(v -> new EventFilterOption(v, v)).toList();
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// The slim list row (spec §2): optional fields omitted when absent;
    /// `projectedAt` always present.
    public record EventRead(
            String id,
            String type,
            String source,
            String subject,
            Instant time,
            String application,
            String subdomain,
            String aggregate,
            String messageGroup,
            String correlationId,
            String clientId,
            Instant projectedAt) {

        public static EventRead from(Event e) {
            var p = e.projection();
            return new EventRead(e.id(), e.type(), e.source(), e.subject(), e.time(),
                    p == null ? null : p.application(), p == null ? null : p.subdomain(), p == null ? null : p.aggregate(),
                    e.messageGroup(), e.correlationId(), e.clientId(),
                    p == null ? e.createdAt() : p.projectedAt());
        }
    }

    /// The full envelope (spec §2). `specVersion`, `subject` and
    /// `deduplicationId` are required on the wire and emitted as `""` for a
    /// `NULL` column (open question 2); `data` omitted when absent;
    /// `contextData` omitted when empty.
    public record EventResponse(
            String id,
            String specVersion,
            String type,
            String source,
            String subject,
            Instant time,
            JsonNode data,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ContextEntryDTO> contextData,
            String deduplicationId,
            String clientId,
            String messageGroup,
            String correlationId,
            String causationId,
            String application,
            String subdomain,
            String aggregate,
            Instant projectedAt,
            Instant createdAt) {

        public EventResponse {
            contextData = contextData == null ? List.of() : List.copyOf(contextData);
        }

        public static EventResponse from(Event e) {
            var p = e.projection();
            return new EventResponse(e.id(), orEmpty(e.specVersion()), e.type(), e.source(), orEmpty(e.subject()), e.time(),
                    e.data(), e.context().stream().map(ContextEntryDTO::from).toList(),
                    orEmpty(e.deduplicationId()), e.clientId(), e.messageGroup(), e.correlationId(), e.causationId(),
                    p == null ? null : p.application(), p == null ? null : p.subdomain(), p == null ? null : p.aggregate(),
                    p == null ? null : p.projectedAt(), e.createdAt());
        }

        /// The wire's `""` for a `NULL` column — the DTO is the one place the mapping lives.
        private static String orEmpty(String v) {
            return v == null ? "" : v;
        }
    }

    /// `{key, value}`.
    public record ContextEntryDTO(String key, String value) {
        static ContextEntryDTO from(Event.ContextEntry c) {
            return new ContextEntryDTO(c.key(), c.value());
        }
    }

    /// `{value, label}` — both the facet value.
    public record EventFilterOption(String value, String label) {
    }

    /// `{applications, subdomains, eventTypes}` — arrays never `null`.
    public record EventFilterOptionsResponse(
            List<EventFilterOption> applications,
            List<EventFilterOption> subdomains,
            List<EventFilterOption> eventTypes) {

        public EventFilterOptionsResponse {
            applications = applications == null ? List.of() : List.copyOf(applications);
            subdomains = subdomains == null ? List.of() : List.copyOf(subdomains);
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        }
    }
}
