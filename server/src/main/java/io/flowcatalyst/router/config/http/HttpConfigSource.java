package io.flowcatalyst.router.config.http;

import io.flowcatalyst.http.oauth.TokenManager;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.manager.RouterServer;
import io.flowcatalyst.router.observability.Warnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;

/// Fetches the router's configuration from `FLOWCATALYST_CONFIG_URL`
/// (`docs/spec/router.md` §8.1).
///
/// ### Every URL is independent, and fetched at the same time
///
/// Each configured URL retries on its own schedule; a slow or dead config
/// service delays only the queues it defines, never the others, and the
/// [StructuredTaskScope] `awaitAll` policy (CONVENTIONS §8) lets every URL's
/// retry loop run to its own conclusion rather than cancelling the rest the
/// moment one fails.
///
/// ### Never suppresses an unchanged answer
///
/// Unlike the Go `config_sync.go`, this source does **not** compare the
/// fetched configuration against the last one and swallow an unchanged
/// result. [RouterServer.ConfigSource]'s contract is explicit about why:
/// after a leadership loss the router has forgotten its consumers, and an
/// unchanged config is exactly what a regained leadership needs applied
/// again. Deduplication, if any is ever wanted, belongs above this class —
/// [io.flowcatalyst.router.manager.RouterManager#reconfigure] is already
/// idempotent against a repeated identical config.
///
/// ### Failure is per-URL, and a URL that has already succeeded once falls
/// back to what it last returned (R-30, §5.6)
///
/// A URL that exhausts its retries but has a **last-known-good**
/// configuration on file keeps contributing that configuration to the
/// merge, with a `CONFIGURATION` warning naming the URL and when that
/// configuration was fetched — a transient bad fetch from one source must
/// not stop the traffic that source was driving. A URL that has never once
/// succeeded has nothing to fall back to and is dropped, with a
/// once-per-streak `CONFIGURATION` warning of its own (and the same INFO
/// notice when it recovers). Only when **every** URL contributes nothing —
/// fresh or cached — does [#fetch] answer [Optional#empty()], the contract's
/// definition of "genuinely unavailable".
///
/// ### Optional bearer auth for the router's own platform (`docs/spec/router-config-auth.md` §2)
///
/// A [TokenManager], when given, mints a `client_credentials` token at the
/// platform origin (`FC_ROUTER_PLATFORM_URL`'s origin) and attaches it only
/// to requests whose URL shares that exact origin — a deployment's
/// `FLOWCATALYST_CONFIG_URL` may list several third-party config services
/// beside the platform's own document, and the credential belongs to the
/// platform, never to them. A `401` from a request that carried the token
/// invalidates the cache so the next attempt re-mints. A
/// [TokenManager.TokenException] while minting is treated exactly like any
/// other transport failure — logged through [#firstOfStreak], the attempt
/// counted as failed, R-B's retry loop unchanged.
public final class HttpConfigSource implements RouterServer.ConfigSource {

    private static final Logger log = LoggerFactory.getLogger(HttpConfigSource.class);

    /// Spec constants (`docs/spec/router.md` §8.1): up to 12 attempts per
    /// URL, 5 s apart, each attempt bounded by a 10 s client timeout — worst
    /// case one URL takes roughly 12×10 s + 11×5 s ≈ 3 minutes.
    public static final int DEFAULT_MAX_ATTEMPTS = 12;
    public static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofSeconds(5);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final List<String> urls;
    private final HttpClient client;
    private final int maxAttempts;
    private final Duration retryInterval;
    private final Duration requestTimeout;
    private final Warnings warnings;
    /// `null` when the router carries no client credentials (`docs/spec/router-config-auth.md`
    /// §2) — every request is then fetched unauthenticated, exactly as before.
    private final TokenManager tokenManager;
    /// The platform's own origin (`scheme://host[:port]`), computed once from
    /// `FC_ROUTER_PLATFORM_URL`; `null` alongside [#tokenManager]. Only a
    /// request whose URL shares this exact origin carries the bearer token —
    /// see [#sameOrigin].
    private final String platformOrigin;

