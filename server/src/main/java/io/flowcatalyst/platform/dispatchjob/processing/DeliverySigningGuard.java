package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;
import io.flowcatalyst.platform.serviceaccount.SigningReach;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.sdk.result.Result;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Whether a caller may create — or preview the signature of — a dispatch
/// job whose payload and target it chose (`docs/spec/security-fixes-2026-09-24.md`
/// S3.2). Ingest (`POST /api/dispatch-jobs`(`/batch`)) and `POST {id}/sign`
/// both ask this before the job is written or signed; without it either is
/// a signing oracle for any account the resolver can reach.
///
/// 1. A non-anchor caller's job may name only a subscription of the job's
///    own client (a subscription id naming no row is ignored, exactly as
///    the resolver ignores it).
/// 2. The identity [DeliveryCredentials#signerOf] would sign with must be
///    one the caller may use ([SigningReach]): a named account through
///    [SigningReach#mayUse] with no owning application (the payload and
///    target are the caller's, so a subscription's ownership proves nothing
///    about them); an application's own account through
///    [SigningReach#mayUseApplication]. A named account or application that
///    does not exist signs nothing, so it is not refused.
public final class DeliverySigningGuard {

    /// Why the job is refused. Every case states its own [#message].
    public sealed interface Refusal {
        String message();

        /// The job names a subscription outside its own client.
        record SubscriptionOutsideClient(String subscriptionId) implements Refusal {
            @Override
            public String message() {
                return "No access to subscription: " + subscriptionId;
            }
        }

        /// The identity that would sign the job is not the caller's to use.
        record Signer(SigningReach.Refusal cause) implements Refusal {
            @Override
            public String message() {
                return "dispatch job would be signed by an identity the caller may not use: " + cause.message();
            }
        }
    }

    private final DeliveryCredentials.SubscriptionLookup subscriptions;
    private final DeliveryCredentials.ConnectionLookup connections;
    private final SigningReach reach;

    public DeliverySigningGuard(DeliveryCredentials.SubscriptionLookup subscriptions,
                                DeliveryCredentials.ConnectionLookup connections, SigningReach reach) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.reach = Objects.requireNonNull(reach, "reach");
    }

    /// A copy whose lookups are memoised for ONE request (a batch names the
    /// same subscription or application over and over); see
    /// [SigningReach#perRequest] for why it must not outlive the request.
    public DeliverySigningGuard perRequest() {
        Map<String, Optional<Subscription>> subscriptionMemo = new HashMap<>();
        Map<String, Optional<io.flowcatalyst.platform.connection.Connection>> connectionMemo = new HashMap<>();
        return new DeliverySigningGuard(
                id -> subscriptionMemo.computeIfAbsent(id, subscriptions::findById),
                id -> connectionMemo.computeIfAbsent(id, connections::findById),
                reach.perRequest());
    }

    /// `job`, when `ac` may cause its signed delivery; the refusal otherwise.
    public Result<DispatchJob, Refusal> check(AuthContext ac, DispatchJob job) {
        Objects.requireNonNull(ac, "ac");
        if (!ac.isAnchor() && job.subscriptionId() != null) {
            Subscription subscription = subscriptions.findById(job.subscriptionId()).orElse(null);
            if (subscription != null
                    && (subscription.clientId() == null || !subscription.clientId().equals(job.clientId()))) {
                return Result.err(new Refusal.SubscriptionOutsideClient(job.subscriptionId()));
            }
        }
        Result<?, SigningReach.Refusal> signer = switch (DeliveryCredentials.signerOf(job, subscriptions, connections)) {
            case DeliveryCredentials.Signer.Named(var serviceAccountId, var ignored) -> reach.account(serviceAccountId)
                    .<Result<?, SigningReach.Refusal>>map(account -> reach.mayUse(ac, account))
                    .orElse(Result.ok(serviceAccountId));
            case DeliveryCredentials.Signer.OfApplication(var applicationCode) -> reach.applicationId(applicationCode)
                    .<Result<?, SigningReach.Refusal>>map(id -> reach.mayUseApplication(ac, id, applicationCode))
                    .orElse(Result.ok(applicationCode));
            case DeliveryCredentials.Signer.Nobody nobody -> Result.ok(nobody);
        };
        return switch (signer) {
            case Result.Ok<?, SigningReach.Refusal> ok -> Result.ok(job);
            case Result.Err<?, SigningReach.Refusal>(var cause) -> Result.err(new Refusal.Signer(cause));
        };
    }
}
