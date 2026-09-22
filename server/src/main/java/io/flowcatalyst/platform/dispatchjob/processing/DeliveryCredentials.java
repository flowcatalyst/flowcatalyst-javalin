package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.connection.Connection;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.subscription.Subscription;

import java.util.Optional;
import java.util.function.Function;

/// The webhook credentials [SubscriberDelivery] stamps on a delivery
/// (dispatch-seam spec §5 "Delivery request construction"): a bearer token
/// (a static, convenience credential) and/or an HMAC signing secret (the
/// real security boundary). Owner ruling 2026-09-22
/// (`docs/go-mirror/2026-09-22-delivery-credentials-handoff.md`, **reversing**
/// `docs/spec/dispatch-delivery-credentials.md` §2 "credentials belong to the
/// application"): the connection form REQUIRES a service account and the
/// subscription form asks for no application, so keying on the application
/// alone described a credential nothing configured. [#resolve] now walks, in
/// order, the first thing that **names** an account — with **no fall-through
/// past a named account**:
///
/// 1. `subscription.serviceAccountId` — an explicit override;
/// 2. `subscription.connectionId → connection.serviceAccountId` — the normal case;
/// 3. the application's oldest active service account, via
///    `subscription.applicationCode` or the direct job's code first segment
///    (the pre-ruling rule, kept as the fallback for a job with no connection
///    to name an account);
/// 4. nothing → bare, **with a reason** ([Resolved#reason]).
///
/// Steps 1–2 resolve **by id** ([OutboundCredentials#resolveById]) and
/// require `active`; an inactive/missing/credential-less named account is
/// declined with a reason naming who configured it, never silently signed by
/// a different account — a rotated or deactivated credential must not keep
/// working by accident. Step 3 is unchanged: [OutboundCredentials#resolve],
/// behind the same one-minute-per-application cache
/// [io.flowcatalyst.platform.scheduler.jobs.JobDispatcher] uses
/// ([OutboundCredentials#cached]); step 1–2's by-id lookups share the same
/// memo, a separate cache instance ([OutboundCredentials#cachedById]).
///
/// Resolution failure degrades to bare delivery with a warning, never a hard
/// failure (spec §3) — [ProcessingApi] enforces that at the call site so a
/// throwing implementation cannot abort a delivery.
public interface DeliveryCredentials {

    /// The credentials to stamp on `job`'s delivery, or a bare [Resolved] —
    /// always carrying [Resolved#reason] — when the job's subscriber has none
    /// configured.
    Resolved resolve(DispatchJob job);

    /// A bearer token and/or signing secret, either or both `null` when not
    /// configured; `reason` is non-empty exactly when both are absent
    /// (never signed — "why" for the operator, hand-off 2026-09-22);
    /// `signedBy` is the signing service account's code, `null` when bare.
    record Resolved(String bearerToken, String signingSecret, String reason, String signedBy) {
        public static final Resolved NONE = new Resolved(null, null, "", null);

        /// Every 2026-09-19-era caller (hand-built test fixtures that only
        /// care about the two credential fields) keeps compiling: `reason`
        /// defaults to `""`, `signedBy` to `null`.
        public Resolved(String bearerToken, String signingSecret) {
            this(bearerToken, signingSecret, "", null);
        }

        /// Neither credential is set — the delivery goes out bare.
        public boolean isBare() {
            return isBlank(bearerToken) && isBlank(signingSecret);
        }

        static Resolved bare(String reason) {
            return new Resolved(null, null, reason, null);
        }

        static Resolved signed(String bearerToken, String signingSecret, String signedBy) {
            return new Resolved(blankToNull(bearerToken), blankToNull(signingSecret), "", signedBy);
        }

        /// Masked: [#bearerToken]/[#signingSecret] are secrets, not
        /// diagnostics (CONVENTIONS.md §8) — `reason`/`signedBy` are not
        /// secret and print in full.
        @Override
        public String toString() {
            return "Resolved[bearerToken=%s, signingSecret=%s, reason=%s, signedBy=%s]"
                    .formatted(mask(bearerToken), mask(signingSecret), reason, signedBy);
        }

        private static String mask(String secret) {
            return secret == null ? "null" : secret.isEmpty() ? "<empty>" : "<redacted>";
        }

        private static boolean isBlank(String s) {
            return s == null || s.isEmpty();
        }

        private static String blankToNull(String s) {
            return isBlank(s) ? null : s;
        }
    }

    /// Every job delivers bare — no lookups at all.
    static DeliveryCredentials none() {
        return job -> Resolved.NONE;
    }

    /// The one [io.flowcatalyst.platform.subscription.SubscriptionRepository]
    /// method this needs, extracted as a seam ([ProcessingRepository] /
    /// [ClientCodeResolver.Lookup] are the model) so a test can inject a fake
    /// without a database.
    /// [io.flowcatalyst.platform.subscription.SubscriptionRepository#findById]
    /// implements this directly via a method reference.
    @FunctionalInterface
    interface SubscriptionLookup {
        Optional<Subscription> findById(String id);
    }

    /// The one [io.flowcatalyst.platform.connection.ConnectionRepository]
    /// method this needs, same reasoning as [SubscriptionLookup].
    /// [io.flowcatalyst.platform.connection.ConnectionRepository#findById]
    /// implements this directly via a method reference.
    @FunctionalInterface
    interface ConnectionLookup {
        Optional<Connection> findById(String id);
    }

