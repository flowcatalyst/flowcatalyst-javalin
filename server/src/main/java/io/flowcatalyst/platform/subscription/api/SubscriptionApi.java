package io.flowcatalyst.platform.subscription.api;

import io.flowcatalyst.platform.shared.apicommon.CreatedResponse;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.subscription.ConfigEntry;
import io.flowcatalyst.platform.subscription.EventTypeBinding;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.SubscriptionRepository.ListFilter;
import io.flowcatalyst.platform.subscription.operations.CreateCommand;
import io.flowcatalyst.platform.subscription.operations.CreateSubscription;
import io.flowcatalyst.platform.subscription.operations.DeleteCommand;
import io.flowcatalyst.platform.subscription.operations.DeleteSubscription;
import io.flowcatalyst.platform.subscription.operations.PauseCommand;
import io.flowcatalyst.platform.subscription.operations.PauseSubscription;
import io.flowcatalyst.platform.subscription.operations.ResumeCommand;
import io.flowcatalyst.platform.subscription.operations.ResumeSubscription;
import io.flowcatalyst.platform.subscription.operations.UpdateCommand;
import io.flowcatalyst.platform.subscription.operations.UpdateSubscription;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.*;

/// The `/api/subscriptions` surface (spec §3). A write handler does exactly:
/// coarse permission → command from DTO → `Operation.run` → response. Reads
/// go straight to the repository and apply the client-scope visibility rule
/// here. Every handler runs inside [Auth#scoped] so the operations can read
/// [Auth#current()]. The application-scoped sync lives in the sdksync surface.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/subscriptions` | 200 [SubscriptionListResponse] |
/// | POST | `/api/subscriptions` | 201 [CreatedResponse] |
/// | GET | `/api/subscriptions/{id}` | 200 [SubscriptionResponse] |
/// | PUT | `/api/subscriptions/{id}` | 204 |
/// | DELETE | `/api/subscriptions/{id}` | 204 |
/// | POST | `/api/subscriptions/{id}/pause` | 204 |
/// | POST | `/api/subscriptions/{id}/resume` | 204 |
public final class SubscriptionApi {

    private SubscriptionApi() {
    }

