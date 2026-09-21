package io.flowcatalyst.platform.subscription.operations;

import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.connection.ConnectionRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.SubscriptionSource;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionCreated;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionDeleted;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionUpdated;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents.SubscriptionsSynced;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.SyncDelete;
import io.flowcatalyst.sdk.usecase.jdbc.SyncSave;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Bulk-upserts an application SDK's subscription catalogue, scoped to
/// `(applicationCode, clientId)`, in one transaction (spec §7,
/// `docs/spec/code-first-connections.md` §3). Rows are matched by code among
/// the subscriptions owned by `(applicationCode, clientId)` — `NULL` client
/// matches `NULL` only
/// ([SubscriptionRepository#findByApplicationAndClient]): `API`/`CODE`-sourced
/// matches are updated, `UI`-authored matches are skipped, new codes are
/// created `API`-sourced with the request's client, and with `removeUnlisted`
/// the owned `API`/`CODE` rows absent from the batch are hard-deleted. One
/// per-row event per row touched plus one [SubscriptionsSynced] rollup.
///
/// Authorization is resource-level against the application the sync is
/// scoped to, and — when `clientId` is given — the client too
/// ([Access#checkSyncAccess]); the coarse sync permission and the
/// `appCode`/`clientId` resolution belong to the sdksync handler.
public final class SyncSubscriptions {

    private SyncSubscriptions() {
    }