    /// Reported to `slog` only, never the operator warning store — matches
    /// Go's `mergeConfigs`, which treats a conflicting duplicate as a
    /// config-authoring problem rather than a runtime condition (§8.1).
    private final RouterConfig.ConflictReporter conflictReporter = message -> log.atWarn().setMessage("config merge conflict")
            .addKeyValue("conflict", message)
            .log();

    /// Each URL's last successfully fetched configuration, held so a source
    /// that starts failing keeps driving the traffic it was already driving
    /// instead of that traffic simply stopping (R-30, §5.6).
    private final Map<String, CachedFetch> lastKnownGood = new ConcurrentHashMap<>();

    /// URLs currently in a failing streak, so the CONFIGURATION warning fires
    /// once on entry rather than on every failed poll (§5.6: "raise... while
    /// a source is failing/stale").
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    /// URLs whose failure cause has already been logged for the current
    /// streak, so the stack trace lands once per outage rather than once per
    /// retry. Cleared on success, like [#failing].
    private final Set<String> causeLogged = ConcurrentHashMap.newKeySet();

    private record CachedFetch(RouterConfig config, Instant fetchedAt) {
    }

    /// Full control for tests: a smaller [#maxAttempts]/[#retryInterval]
    /// keeps the retry *mechanism* under test without waiting out the
    /// production schedule, and a client pointed at a loopback test server
    /// needs no network double.
    HttpConfigSource(List<String> urls, HttpClient client, int maxAttempts,
                      Duration retryInterval, Duration requestTimeout) {
        this(urls, client, maxAttempts, retryInterval, requestTimeout, Warnings.NO_OP);
    }

    /// As above, with the [Warnings] collaborator the last-known-good
    /// CONFIGURATION notices go through (R-30). Package-private: only
    /// [#create] builds a production instance, and other tests that do not
    /// care about the notices use the [Warnings.NO_OP] convenience above.
    HttpConfigSource(List<String> urls, HttpClient client, int maxAttempts, Duration retryInterval,
                      Duration requestTimeout, Warnings warnings) {
        this(urls, client, maxAttempts, retryInterval, requestTimeout, warnings, null, null);
    }

    /// The full constructor: an optional [TokenManager] plus the platform
    /// origin it mints for (`docs/spec/router-config-auth.md` §2). Both
    /// `null`, or both non-null — [#create] is the only caller that ever
    /// passes non-null values.
    HttpConfigSource(List<String> urls, HttpClient client, int maxAttempts, Duration retryInterval,
                      Duration requestTimeout, Warnings warnings, TokenManager tokenManager, String platformOrigin) {
        this.urls = List.copyOf(urls);
        this.client = client;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
        this.requestTimeout = requestTimeout;
        this.warnings = warnings;
        this.tokenManager = tokenManager;
        this.platformOrigin = platformOrigin;
    }

    /// The production source: `rawEnvValue` is `FLOWCATALYST_CONFIG_URL`
    /// verbatim (comma-separated, possibly `null`/blank/unset). Empty when
    /// no URL is configured — the composition root falls back to the
    /// default-broker config in that case (§8.4), so this never needs to be
    /// called at all when there is nothing to fetch.
    public static RouterServer.ConfigSource create(String rawEnvValue) {
        return create(rawEnvValue, Warnings.NO_OP);
    }

    /// As above, wired to the operator warning store so a failing/stale
    /// source (R-30) and its recovery are operator-visible.
    public static RouterServer.ConfigSource create(String rawEnvValue, Warnings warnings) {
        return create(rawEnvValue, warnings, null, null);
    }

