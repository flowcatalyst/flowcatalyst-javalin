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
/// caller logs a WARN for and still delivers.
///
/// Shared by two callers, deliberately one resolver: the scheduled-job
/// dispatcher (`docs/spec/scheduled-job-scheduler.md` §3 step 5,
/// [io.flowcatalyst.platform.scheduler.jobs.JobDispatcher]) and the dispatch-job
/// processing endpoint (`docs/spec/dispatch-delivery-credentials.md` §2,
/// [io.flowcatalyst.platform.dispatchjob.processing.DeliveryCredentials#forApplications]).
/// Moved here from `platform.scheduler.jobs` (2026-09-19) — a neutral package
/// both callers can depend on without one owning the other — when the second
/// caller needed the exact same resolve-then-cache behaviour rather than a
/// second implementation of it.
public record OutboundCredentials(String token, String signingSecret) {

    /// Looks up the oldest active service account of `applicationId` and
    /// extracts its webhook credentials. `Optional.empty()` = no active
    /// service account at all — one of the five degraded cases the caller logs.
    public static Optional<OutboundCredentials> resolve(ServiceAccountRepository serviceAccounts, String applicationId) {
        return serviceAccounts.findFirstActiveByApplicationId(applicationId)
                .map(sa -> new OutboundCredentials(sa.webhookCredentials().token(), sa.webhookCredentials().signingSecret()));
    }

    /// Wraps `delegate` with a one-minute-per-application TTL cache (spec §3
    /// step 5: "cached one minute per application"). A plain function, not a
    /// class depending on [ServiceAccountRepository] directly, so a counting
    /// resolver (`JobDispatcherTest`, `DeliveryCredentialsTest`) plugs in as
    /// `delegate` without a repository or a database at all.
    public static Function<String, Optional<OutboundCredentials>> cached(
            Function<String, Optional<OutboundCredentials>> delegate, Clock clock) {
        return new Cache(delegate, clock);
    }

    private static final class Cache implements Function<String, Optional<OutboundCredentials>> {
        private static final Duration TTL = Duration.ofMinutes(1);

        private final Function<String, Optional<OutboundCredentials>> delegate;
        private final Clock clock;
        private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

        private record Entry(Optional<OutboundCredentials> value, Instant expiresAt) {
        }

        Cache(Function<String, Optional<OutboundCredentials>> delegate, Clock clock) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public Optional<OutboundCredentials> apply(String applicationId) {
            Instant now = clock.instant();
            Entry cached = entries.get(applicationId);
            if (cached != null && now.isBefore(cached.expiresAt())) {
                return cached.value();
            }
            Optional<OutboundCredentials> resolved = delegate.apply(applicationId);
            entries.put(applicationId, new Entry(resolved, now.plus(TTL)));
            return resolved;
        }
    }
}