    public static Operation<SyncSubscriptionsCommand, SubscriptionsSynced> of(
            SubscriptionRepository repo, ConnectionRepository connections, DispatchPoolRepository pools) {
        return Operation.<SyncSubscriptionsCommand, SubscriptionsSynced>named("SyncSubscriptions")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED",
                            "Application code is required");
                    cmd.subscriptions().forEach(SyncSubscriptions::validateInput);
                })
                .authorize(cmd -> Access.checkSyncAccess(cmd.applicationId(), cmd.applicationCode(), cmd.clientId()))
                .execute((cmd, ec) -> {
                    List<SyncSubscriptionInput> subs = cmd.subscriptions();

                    // Resolve every subscription's connection to an id FIRST (spec §3):
                    // wherever it is named, its scope must be consistent with this
                    // sync's client before anything is written.
                    String[] resolvedConnectionIds = new String[subs.size()];
                    for (int i = 0; i < subs.size(); i++) {
                        resolvedConnectionIds[i] = resolveConnectionId(subs.get(i), cmd, connections);
                    }

                    Map<String, Subscription> existingByCode = repo.findByApplicationAndClient(cmd.applicationCode(), cmd.clientId()).stream()
                            .collect(Collectors.toMap(Subscription::code, Function.identity(), (a, _) -> a, LinkedHashMap::new));
                    Set<String> incomingCodes = subs.stream()
                            .map(SyncSubscriptionInput::code).collect(Collectors.toSet());

                    var saves = new ArrayList<SyncSave<Subscription>>(subs.size());
                    var deletes = new ArrayList<SyncDelete<Subscription>>();
                    int created = 0;
                    int updated = 0;
                    for (int i = 0; i < subs.size(); i++) {
                        SyncSubscriptionInput in = subs.get(i);
                        String resolvedConnectionId = resolvedConnectionIds[i];
                        Subscription existing = existingByCode.get(in.code());
                        if (existing != null) {
                            if (!existing.source().isSyncManaged()) continue; // UI-authored rows are never touched
                            Subscription s = applyInput(existing, in, resolvedConnectionId, pools);
                            saves.add(new SyncSave<>(s, SubscriptionUpdated.of(ec, s)));
                            updated++;
                        } else {
                            Subscription s = applyInput(Subscription.create(in.code(), in.name(), in.target())
                                    .withApplicationCode(cmd.applicationCode())
                                    .withClientId(cmd.clientId())
                                    .withSource(SubscriptionSource.API)
                                    .withCreatedBy(ec.principalId()), in, resolvedConnectionId, pools);
                            saves.add(new SyncSave<>(s, SubscriptionCreated.of(ec, s)));
                            created++;
                        }
                    }
                    if (cmd.removeUnlisted()) {
                        existingByCode.values().stream()
                                .filter(s -> s.source().isSyncManaged() && !incomingCodes.contains(s.code()))
                                .forEach(s -> deletes.add(new SyncDelete<>(s, SubscriptionDeleted.of(ec, s))));
                    }

                    List<String> syncedCodes = subs.stream().map(SyncSubscriptionInput::code).toList();
                    var rollup = SubscriptionsSynced.of(ec, cmd.applicationCode(), cmd.clientId(),
                            created, updated, deletes.size(), syncedCodes);
                    return Plan.sync(repo, saves, deletes, rollup);
                });
    }

    /// The sync row rules (spec §4): presence only — no code normalisation, no
    /// URL format check. `sharedConnection` requires `connectionCode`
    /// (`code-first-connections.md` §3).
    private static void validateInput(SyncSubscriptionInput in) {
        UseCaseException.requireNonBlank(in.code(), "CODE_REQUIRED", "Subscription code is required");
        UseCaseException.requireNonBlank(in.name(), "NAME_REQUIRED", "Subscription name is required");
        UseCaseException.requireNonBlank(in.target(), "TARGET_REQUIRED", "Target endpoint URL is required");
        if (in.eventTypes().isEmpty()) {
            throw UseCaseException.validation("EVENT_TYPES_REQUIRED", "At least one event type is required");
        }
        if (in.sharedConnection() && (in.connectionCode() == null || in.connectionCode().isBlank())) {
            throw UseCaseException.validation("SHARED_CONNECTION_REQUIRES_CODE",
                    "Subscription '" + in.code() + "': sharedConnection requires connectionCode");
        }
    }

    /// Resolves one input's connection to an id, however it was named
    /// (`code-first-connections.md` §3):
    ///
    ///   - `connectionCode` set: the namespace is EXPLICIT, never guessed — a
    ///     bare code names a connection owned by THIS application;
    ///     `sharedConnection` switches to the shared (application-less)
    ///     namespace. No fallback between the two. Within that namespace, a
    ///     client-scoped sync prefers its own client's connection, falling
    ///     back to a global one; a client-less sync only ever resolves a
    ///     global connection (the fallback lookup below IS that resolution
    ///     when `cmd.clientId()` is `null`, since `findByCode(..., null)`
    ///     only matches a `NULL` `client_id`). Not found → `404
    ///     CONNECTION_NOT_FOUND`. When `connectionId` is ALSO given, it must
    ///     name the same connection → `400 CONNECTION_MISMATCH`.
    ///   - `connectionId` only: an id can name ANY row, so its scope needs an
    ///     explicit check — its client must be absent or equal to this
    ///     sync's client (`400 CONNECTION_SCOPE_MISMATCH`: a global sync may
    ///     never point at a client-owned connection), and its application
    ///     must be absent (shared, usable by anyone) or equal to this sync's
    ///     application (`400 CONNECTION_SCOPE_MISMATCH`: a connection signs
    ///     deliveries with its application's credentials, so an id must not
    ///     reach across).
    ///   - Neither given: `null` (clears any existing link).
    private static String resolveConnectionId(SyncSubscriptionInput in, SyncSubscriptionsCommand cmd, ConnectionRepository connections) {
        if (in.connectionCode() != null && !in.connectionCode().isBlank()) {
            String code = in.connectionCode().strip();
            String namespaceAppCode = in.sharedConnection() ? null : cmd.applicationCode();

            Connection c = null;
            if (cmd.clientId() != null) {
                c = connections.findByCode(code, namespaceAppCode, cmd.clientId()).orElse(null);
            }
            if (c == null) {
                c = connections.findByCode(code, namespaceAppCode, null).orElse(null);
            }
            if (c == null) {
                throw UseCaseException.notFound("CONNECTION_NOT_FOUND", "Connection with code '" + code + "' not found");
            }
            if (in.connectionId() != null && !in.connectionId().equals(c.id())) {
                throw UseCaseException.validation("CONNECTION_MISMATCH",
                        "Subscription '" + in.code() + "': connectionId and connectionCode name different connections");
            }
            return c.id();
        }
        if (in.connectionId() == null) {
            return null;
        }
        Connection c = connections.findById(in.connectionId())
                .orElseThrow(() -> UseCaseException.notFound("CONNECTION_NOT_FOUND", "Connection '" + in.connectionId() + "' not found"));
        if (c.clientId() != null && (cmd.clientId() == null || !c.clientId().equals(cmd.clientId()))) {
            throw UseCaseException.validation("CONNECTION_SCOPE_MISMATCH",
                    "Subscription '" + in.code() + "': connection '" + in.connectionId() + "' is scoped to a different client");
        }
        if (c.applicationCode() != null && !c.applicationCode().equals(cmd.applicationCode())) {
            throw UseCaseException.validation("CONNECTION_SCOPE_MISMATCH",
                    "Subscription '" + in.code() + "': connection '" + in.connectionId() + "' belongs to a different application");
        }
        return in.connectionId();
    }

    /// Writes a batch row onto a subscription (spec §7): name, description,
    /// endpoint, connection (cleared when absent), bindings and `dataOnly`
    /// are replaced; `maxRetries` / `timeoutSeconds` only when present; the
    /// dispatch pool is re-resolved only when a code is given; `mode` is
    /// deliberately ignored.
    private static Subscription applyInput(Subscription s, SyncSubscriptionInput in, String resolvedConnectionId, DispatchPoolRepository pools) {
        Subscription out = s.withName(in.name())
                .withDescription(in.description())
                .withEndpoint(in.target())
                .withConnectionId(resolvedConnectionId)
                .withEventTypes(in.eventTypes().stream().map(SyncEventTypeBindingInput::toBinding).toList())
                .withDataOnly(in.dataOnly());
        if (in.maxRetries() != null) out = out.withMaxRetries(in.maxRetries());
        if (in.timeoutSeconds() != null) out = out.withTimeoutSeconds(in.timeoutSeconds());
        if (in.dispatchPoolCode() != null && !in.dispatchPoolCode().isBlank()) {
            // An unknown code is silently left as is (spec open question 6).
            var pool = pools.findByCode(in.dispatchPoolCode(), null);
            if (pool.isPresent()) out = out.withDispatchPool(pool.get().id(), pool.get().code());
        }
        return out;
    }
}