    /// The handlers' dependencies.
    public record State(SubscriptionRepository repo, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(uow, "uow");
        }
    }

    /// Mounts the endpoints; paths, methods and status codes are the lockfile's.
    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/subscriptions", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/subscriptions", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/subscriptions/{id}", Auth.scoped(ctx -> getById(ctx, s)));
        write.put("/api/subscriptions/{id}", Auth.scoped(ctx -> update(ctx, s)));
        write.delete("/api/subscriptions/{id}", Auth.scoped(ctx -> delete(ctx, s)));
        write.post("/api/subscriptions/{id}/pause", Auth.scoped(ctx -> pause(ctx, s)));
        write.post("/api/subscriptions/{id}/resume", Auth.scoped(ctx -> resume(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SUBSCRIPTION_VIEW);
        List<Subscription> visible = Checks.filterClientScoped(ac, s.repo().findWithFilters(listFilter(ctx)), Subscription::clientId);
        ctx.json(SubscriptionListResponse.from(visible));
    }

    private static void getById(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, SUBSCRIPTION_VIEW);
        ctx.json(SubscriptionResponse.from(visible(ac, load(s, ctx.pathParam("id")))));
    }

    private static void create(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SUBSCRIPTION_CREATE, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE);
        var cmd = ctx.bodyAsClass(CreateSubscriptionRequest.class).toCommand();
        var event = CreateSubscription.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(201).json(new CreatedResponse(event.subscriptionId()));
    }

    private static void update(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SUBSCRIPTION_CREATE, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE);
        var cmd = ctx.bodyAsClass(UpdateSubscriptionRequest.class).toCommand(ctx.pathParam("id"));
        UpdateSubscription.of(s.repo()).run(s.uow(), cmd, Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Exchange ctx, State s) {
        Checks.require(Auth.current(), SUBSCRIPTION_DELETE);
        DeleteSubscription.of(s.repo()).run(s.uow(), new DeleteCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void pause(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SUBSCRIPTION_CREATE, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE);
        PauseSubscription.of(s.repo()).run(s.uow(), new PauseCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    private static void resume(Exchange ctx, State s) {
        Checks.requireAny(Auth.current(), SUBSCRIPTION_CREATE, SUBSCRIPTION_UPDATE, SUBSCRIPTION_DELETE);
        ResumeSubscription.of(s.repo()).run(s.uow(), new ResumeCommand(ctx.pathParam("id")), Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Query params → filter; both are plain equality filters, no defaults.
    private static ListFilter listFilter(Exchange ctx) {
        return new ListFilter(queryParam(ctx, "status"), queryParam(ctx, "clientId"));
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static Subscription load(State s, String id) {
        return s.repo().findById(id).orElseThrow(() -> HttpError.notFound("Subscription", id));
    }

    /// A client-bound subscription is visible only to principals with access to that client.
    private static Subscription visible(AuthContext ac, Subscription s) {
        if (s.clientId() != null && !ac.canAccessClient(s.clientId())) {
            throw HttpError.forbidden("No access to this subscription");
        }
        return s;
    }

    // ── Wire DTOs (lockfile components) ────────────────────────────────────

    /// `EventTypeBindingDTO`: optional fields omitted when `null`. An absent
    /// `eventTypeCode` is stored as `""` — the column is `NOT NULL`, the
    /// pattern is not validated, and that is what the row looked like before
    /// this port (spec §4, open question 10).
    public record EventTypeBindingDto(String eventTypeId, String eventTypeCode, String specVersion, String filter) {
        public EventTypeBinding toEntity() {
            return new EventTypeBinding(eventTypeId, eventTypeCode == null ? "" : eventTypeCode, specVersion, filter);
        }

        public static EventTypeBindingDto from(EventTypeBinding b) {
            return new EventTypeBindingDto(b.eventTypeId(), b.eventTypeCode(), b.specVersion(), b.filter());
        }
    }

    /// `ConfigEntryDTO`. An absent `key` / `value` is stored as `""` (both
    /// columns are `NOT NULL`; spec §4, open question 10).
    public record ConfigEntryDto(String key, String value) {
        public ConfigEntry toEntity() {
            return new ConfigEntry(key == null ? "" : key, value == null ? "" : value);
        }

        public static ConfigEntryDto from(ConfigEntry c) {
            return new ConfigEntryDto(c.key(), c.value());
        }
    }

    /// Body of `POST /api/subscriptions`; absent settings take the aggregate defaults.
    public record CreateSubscriptionRequest(
            String code,
            String name,
            String endpoint,
            String description,
            String clientId,
            String connectionId,
            String dispatchPoolId,
            String serviceAccountId,
            List<EventTypeBindingDto> eventTypes,
            List<ConfigEntryDto> customConfig,
            String mode,
            String queue,
            Integer timeoutSeconds,
            Integer maxRetries,
            Integer delaySeconds,
            Integer maxAgeSeconds,
            Boolean dataOnly) {

        public CreateCommand toCommand() {
            return new CreateCommand(code, name, endpoint, description, clientId, connectionId, dispatchPoolId,
                    serviceAccountId, bindings(eventTypes), entries(customConfig), mode, queue, timeoutSeconds, maxRetries,
                    delaySeconds, maxAgeSeconds, dataOnly);
        }
    }

    /// Body of `PUT /api/subscriptions/{id}`; the path id is authoritative,
    /// every field optional (absent = unchanged, an explicit `[]` replaces).
    public record UpdateSubscriptionRequest(
            String name,
            String description,
            String endpoint,
            String connectionId,
            List<EventTypeBindingDto> eventTypes,
            List<ConfigEntryDto> customConfig,
            String mode,
            String queue,
            Integer timeoutSeconds,
            Integer maxRetries,
            Integer delaySeconds,
            Integer maxAgeSeconds,
            String dispatchPoolId,
            String serviceAccountId,
            Boolean dataOnly) {

        public UpdateCommand toCommand(String id) {
            return new UpdateCommand(id, name, description, endpoint, connectionId, bindings(eventTypes),
                    entries(customConfig), mode, queue, timeoutSeconds, maxRetries, delaySeconds, maxAgeSeconds,
                    dispatchPoolId, serviceAccountId, dataOnly);
        }
    }

    /// `null` stays `null` (absent on the wire), so an update can tell "unchanged" from "replace with empty".
    private static List<EventTypeBinding> bindings(List<EventTypeBindingDto> dtos) {
        return dtos == null ? null : dtos.stream().map(EventTypeBindingDto::toEntity).toList();
    }

    private static List<ConfigEntry> entries(List<ConfigEntryDto> dtos) {
        return dtos == null ? null : dtos.stream().map(ConfigEntryDto::toEntity).toList();
    }

    /// The wire shape of one subscription (spec §3, field order as the SPA
    /// has always seen it); optional fields are omitted when `null`, the two
    /// arrays are always present.
    public record SubscriptionResponse(
            String id,
            String code,
            String applicationCode,
            String name,
            String description,
            String clientId,
            String clientIdentifier,
            boolean clientScoped,
            List<EventTypeBindingDto> eventTypes,
            String connectionId,
            String endpoint,
            String queue,
            List<ConfigEntryDto> customConfig,
            String source,
            String status,
            int maxAgeSeconds,
            String dispatchPoolId,
            String dispatchPoolCode,
            int delaySeconds,
            int sequence,
            String mode,
            int timeoutSeconds,
            int maxRetries,
            String serviceAccountId,
            boolean dataOnly,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {

        public SubscriptionResponse {
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
            customConfig = customConfig == null ? List.of() : List.copyOf(customConfig);
        }

        public static SubscriptionResponse from(Subscription s) {
            return new SubscriptionResponse(s.id(), s.code(), s.applicationCode(), s.name(), s.description(),
                    s.clientId(), s.clientIdentifier(), s.clientScoped(),
                    s.eventTypes().stream().map(EventTypeBindingDto::from).toList(),
                    s.connectionId(), s.endpoint(), s.queue(),
                    s.customConfig().stream().map(ConfigEntryDto::from).toList(),
                    s.source().name(), s.status().name(), s.maxAgeSeconds(), s.dispatchPoolId(), s.dispatchPoolCode(),
                    s.delaySeconds(), s.sequence(), s.mode().name(), s.timeoutSeconds(), s.maxRetries(),
                    s.serviceAccountId(), s.dataOnly(), s.createdBy(), s.createdAt(), s.updatedAt());
        }
    }

    /// `{"subscriptions": [...], "total": n}` — `total` is the size of the
    /// visible list; no pagination on this endpoint.
    public record SubscriptionListResponse(List<SubscriptionResponse> subscriptions, long total) {
        public SubscriptionListResponse {
            subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
        }

        public static SubscriptionListResponse from(List<Subscription> visible) {
            var items = visible.stream().map(SubscriptionResponse::from).toList();
            return new SubscriptionListResponse(items, items.size());
        }
    }
}