    /// As above, plus optional client-credentials auth for the router's own
    /// platform (`docs/spec/router-config-auth.md` §2): `tokenManager` and
    /// `platformUrl` are both non-null or both null — [io.flowcatalyst.server.Router#configSource]
    /// is the only caller and enforces that pairing before it ever reaches here.
    public static RouterServer.ConfigSource create(String rawEnvValue, Warnings warnings,
                                                     TokenManager tokenManager, String platformUrl) {
        var urls = parseUrls(rawEnvValue);
        if (urls.isEmpty()) {
            return Optional::empty;
        }
        var client = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_REQUEST_TIMEOUT)
                .build();
        return new HttpConfigSource(urls, client, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_INTERVAL,
                DEFAULT_REQUEST_TIMEOUT, warnings, tokenManager, originOf(platformUrl));
    }

    /// `scheme://host[:port]` of `url`, or `null` for a `null`/unparseable one.
    private static String originOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            var uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Whether `url` shares [#platformOrigin] exactly — the same-origin rule
    /// that decides whether a request carries the bearer token (§2: "other
    /// origins are fetched as before, and the credential is never sent to
    /// them").
    private boolean carriesAuthTo(String url) {
        return tokenManager != null && platformOrigin != null && platformOrigin.equals(originOf(url));
    }

    /// Splits on `,`, trims each part, drops empties (§8.1).
    static List<String> parseUrls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    @Override
    public Optional<RouterConfig> fetch() {
        if (urls.isEmpty()) {
            return Optional.empty();
        }

        List<FetchOutcome> outcomes;
        try {
            outcomes = fetchAllInUrlOrder();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        // Order preserved from `urls`, which is what keeps the merge's
        // first-definition-wins precedence a property of configured order
        // rather than of fetch outcome.
        var effective = new ArrayList<RouterConfig>(outcomes.size());
        var droppedCount = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            var url = urls.get(i);
            switch (outcomes.get(i)) {
                case FetchOutcome.Success success -> effective.add(recordSuccess(url, success.config()));
                case FetchOutcome.Failure ignored -> {
                    var stale = lastKnownGoodFor(url);
                    if (stale.isPresent()) {
                        effective.add(stale.get());
                    } else {
                        droppedCount++;
                        // Never once succeeded: nothing to fall back to, and until now
                        // nothing told an operator either — a router whose only config
                        // URL is unreachable ran with no queues in silence. Once per
                        // failure streak, like the stale case (the Rust router raises
                        // "Config sync failed" as a WARN on every failed sync).
                        if (failing.add(url)) {
                            warnings.raise(Warnings.Severity.WARNING, "CONFIGURATION",
                                    "config source " + url + " is failing and has never supplied a configuration;"
                                            + " it contributes nothing until it answers");
                        }
                    }
                }
            }
        }

        if (effective.isEmpty()) {
            log.atError().setMessage("config fetch: all urls failed with no last-known-good configuration to fall back to")
                    .addKeyValue("count", urls.size())
                    .log();
            return Optional.empty();
        }
        if (droppedCount > 0) {
            log.atWarn().setMessage("config fetch: urls failed with no last-known-good configuration; dropped")
                    .addKeyValue("dropped", droppedCount)
                    .addKeyValue("count", urls.size())
                    .log();
        }
        return Optional.of(RouterConfig.merge(effective, conflictReporter));
    }

    /// The cause on the first failure of a URL's streak, its `toString`
    /// afterwards: a source that stays broken is retried every
    /// [#DEFAULT_RETRY_INTERVAL], and a stack trace per attempt is volume
    /// rather than information.
    ///
    /// Deliberately **not** the `failing` set: that one is only entered when
    /// there is a last-known-good to fall back on, so a URL that has never
    /// once succeeded — the misconfigured-URL case, which never recovers on
    /// its own — would never be in it and would log a trace every retry
    /// forever. [#causeLogged] tracks the streak on its own terms and is
    /// cleared by [#recordSuccess] alongside it.
    private org.slf4j.spi.LoggingEventBuilder firstOfStreak(
            org.slf4j.spi.LoggingEventBuilder event, String url, Throwable e) {
        return causeLogged.add(url) ? event.setCause(e) : event.addKeyValue("reason", String.valueOf(e));
    }

    /// Caches `config` as `url`'s last-known-good and, if `url` was in a
    /// failing streak, clears it with an INFO recovery notice (R-30, §5.6).
    ///
    /// @return `config`, unchanged — so this can sit inline in the fetch loop
    ///         above rather than needing a separate statement per URL
    private RouterConfig recordSuccess(String url, RouterConfig config) {
        lastKnownGood.put(url, new CachedFetch(config, Instant.now()));
        causeLogged.remove(url);
        if (failing.remove(url)) {
            warnings.raise(Warnings.Severity.INFO, "CONFIGURATION", "config source " + url + " recovered");
        }
        return config;
    }

    /// `url`'s last-known-good configuration, raising the once-per-streak
    /// CONFIGURATION warning the first time it is drawn on for this failure
    /// (R-30, §5.6). Empty when `url` has never once succeeded — there is
    /// nothing to fall back to, and it is dropped exactly as before.
    private Optional<RouterConfig> lastKnownGoodFor(String url) {
        var cached = lastKnownGood.get(url);
        if (cached == null) {
            return Optional.empty();
        }
        if (failing.add(url)) {
            warnings.raise(Warnings.Severity.WARNING, "CONFIGURATION",
                    "config source " + url + " is failing; using its last-known-good configuration, fetched at "
                            + cached.fetchedAt());
        }
        return Optional.of(cached.config());
    }

    /// Forks one retrying fetch per URL and waits for all of them,
    /// regardless of individual outcome, then reads the results back in the
    /// **URLs' declared order** — not completion order, which is what makes
    /// first-definition-wins (`RouterConfig#merge`) deterministic regardless
    /// of which config service answers fastest.
    /// Fetches every URL at once and returns the outcomes **in URL order**.
    ///
    /// Parallel because a source that is slow or retrying should cost its own
    /// latency and not the sum: with 12 attempts 5s apart, a single dead URL
    /// would otherwise delay every healthy one behind it by up to a minute.
    ///
    /// Order comes from the subtask list, not from completion, so the merge's
    /// first-definition-wins precedence stays a property of the configured
    /// order rather than of whichever server answered first — which would
    /// make precedence a race.
    private List<FetchOutcome> fetchAllInUrlOrder() throws InterruptedException {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<FetchOutcome>awaitAll())) {
            var subtasks = urls.stream()
                    .map(url -> scope.fork(() -> fetchWithRetry(url)))
                    .toList();
            scope.join();
            var results = new ArrayList<FetchOutcome>(urls.size());
            for (int i = 0; i < subtasks.size(); i++) {
                results.add(resultOf(subtasks.get(i), urls.get(i)));
            }
            return results;
        }
    }

    private FetchOutcome resultOf(StructuredTaskScope.Subtask<FetchOutcome> subtask, String url) {
        return switch (subtask.state()) {
            case SUCCESS -> subtask.get();
            case FAILED -> {
                log.atWarn().setMessage("config fetch: failed unexpectedly")
                        .addKeyValue("url", url)
                        .setCause(subtask.exception())
                        .log();
                yield new FetchOutcome.Failure(url);
            }
            case UNAVAILABLE -> new FetchOutcome.Failure(url);
        };
    }

    /// Up to [#maxAttempts] attempts, [#retryInterval] apart, for one URL.
    /// A **refusal** — an answer the next attempt cannot change (`docs/spec/router.md`
    /// §8.1: 403, 404, any other 4xx bar the retryable few, or a 401 on a request
    /// that carried no token) — fails the URL at once instead of burning the
    /// budget on it: `fetch()` applies nothing until every URL has finished, so
    /// twelve retries of a 403 would hold every healthy source back for a
    /// minute on every poll (staging, 2026-09-16).
    private FetchOutcome fetchWithRetry(String url) throws InterruptedException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            switch (attemptOnce(url)) {
                case Attempt.Ok ok -> {
                    return new FetchOutcome.Success(ok.config());
                }
                case Attempt.Refused refused -> {
                    log.atWarn().setMessage("config fetch: refused; not retrying")
                            .addKeyValue("url", url)
                            .addKeyValue("status", refused.status())
                            .log();
                    return new FetchOutcome.Failure(url);
                }
                case Attempt.Retry ignored -> {
                    if (attempt < maxAttempts) {
                        Thread.sleep(retryInterval);
                    }
                }
            }
        }
        log.atWarn().setMessage("config fetch: failed")
                .addKeyValue("url", url)
                .addKeyValue("max_attempts", maxAttempts)
                .log();
        return new FetchOutcome.Failure(url);
    }

    /// One GET, decoded as a [RouterConfig]. [Attempt.Retry] on a transport
    /// failure, a retryable status ([#retryableStatus]) or a body that isn't
    /// valid JSON for the shape; [Attempt.Refused] on a status another
    /// attempt cannot change (§8.1). [InterruptedException]
    /// is the one outcome that is never swallowed here: [HttpClient#send]
    /// declares it directly, and letting it propagate is what makes
    /// cancellation-by-interruption work at this blocking point
    /// (CONVENTIONS §8).
    private Attempt attemptOnce(String url) throws InterruptedException {
        boolean authorized = carriesAuthTo(url);

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(requestTimeout);
        } catch (RuntimeException e) {
            log.atWarn().setMessage("config fetch: malformed")
                    .addKeyValue("url", url)
                    .setCause(e)
                    .log();
            return new Attempt.Retry();
        }

        if (authorized) {
            String token;
            try {
                token = tokenManager.token();
            } catch (TokenManager.TokenException e) {
                // A minting failure is an attempt failure like any other transport
                // failure (`docs/spec/router-config-auth.md` §2) — never an
                // exception out of fetch(); R-B's retry loop keeps working.
                firstOfStreak(log.atWarn().setMessage("config fetch attempt failed: token mint failed")
                        .addKeyValue("url", url), url, e).log();
                return new Attempt.Retry();
            }
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.build();

        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            firstOfStreak(log.atWarn().setMessage("config fetch attempt failed")
                    .addKeyValue("url", url), url, e).log();
            return new Attempt.Retry();
        }

        if (authorized && response.statusCode() == 401) {
            // The cached token was rejected — invalidate so the NEXT attempt
            // re-mints, rather than replaying a token known to be bad on every
            // remaining retry of this streak (§2).
            tokenManager.invalidate();
        }

        int status = response.statusCode();
        if (status >= 300) {
            var event = log.atWarn().setMessage("config fetch attempt failed")
                    .addKeyValue("url", url)
                    .addKeyValue("status", status);
            if (!authorized && (status == 401 || status == 403)) {
                // The one refusal an operator can fix from the log line alone
                // (`docs/spec/router-config-auth.md` §2).
                event.addKeyValue("hint", UNAUTHENTICATED_HINT);
            }
            event.log();
            return retryableStatus(status, authorized) ? new Attempt.Retry() : new Attempt.Refused(status);
        }

        try {
            return new Attempt.Ok(Json.MAPPER.readValue(response.body(), RouterConfig.class));
        } catch (JacksonException e) {
            firstOfStreak(log.atWarn().setMessage("config fetch attempt failed: invalid JSON")
                    .addKeyValue("url", url), url, e).log();
            return new Attempt.Retry();
        }
    }

    /// Whether another attempt could plausibly get a different answer:
    /// server-side and throttling failures (5xx, 408, 425, 429), and a 401 on
    /// an authenticated request — the rejected token was just invalidated, so
    /// the next attempt mints a fresh one. Every other client error — a 403
    /// for a missing permission or credential, a 404 for a wrong URL — answers
    /// the same way until someone changes the deployment (§8.1).
    static boolean retryableStatus(int status, boolean authenticated) {
        if (status >= 500) return true;
        return switch (status) {
            case 408, 425, 429 -> true;
            case 401 -> authenticated;
            default -> false;
        };
    }

    /// What a 401/403 answered to a request that carried no token means for
    /// the deployment, spelled out where the operator is already looking.
    static final String UNAUTHENTICATED_HINT = "sent without credentials: a platform's /api/dispatch/router-config needs "
            + "FC_ROUTER_PLATFORM_URL set to that platform plus FC_ROUTER_CLIENT_ID/FC_ROUTER_CLIENT_SECRET";

    /// One attempt's outcome — an expected result, not an exception
    /// (CONVENTIONS §8).
    private sealed interface Attempt {
        record Ok(RouterConfig config) implements Attempt {
        }

        /// Worth another attempt after [#retryInterval].
        record Retry() implements Attempt {
        }

        /// Answered with a status the next attempt cannot change.
        record Refused(int status) implements Attempt {
        }
    }

    /// One URL's outcome — an expected result, not an exception
    /// (CONVENTIONS §8).
    private sealed interface FetchOutcome {
        record Success(RouterConfig config) implements FetchOutcome {
        }

        record Failure(String url) implements FetchOutcome {
        }
    }
}