    /// The one [io.flowcatalyst.platform.application.ApplicationRepository]
    /// method this needs, same reasoning as [SubscriptionLookup].
    /// [io.flowcatalyst.platform.application.ApplicationRepository#findByCode]
    /// implements this directly via a method reference.
    @FunctionalInterface
    interface ApplicationLookup {
        Optional<Application> findByCode(String code);
    }

    /// Builds the resolver the hand-off's resolution order describes (class
    /// doc): `byServiceAccountId` is
    /// `OutboundCredentials.cachedById(id -> OutboundCredentials.resolveById(serviceAccounts, id), clock)`
    /// (steps 1–2), `byApplicationId` is the pre-existing
    /// `OutboundCredentials.cached(applicationId -> OutboundCredentials.resolve(serviceAccounts, applicationId), clock)`
    /// (step 3, unchanged) — both share `serviceAccounts`/`clock` with each
    /// other and with the scheduled-job dispatcher, never a second
    /// implementation of either.
    ///
    /// Every lookup here is allowed to throw straight through — a database
    /// failure at any step is the "resolver throws" case the class doc
    /// describes, and [ProcessingApi] is what degrades that to a bare
    /// delivery with a WARN, not this factory (spec §3).
    static DeliveryCredentials resolve(SubscriptionLookup subscriptions, ConnectionLookup connections,
                                        ApplicationLookup applications,
                                        Function<String, OutboundCredentials.ById> byServiceAccountId,
                                        Function<String, Optional<OutboundCredentials>> byApplicationId) {
        return job -> {
            Subscription subscription = subscriptionFor(job, subscriptions);

            if (subscription != null) {
                String subscriptionAccountId = blankToNull(subscription.serviceAccountId());
                if (subscriptionAccountId != null) {
                    // 1. The subscription names its own account.
                    return named("subscription " + subscription.code(), subscriptionAccountId, byServiceAccountId);
                }
                String connectionId = blankToNull(subscription.connectionId());
                if (connectionId != null) {
                    Connection connection = connections.findById(connectionId).orElse(null);
                    String connectionAccountId = connection == null ? null : blankToNull(connection.serviceAccountId());
                    if (connectionAccountId != null) {
                        // 2. Its connection names one.
                        return named("connection " + connection.code(), connectionAccountId, byServiceAccountId);
                    }
                }
            }

            // 3. The application's oldest active account — unchanged (spec §2 step 3).
            String applicationCode = subscription != null ? blankToNull(subscription.applicationCode()) : null;
            if (applicationCode == null) {
                applicationCode = leadingSegment(job.code());
            }
            if (applicationCode == null) {
                return Resolved.bare("no subscription, connection or application names a service account");
            }
            Application application = applications.findByCode(applicationCode).orElse(null);
            if (application == null) {
                return Resolved.bare("application " + applicationCode + " does not exist");
            }
            Optional<OutboundCredentials> resolved = byApplicationId.apply(application.id());
            if (resolved.isEmpty() || resolved.get().isEmpty()) {
                return Resolved.bare("application " + applicationCode + " has no active service account");
            }
            OutboundCredentials creds = resolved.get();
            return Resolved.signed(creds.token(), creds.signingSecret(), creds.signedBy());
        };
    }

    /// `job.subscriptionId()` names a subscription, or `null` — a
    /// `subscriptionId` naming no row is treated exactly like having none
    /// (falls through to step 3, spec §2's old case-2 rule, unchanged).
    private static Subscription subscriptionFor(DispatchJob job, SubscriptionLookup subscriptions) {
        if (job.subscriptionId() == null) {
            return null;
        }
        return subscriptions.findById(job.subscriptionId()).orElse(null);
    }

    /// Resolves an EXPLICITLY named account (`who` = `subscription <code>` /
    /// `connection <code>`, hand-off 2026-09-22): whatever
    /// [OutboundCredentials#resolveById] answers is final — a decline is
    /// never retried against a different account. Mirrors Go's
    /// `server.named`.
    private static Resolved named(String who, String serviceAccountId,
                                   Function<String, OutboundCredentials.ById> byServiceAccountId) {
        return switch (byServiceAccountId.apply(serviceAccountId)) {
            case OutboundCredentials.ById.Found(var creds) ->
                    Resolved.signed(creds.token(), creds.signingSecret(), creds.signedBy());
            case OutboundCredentials.ById.Missing ignored ->
                    Resolved.bare(who + ": service account " + serviceAccountId + " does not exist");
            case OutboundCredentials.ById.Inactive(var code) ->
                    Resolved.bare(who + ": service account " + code + " is inactive");
            case OutboundCredentials.ById.NoCredentials(var code) ->
                    Resolved.bare(who + ": service account " + code + " has no webhook credentials");
        };
    }

    /// `code` up to (not including) its first `:`, or `null` when `code`
    /// carries no `:` at all, or that leading segment is empty (`:x`) — spec
    /// §2 step 3's malformed-input table.
    private static String leadingSegment(String code) {
        if (code == null) {
            return null;
        }
        int colon = code.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String segment = code.substring(0, colon);
        return segment.isEmpty() ? null : segment;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
