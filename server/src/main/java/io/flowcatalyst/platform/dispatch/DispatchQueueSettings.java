package io.flowcatalyst.platform.dispatch;

import io.flowcatalyst.platform.shared.dispatch.DispatchQueueName;
import io.flowcatalyst.router.queue.sqs.SqsQueue;
import io.flowcatalyst.server.Env;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Objects;

/// How the platform names and addresses the dispatch queues it advertises in
/// its router-config document (`docs/spec/deployed-dispatch.md` §3),
/// resolved once from [Env]. Settings only — no queue is created or checked
/// to exist here (queues are created lazily, settled item 3).
///
/// @param sqs          `true` for `FC_DISPATCH_QUEUE_TYPE=SQS`
///                     (case-insensitive); `false` for every other value,
///                     including unset — the document then names
///                     Postgres-backed queues, as `fcdev` does today
/// @param prefix       `FC_DISPATCH_QUEUE_PREFIX`, e.g. `FC-staging`.
///                     Checked non-blank by [#resolve] whenever [#sqs] is
///                     true; may be blank for a Postgres deployment (dev)
/// @param databaseUrl  [Env#databaseUrl()], scheme-normalised to
///                     `postgres://` — the `queueUri` every Postgres-backed
///                     queue in the document shares. Normalised (not
///                     `env.databaseUrl()` verbatim) because
///                     [io.flowcatalyst.router.queue.QueueFactory#resolveScheme]
///                     only registers the `postgres` key, never `postgresql`,
///                     which is [Env#databaseUrl]'s own scheme (found via
///                     unit D's end-to-end test: without this, the router
///                     would refuse every Postgres-backed queue the served
///                     document names, logging "queue uses a scheme with no
///                     registered consumer") — the same one-line rewrite
///                     `Server#defaultQueueUri`/`Router#defaultQueueUri`
///                     already apply for the identical reason.
///                     `RouterConfig#merge` keys queues by `queueUri`, but
///                     only *across* merged sources: several queue *names*
///                     sharing one URI inside this one document is the
///                     ordinary Postgres shape (one database, many queue
///                     names) and is left alone by a single-source document.
///                     `""` when [#sqs] is true
/// @param sqsAccountId the AWS account id parsed from the path of
///                     `FC_DISPATCH_QUEUE_URL`
///                     (`https://sqs.<region>.amazonaws.com/<account>/<queue>`);
///                     `""` when [#sqs] is false or the URL does not parse
/// @param sqsRegion    `FC_DISPATCH_QUEUE_REGION` if set, else the region
///                     parsed from `FC_DISPATCH_QUEUE_URL`
///                     ([SqsQueue#regionFromUrl]); `""` when [#sqs] is false
///                     or neither resolves
public record DispatchQueueSettings(boolean sqs, String prefix, String databaseUrl, String sqsAccountId,
                                    String sqsRegion) {

    public DispatchQueueSettings {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(databaseUrl, "databaseUrl");
        Objects.requireNonNull(sqsAccountId, "sqsAccountId");
        Objects.requireNonNull(sqsRegion, "sqsRegion");
    }

    /// Resolves from `env`. Refuses to start when `FC_DISPATCH_QUEUE_TYPE=SQS`
    /// but `FC_DISPATCH_QUEUE_PREFIX` is blank (spec §3 "Risks to pin with
    /// tests": "The prefix being unset in a deployed environment must be a
    /// startup error, not a queue literally named `FC-{env}`") — the same
    /// eager-throw idiom as
    /// [io.flowcatalyst.server.dbsecret.DbSecretMode#resolve] and
    /// [io.flowcatalyst.server.Router#electionConfig]: called from the
    /// composition root before any subsystem starts, so a misconfigured
    /// deployment fails loudly at boot rather than silently composing a
    /// useless queue name at runtime.
    ///
    /// @throws IllegalStateException `FC_DISPATCH_QUEUE_TYPE` is `SQS` and
    ///                                either `FC_DISPATCH_QUEUE_PREFIX` is
    ///                                blank, or no account id and region could
    ///                                be resolved to compose queue URLs with —
    ///                                both would otherwise yield a
    ///                                syntactically valid but meaningless SQS
    ///                                endpoint
    public static DispatchQueueSettings resolve(Env env) {
        Objects.requireNonNull(env, "env");
        boolean sqs = "SQS".equalsIgnoreCase(env.dispatchQueueType().trim());
        String prefix = env.dispatchQueuePrefix();
        if (sqs && prefix.isBlank()) {
            throw new IllegalStateException(
                    "FC_DISPATCH_QUEUE_PREFIX is required when FC_DISPATCH_QUEUE_TYPE=SQS — without it, "
                            + "dispatch queues would be named literally \"FC-{env}-...\" instead of a real "
                            + "per-deployment prefix");
        }
        if (!sqs) {
            return new DispatchQueueSettings(false, prefix, normalisePostgresScheme(env.databaseUrl()), "", "");
        }
        String url = env.dispatchQueueUrl();
        String region = env.dispatchQueueRegion();
        if (region.isBlank()) {
            region = SqsQueue.regionFromUrl(url).orElse("");
        }
        String accountId = accountIdFromSqsUrl(url);
        // Same reasoning as the prefix check above, and the same failure it
        // guards against: [#queueUriFor] interpolates both of these straight
        // into the queue URL, so a blank one yields
        // "https://sqs..amazonaws.com//FC-staging-acme-DEFAULT.fifo" — which
        // QueueFactory.resolveScheme still recognises as SQS (the host starts
        // "sqs." and contains ".amazonaws."), so the router would build a
        // consumer against a nonsense endpoint and fail at poll time instead
        // of at boot. A deployed environment that sets DISPATCH_QUEUE_TYPE=SQS
        // without a usable DISPATCH_QUEUE_URL is misconfigured; say so now.
        if (region.isBlank() || accountId.isBlank()) {
            throw new IllegalStateException(
                    "FC_DISPATCH_QUEUE_TYPE=SQS needs an account id and region to compose queue URLs, but "
                            + (accountId.isBlank() ? "no account id could be parsed from FC_DISPATCH_QUEUE_URL" : "")
                            + (accountId.isBlank() && region.isBlank() ? " and " : "")
                            + (region.isBlank() ? "no region was resolved from FC_DISPATCH_QUEUE_REGION or "
                                    + "FC_DISPATCH_QUEUE_URL" : "")
                            + " (FC_DISPATCH_QUEUE_URL=\"" + url + "\")");
        }
        return new DispatchQueueSettings(true, prefix, "", accountId, region);
    }

    /// The queue URL a composed [DispatchQueueName] resolves to: the
    /// composed SQS queue URL for [#sqs], else [#databaseUrl] — see that
    /// field's doc for why a shared Postgres URI across several queue names
    /// is safe inside one document.
    public String queueUriFor(DispatchQueueName name) {
        return sqs
                ? "https://sqs." + sqsRegion + ".amazonaws.com/" + sqsAccountId + "/" + name.value()
                : databaseUrl;
    }

    /// [Env#databaseUrl()] uses the `postgresql://` scheme (it is a plain
    /// JDBC-shaped connection string); [io.flowcatalyst.router.queue.QueueFactory#resolveScheme]
    /// only ever registers the `postgres` key. Without this rewrite every
    /// Postgres-backed queue in the served document would carry a scheme the
    /// router refuses outright — see [#databaseUrl]'s doc for how this was
    /// found. A no-op for a blank/already-`postgres://` URL.
    private static String normalisePostgresScheme(String url) {
        return url.replaceFirst("^postgresql://", "postgres://");
    }

    /// The first path segment of an SQS queue URL
    /// (`https://sqs.<region>.amazonaws.com/<account>/<queue>`); `""` when
    /// `url` does not parse or has no such segment.
    private static String accountIdFromSqsUrl(String url) {
        try {
            var path = new URI(url).getPath();
            if (path == null) {
                return "";
            }
            return Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).findFirst().orElse("");
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
