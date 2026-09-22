package io.flowcatalyst.platform.serviceaccount;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/// An application's resolved outbound credentials — the bearer token and/or
/// HMAC signing secret of the application's **oldest active service
/// account**. Either field may be independently absent — a degraded case the
/// caller logs a WARN for and still delivers. `reason`/`signedBy` are set
/// only by [#resolveById] (below); [#resolve] leaves them `null` — its
/// callers (the scheduled-job dispatcher) never read either, and building a
/// reason for "no active account" needs the application's *code*, which this
/// by-id method never sees, only the caller does
/// ([io.flowcatalyst.platform.dispatchjob.processing.DeliveryCredentials]).
///
/// Shared by two callers, deliberately one resolver: the scheduled-job
/// dispatcher (`docs/spec/scheduled-job-scheduler.md` §3 step 5,
/// [io.flowcatalyst.platform.scheduler.jobs.JobDispatcher]) and the dispatch-job
/// processing endpoint (`docs/spec/dispatch-delivery-credentials.md` §2,
/// [io.flowcatalyst.platform.dispatchjob.processing.DeliveryCredentials#resolve]).
/// Moved here from `platform.scheduler.jobs` (2026-09-19) — a neutral package
/// both callers can depend on without one owning the other — when the second
/// caller needed the exact same resolve-then-cache behaviour rather than a
/// second implementation of it.
public record OutboundCredentials(String token, String signingSecret, String reason, String signedBy) {

    /// Convenience constructor for every pre-2026-09-22 caller (the
    /// scheduled-job dispatcher, its tests): `reason`/`signedBy` default to
    /// `null` — untouched by anything that only ever reads [#token]/[#signingSecret].
    public OutboundCredentials(String token, String signingSecret) {
        this(token, signingSecret, null, null);
    }

    /// Neither credential is set.
    public boolean isEmpty() {
        return isBlank(token) && isBlank(signingSecret);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /// Looks up the oldest active service account of `applicationId` and
    /// extracts its webhook credentials, stamping [#signedBy] with its code.
    /// `Optional.empty()` = no active service account at all — one of the
    /// degraded cases the caller logs.
    public static Optional<OutboundCredentials> resolve(ServiceAccountRepository serviceAccounts, String applicationId) {
        return serviceAccounts.findFirstActiveByApplicationId(applicationId)
                .map(sa -> new OutboundCredentials(sa.webhookCredentials().token(), sa.webhookCredentials().signingSecret(),
                        null, sa.code()));
    }

    /// Wraps `delegate` with a one-minute-per-application TTL cache (spec §3
    /// step 5: "cached one minute per application"). A plain function, not a
    /// class depending on [ServiceAccountRepository] directly, so a counting
    /// resolver (`JobDispatcherTest`, `DeliveryCredentialsTest`) plugs in as
    /// `delegate` without a repository or a database at all.
    public static Function<String, Optional<OutboundCredentials>> cached(
            Function<String, Optional<OutboundCredentials>> delegate, Clock clock) {
        return new Cache<>(delegate, clock);
    }

    /// A named-account lookup's outcome (`docs/spec/dispatch-delivery-credentials.md`
    /// §2 steps 1–2, hand-off 2026-09-22): unlike [#resolve] — scoped to "the
    /// application's oldest ACTIVE account" and happy to fall through to
    /// `Optional.empty()` — every distinct reason a named account can fail to
    /// sign must be reported, never collapsed into one "not found". An
    /// inactive account is a DECLINE here, not a fallback signal: deactivating
    /// an account must stop it signing, and the caller (not this lookup)
    /// decides whether anything else may sign instead.
    public sealed interface ById {
        /// The account exists, is active, and carries at least one webhook credential.
        record Found(OutboundCredentials credentials) implements ById {
        }

        /// No `iam_service_accounts` row for this id.
        record Missing() implements ById {
        }

        /// The row exists but `active = false`.
        record Inactive(String code) implements ById {
        }

        /// The row exists and is active, but neither a bearer token nor a signing secret is set.
        record NoCredentials(String code) implements ById {
        }
    }

    /// [#ById]'s four outcomes for `serviceAccountId` — never a bare `<who>`-prefixed
    /// message: only the caller (which named this account — a subscription's
    /// own id, or its connection's) knows who to blame, so this stays
    /// generic and the caller (`DeliveryCredentials#named`) builds the final
    /// reason.
    public static ById resolveById(ServiceAccountRepository serviceAccounts, String serviceAccountId) {
        var found = serviceAccounts.findById(serviceAccountId);
        if (found.isEmpty()) {
            return new ById.Missing();
        }
        ServiceAccount account = found.get();
        if (!account.active()) {
            return new ById.Inactive(account.code());
        }
        var creds = new OutboundCredentials(account.webhookCredentials().token(), account.webhookCredentials().signingSecret(),
                null, account.code());
        if (creds.isEmpty()) {
            return new ById.NoCredentials(account.code());
        }
        return new ById.Found(creds);
    }

    /// The by-id twin of [#cached] — same one-minute memo, same generic
    /// [Cache] implementation, a separate instance (Go: `NewCachedOutboundCredsByIDResolver`,
    /// sharing `cachedCreds` with the by-application resolver the way this
    /// shares [Cache]).
    public static Function<String, ById> cachedById(Function<String, ById> delegate, Clock clock) {
        return new Cache<>(delegate, clock);
    }

    private static final class Cache<T> implements Function<String, T> {
        private static final Duration TTL = Duration.ofMinutes(1);

        private final Function<String, T> delegate;
        private final Clock clock;
        private final ConcurrentHashMap<String, Entry<T>> entries = new ConcurrentHashMap<>();

        private record Entry<T>(T value, Instant expiresAt) {
        }

        Cache(Function<String, T> delegate, Clock clock) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public T apply(String key) {
            Instant now = clock.instant();
            Entry<T> cached = entries.get(key);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                return cached.value();
            }
            T resolved = delegate.apply(key);
            entries.put(key, new Entry<>(resolved, now.plus(TTL)));
            return resolved;
        }
    }
}
