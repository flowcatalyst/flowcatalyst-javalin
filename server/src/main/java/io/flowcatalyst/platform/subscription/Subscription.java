package io.flowcatalyst.platform.subscription;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The subscription aggregate root (spec: `docs/spec/subscription.md`,
/// `docs/spec/code-first-connections.md` §2). A subscription binds
/// event-type patterns to a delivery endpoint (optionally through a
/// connection) with the dispatch settings the router applies. It is unique
/// per `(applicationCode, clientId, code)`, `NULL` a real value on both
/// nullable parts — not per `(code, clientId)` alone; a `null` client means
/// platform-wide, a `null` applicationCode means shared (no application).
///
/// Immutable record: each transition returns a copy, so an operation is just
/// load → transition → event. The two status transitions are unconditional
/// flips (spec §2, open question 9). The repository persists whatever copy
/// it is handed and stamps `updatedAt` itself.
///
/// `code` must already be in its stored form when handed to [#create]: the
/// admin create normalises and validates through [SubscriptionCode#parse],
/// the SDK sync stores the code verbatim (spec §7, open question 3).
///
/// @param id               `sub_…` TSID
/// @param code             unique per client; immutable after create
/// @param applicationCode  owning SDK application; set by sync only (`null` for admin creates)
/// @param name             human-readable name
/// @param description      optional description
/// @param clientId         optional client scope; `null` = platform-wide
/// @param clientIdentifier optional; read and written but never set by an operation (spec §1)
/// @param clientScoped     read and written, always `false` today (spec §1)
/// @param eventTypes       the patterns, in stored order; replaced wholesale on update
/// @param connectionId     optional delivery connection (not a foreign key)
/// @param endpoint         delivery URL (column `target`)
/// @param queue            optional dispatch priority, `DEFAULT` / `HIGH_PRIORITY`
///                          (ruling R1); `null` = not set. Stored verbatim —
///                          a legacy row may hold other text (ruling R6),
///                          which only the publish path, not this record,
///                          treats as `DEFAULT`
/// @param customConfig     free-form key/values; replaced wholesale on update
/// @param source           who authored the row — decides whether sync may touch it
/// @param status           `ACTIVE` | `PAUSED`
/// @param maxAgeSeconds    a message older than this is not delivered (default [#DEFAULT_MAX_AGE_SECONDS])
/// @param dispatchPoolId   optional pool id
/// @param dispatchPoolCode optional pool code (set by sync resolution only)
/// @param delaySeconds     delivery delay (default [#DEFAULT_DELAY_SECONDS])
/// @param sequence         ordering hint, never changed today (default [#DEFAULT_SEQUENCE])
/// @param mode             router ordering mode (default [DispatchMode#DEFAULT], `NEXT_ON_ERROR` — ledger `X-01`)
/// @param timeoutSeconds   per-delivery timeout (default [#DEFAULT_TIMEOUT_SECONDS])
/// @param maxRetries       retry budget (default [#DEFAULT_MAX_RETRIES])
/// @param serviceAccountId optional signing/authenticating service account (not validated)
/// @param dataOnly         deliver only the event's `data` (default [#DEFAULT_DATA_ONLY])
/// @param createdBy        creating principal, `null` for pre-audit rows
/// @param createdAt        creation time
/// @param updatedAt        last change
public record Subscription(
        String id,
        String code,
        String applicationCode,
        String name,
        String description,
        String clientId,
        String clientIdentifier,
        boolean clientScoped,
        List<EventTypeBinding> eventTypes,
        String connectionId,
        String endpoint,
        String queue,
        List<ConfigEntry> customConfig,
        SubscriptionSource source,
        SubscriptionStatus status,
        int maxAgeSeconds,
        String dispatchPoolId,
        String dispatchPoolCode,
        int delaySeconds,
        int sequence,
        DispatchMode mode,
        int timeoutSeconds,
        int maxRetries,
        String serviceAccountId,
        boolean dataOnly,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public static final int DEFAULT_MAX_AGE_SECONDS = 86_400;
    public static final int DEFAULT_DELAY_SECONDS = 0;
    public static final int DEFAULT_SEQUENCE = 99;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final boolean DEFAULT_DATA_ONLY = true;
    public static final DispatchMode DEFAULT_MODE = DispatchMode.DEFAULT;

    public Subscription {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        Objects.requireNonNull(endpoint, "endpoint");
        customConfig = customConfig == null ? List.of() : List.copyOf(customConfig);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `ACTIVE`, `UI`-sourced, platform-wide subscription with no
    /// bindings, no config and every dispatch setting at its default (spec §1).
    public static Subscription create(String code, String name, String endpoint) {
        Instant now = Instant.now();
        return new Subscription(EntityType.SUBSCRIPTION.generate(), code, null, name, null, null, null, false,
                List.of(), null, endpoint, null, List.of(), SubscriptionSource.UI, SubscriptionStatus.ACTIVE,
                DEFAULT_MAX_AGE_SECONDS, null, null, DEFAULT_DELAY_SECONDS, DEFAULT_SEQUENCE, DEFAULT_MODE,
                DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_RETRIES, null, DEFAULT_DATA_ONLY, null, now, now);
    }

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE;
    }

    public boolean isPaused() {
        return status == SubscriptionStatus.PAUSED;
    }

    // ── Matching (spec §1) ─────────────────────────────────────────────────

    /// Whether any binding's pattern matches `eventTypeCode`.
    public boolean matchesEventType(String eventTypeCode) {
        return eventTypes.stream().anyMatch(b -> b.matches(eventTypeCode));
    }

    /// Whether this subscription accepts events from `eventClientId`: a
    /// platform-wide subscription accepts every event (client-less ones
    /// included); a client-bound one accepts only events carrying its client.
    public boolean matchesClient(String eventClientId) {
        if (clientId == null) return true;
        return clientId.equals(eventClientId);
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// → `PAUSED`. Idempotent: pausing a paused subscription is not an error
    /// (spec §2, open question 9).
    public Subscription pause() {
        return withStatus(SubscriptionStatus.PAUSED);
    }

    /// → `ACTIVE`. Idempotent (spec §2, open question 9).
    public Subscription resume() {
        return withStatus(SubscriptionStatus.ACTIVE);
    }

    private Subscription withStatus(SubscriptionStatus newStatus) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, newStatus, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, Instant.now());
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    public Subscription withApplicationCode(String newApplicationCode) {
        return new Subscription(id, code, newApplicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withName(String newName) {
        return new Subscription(id, code, applicationCode, newName, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withDescription(String newDescription) {
        return new Subscription(id, code, applicationCode, name, newDescription, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withClientId(String newClientId) {
        return new Subscription(id, code, applicationCode, name, description, newClientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    /// Replaces every binding (spec §3: wholesale, an empty list is allowed).
    public Subscription withEventTypes(List<EventTypeBinding> newEventTypes) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                newEventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    /// `null` clears (sync re-points or clears it every run — spec §7).
    public Subscription withConnectionId(String newConnectionId) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, newConnectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withEndpoint(String newEndpoint) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, newEndpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    /// Replaces every config entry (wholesale).
    public Subscription withCustomConfig(List<ConfigEntry> newCustomConfig) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, newCustomConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withSource(SubscriptionSource newSource) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, newSource, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withMaxAgeSeconds(int newMaxAgeSeconds) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, newMaxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    /// Points at a pool by id only — the admin surface never learns the code (spec open question 6).
    public Subscription withDispatchPoolId(String newDispatchPoolId) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                newDispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    /// Points at a resolved pool — id and code together (sync, spec §7).
    public Subscription withDispatchPool(String newDispatchPoolId, String newDispatchPoolCode) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                newDispatchPoolId, newDispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withDelaySeconds(int newDelaySeconds) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, newDelaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withMode(DispatchMode newMode) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, newMode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    /// Sets the dispatch priority (ruling R1); the caller — [CreateSubscription]
    /// / [UpdateSubscription] — validates through
    /// [io.flowcatalyst.platform.shared.dispatch.QueuePriority#parse] and passes
    /// its normalised name or `null`. This wither itself does not validate, so
    /// it can still carry a legacy row's non-conforming text (ruling R6)
    /// unchanged — the publish path, not this record, coerces that to
    /// `DEFAULT`.
    public Subscription withQueue(String newQueue) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, newQueue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withTimeoutSeconds(int newTimeoutSeconds) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, newTimeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withMaxRetries(int newMaxRetries) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, newMaxRetries,
                serviceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withServiceAccountId(String newServiceAccountId) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                newServiceAccountId, dataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withDataOnly(boolean newDataOnly) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, newDataOnly, createdBy, createdAt, updatedAt);
    }

    public Subscription withCreatedBy(String principalId) {
        return new Subscription(id, code, applicationCode, name, description, clientId, clientIdentifier, clientScoped,
                eventTypes, connectionId, endpoint, queue, customConfig, source, status, maxAgeSeconds,
                dispatchPoolId, dispatchPoolCode, delaySeconds, sequence, mode, timeoutSeconds, maxRetries,
                serviceAccountId, dataOnly, principalId, createdAt, updatedAt);
    }
}
