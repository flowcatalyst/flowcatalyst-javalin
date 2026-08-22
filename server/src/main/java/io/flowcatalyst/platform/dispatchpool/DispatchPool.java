package io.flowcatalyst.platform.dispatchpool;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.Objects;

/// The dispatch-pool aggregate root (spec: `docs/spec/dispatchpool.md`). A
/// pool is a named bucket of rate-limit + concurrency settings the router
/// applies when dispatching; it is unique per `(code, clientId)` and a
/// `null` client means platform-wide.
///
/// Immutable record: each transition returns a copy. The three status
/// transitions are unconditional flips (spec §2, open question 1) — there is
/// no state they refuse from. The repository persists whatever copy it is
/// handed and stamps `updatedAt` itself.
///
/// @param id               `dpl_…` TSID
/// @param code             `^[a-z][a-z0-9_-]*$`, unique per client
/// @param name             human-readable name
/// @param description      optional description
/// @param rateLimit        messages per minute; `null` = no rate limiter, concurrency-only
/// @param concurrency      max concurrent dispatches (default [#DEFAULT_CONCURRENCY])
/// @param clientId         owning client; `null` = platform-wide
/// @param clientIdentifier denormalised client identifier; read-only here (spec §1)
/// @param status           routing-eligibility state
/// @param createdAt        creation time
/// @param updatedAt        last change
public record DispatchPool(
        String id,
        String code,
        String name,
        String description,
        Integer rateLimit,
        int concurrency,
        String clientId,
        String clientIdentifier,
        DispatchPoolStatus status,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public static final int DEFAULT_CONCURRENCY = 10;

    public DispatchPool {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `ACTIVE`, platform-wide pool with the default concurrency and
    /// no rate limit. `code` must already be in its stored form (see
    /// [DispatchPoolCode]).
    ///
    /// @throws UseCaseException validation `INVALID_CODE_FORMAT` (see [DispatchPoolCode#parse])
    public static DispatchPool create(String code, String name) {
        String validated = DispatchPoolCode.parse(code).value();
        Instant now = Instant.now();
        return new DispatchPool(EntityType.DISPATCH_POOL.generate(), validated, name, null, null, DEFAULT_CONCURRENCY,
                null, null, DispatchPoolStatus.ACTIVE, now, now);
    }

    public boolean isArchived() {
        return status == DispatchPoolStatus.ARCHIVED;
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// → `SUSPENDED`: dispatch into the pool pauses; in-flight work is untouched.
    public DispatchPool suspend() {
        return withStatus(DispatchPoolStatus.SUSPENDED);
    }

    /// → `ACTIVE`: the pool is routable again.
    public DispatchPool activate() {
        return withStatus(DispatchPoolStatus.ACTIVE);
    }

    /// → `ARCHIVED`: retired (soft — the row stays; sync's `removeUnlisted` lands here).
    public DispatchPool archive() {
        return withStatus(DispatchPoolStatus.ARCHIVED);
    }

    private DispatchPool withStatus(DispatchPoolStatus newStatus) {
        return new DispatchPool(id, code, name, description, rateLimit, concurrency, clientId, clientIdentifier,
                newStatus, createdAt, Instant.now());
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    public DispatchPool withName(String newName) {
        return new DispatchPool(id, code, newName, description, rateLimit, concurrency, clientId, clientIdentifier,
                status, createdAt, updatedAt);
    }

    public DispatchPool withDescription(String newDescription) {
        return new DispatchPool(id, code, name, newDescription, rateLimit, concurrency, clientId, clientIdentifier,
                status, createdAt, updatedAt);
    }

    /// `null` clears the limiter (concurrency-only).
    public DispatchPool withRateLimit(Integer newRateLimit) {
        return new DispatchPool(id, code, name, description, newRateLimit, concurrency, clientId, clientIdentifier,
                status, createdAt, updatedAt);
    }

    public DispatchPool withConcurrency(int newConcurrency) {
        return new DispatchPool(id, code, name, description, rateLimit, newConcurrency, clientId, clientIdentifier,
                status, createdAt, updatedAt);
    }

    public DispatchPool withClientId(String newClientId) {
        return new DispatchPool(id, code, name, description, rateLimit, concurrency, newClientId, clientIdentifier,
                status, createdAt, updatedAt);
    }
}
