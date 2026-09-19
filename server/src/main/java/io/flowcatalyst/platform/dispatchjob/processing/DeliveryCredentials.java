package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.subscription.Subscription;

import java.util.Optional;
import java.util.function.Function;

/// The webhook credentials [SubscriberDelivery] stamps on a delivery
/// (dispatch-seam spec §5 "Delivery request construction"): a bearer token
/// (a static, convenience credential) and/or an HMAC signing secret (the
/// real security boundary). Credentials belong to the **application**, not
/// to the job's or subscription's `serviceAccountId`
/// (`docs/spec/dispatch-delivery-credentials.md`, resolved from Go's
/// `wire_public.go` `dispatchDeliveryCredsResolver`): [#forApplications]
/// walks job → subscription (when the job has one) → application code →
/// application → the application's oldest active service account, behind
/// the same one-minute-per-application cache
/// [io.flowcatalyst.platform.scheduler.jobs.JobDispatcher] uses
/// ([OutboundCredentials#cached]) — one resolver, shared, not a second
/// implementation of it. [#none] stays for tests that want no credentials
/// at all.
///
/// Resolution failure degrades to bare delivery with a warning, never a hard
/// failure (spec §5) — [ProcessingApi] enforces that at the call site so a
/// throwing implementation cannot abort a delivery.
public interface DeliveryCredentials {

    /// The credentials to stamp on `job`'s delivery, or [Resolved#NONE] when
    /// the job's subscriber has none configured.
    Resolved resolve(DispatchJob job);

    /// A bearer token and/or signing secret, either or both `null` when not
    /// configured.
    record Resolved(String bearerToken, String signingSecret) {
        public static final Resolved NONE = new Resolved(null, null);

        /// Masked: these are secrets, not diagnostics (CONVENTIONS.md §8).
        @Override
        public String toString() {
            return "Resolved[bearerToken=%s, signingSecret=%s]"
                    .formatted(mask(bearerToken), mask(signingSecret));
        }

        private static String mask(String secret) {
            return secret == null ? "null" : secret.isEmpty() ? "<empty>" : "<redacted>";
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

    /// The one [io.flowcatalyst.platform.application.ApplicationRepository]
    /// method this needs, same reasoning as [SubscriptionLookup].
    /// [io.flowcatalyst.platform.application.ApplicationRepository#findByCode]
    /// implements this directly via a method reference.
    @FunctionalInterface
    interface ApplicationLookup {
        Optional<Application> findByCode(String code);
    }

    /// Resolves an application code for `job` (spec §2 steps 1–2), then that
    /// application's oldest active service account's webhook credentials
    /// through `byApplicationId` (spec §2 step 5 — pass
    /// `OutboundCredentials.cached(applicationId -> OutboundCredentials.resolve(serviceAccounts, applicationId), clock)`,
    /// the scheduled-job dispatcher's own resolver, never a second one).
    ///
    /// Application-code resolution (spec §2):
    /// 1. `job.subscriptionId()` names a subscription whose `applicationCode`
    ///    is non-blank ⇒ that code.
    /// 2. Otherwise (no `subscriptionId`, an id naming no row, or a
    ///    subscription with a blank `applicationCode`) ⇒ `job.code()` up to
    ///    its first `:` when the code contains one and that leading segment
    ///    is non-empty.
    /// 3. Neither resolves an application code ⇒ [Resolved#NONE] — a bare,
    ///    platform-scoped delivery, not an error.
    ///
    /// Every lookup here is allowed to throw straight through — a database
    /// failure at any step is the "resolver throws" case the class doc and
    /// spec §3 describe, and [ProcessingApi] is what degrades that to a bare
    /// delivery with a WARN, not this factory.
    static DeliveryCredentials forApplications(SubscriptionLookup subscriptions, ApplicationLookup applications,
                                                Function<String, Optional<OutboundCredentials>> byApplicationId) {
        return job -> {
            String applicationCode = applicationCodeFor(job, subscriptions);
            if (applicationCode == null) {
                return Resolved.NONE;
            }
            return applications.findByCode(applicationCode)
                    .flatMap(app -> byApplicationId.apply(app.id()))
                    .map(oc -> new Resolved(oc.token(), oc.signingSecret()))
                    .orElse(Resolved.NONE);
        };
    }

    /// Spec §2 steps 1–2. A `subscriptionId` that names no row, or a
    /// subscription whose `applicationCode` is `null`/blank, falls through to
    /// the job-code segment — it is not treated as "no application code" by
    /// itself (spec §2: "A subscription id that names no row is case 2, not
    /// an error").
    private static String applicationCodeFor(DispatchJob job, SubscriptionLookup subscriptions) {
        if (job.subscriptionId() != null) {
            Optional<Subscription> subscription = subscriptions.findById(job.subscriptionId());
            if (subscription.isPresent()) {
                String applicationCode = subscription.get().applicationCode();
                if (applicationCode != null && !applicationCode.isBlank()) {
                    return applicationCode;
                }
            }
        }
        return leadingSegment(job.code());
    }

    /// `code` up to (not including) its first `:`, or `null` when `code`
    /// carries no `:` at all, or that leading segment is empty (`:x`) — spec
    /// §2 step 2 / S3's malformed-input table.
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
}
