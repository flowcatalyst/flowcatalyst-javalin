package io.flowcatalyst.router.config.http;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.manager.RouterServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
/// ### Failure is per-URL, and only total failure is "unavailable"
///
/// A URL that exhausts its retries is logged and dropped; the merge carries
/// on with whatever the other URLs returned. Only when **every** URL fails
/// does [#fetch] answer [Optional#empty()] — the contract's definition of
/// "genuinely unavailable".
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

    /// Reported to `slog` only, never the operator warning store — matches
    /// Go's `mergeConfigs`, which treats a conflicting duplicate as a
    /// config-authoring problem rather than a runtime condition (§8.1).
    private final RouterConfig.ConflictReporter conflictReporter = message -> log.warn("config merge: {}", message);

    /// Full control for tests: a smaller [#maxAttempts]/[#retryInterval]
    /// keeps the retry *mechanism* under test without waiting out the
    /// production schedule, and a client pointed at a loopback test server
    /// needs no network double.
    HttpConfigSource(List<String> urls, HttpClient client, int maxAttempts,
                      Duration retryInterval, Duration requestTimeout) {
        this.urls = List.copyOf(urls);
        this.client = client;
        this.maxAttempts = maxAttempts;
        this.retryInterval = retryInterval;
        this.requestTimeout = requestTimeout;
    }

    /// The production source: `rawEnvValue` is `FLOWCATALYST_CONFIG_URL`
    /// verbatim (comma-separated, possibly `null`/blank/unset). Empty when
    /// no URL is configured — the composition root falls back to the
    /// default-broker config in that case (§8.4), so this never needs to be
    /// called at all when there is nothing to fetch.
    public static RouterServer.ConfigSource create(String rawEnvValue) {
        var urls = parseUrls(rawEnvValue);
        if (urls.isEmpty()) {
            return Optional::empty;
        }
        var client = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_REQUEST_TIMEOUT)
                .build();
        return new HttpConfigSource(urls, client, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_INTERVAL, DEFAULT_REQUEST_TIMEOUT);
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

        var successes = new ArrayList<RouterConfig>(outcomes.size());
        var failedCount = 0;
        for (var outcome : outcomes) {
            if (outcome instanceof FetchOutcome.Success success) {
                successes.add(success.config());
            } else {
                failedCount++;
            }
        }

        if (successes.isEmpty()) {
            log.error("config fetch: all {} url(s) failed", urls.size());
            return Optional.empty();
        }
        if (failedCount > 0) {
            log.warn("config fetch: {} of {} url(s) failed; continuing with the rest", failedCount, urls.size());
        }
        return Optional.of(RouterConfig.merge(successes, conflictReporter));
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
                log.warn("config fetch: {} failed unexpectedly", url, subtask.exception());
                yield new FetchOutcome.Failure(url);
            }
            case UNAVAILABLE -> new FetchOutcome.Failure(url);
        };
    }

    /// Up to [#maxAttempts] attempts, [#retryInterval] apart, for one URL.
    private FetchOutcome fetchWithRetry(String url) throws InterruptedException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            var config = attemptOnce(url);
            if (config.isPresent()) {
                return new FetchOutcome.Success(config.get());
            }
            if (attempt < maxAttempts) {
                Thread.sleep(retryInterval);
            }
        }
        log.warn("config fetch: {} failed after {} attempt(s)", url, maxAttempts);
        return new FetchOutcome.Failure(url);
    }

    /// One GET, decoded as a [RouterConfig]. Empty on any attempt failure —
    /// a non-2xx/3xx-exclusive status ("≥300"), a transport failure, or a
    /// body that isn't valid JSON for the shape (§8.1). [InterruptedException]
    /// is the one outcome that is never swallowed here: [HttpClient#send]
    /// declares it directly, and letting it propagate is what makes
    /// cancellation-by-interruption work at this blocking point
    /// (CONVENTIONS §8).
    private Optional<RouterConfig> attemptOnce(String url) throws InterruptedException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(requestTimeout)
                    .build();
        } catch (RuntimeException e) {
            log.warn("config fetch: malformed url {}", url, e);
            return Optional.empty();
        }

        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.warn("config fetch attempt failed for {}: {}", url, e.toString());
            return Optional.empty();
        }

        if (response.statusCode() >= 300) {
            log.warn("config fetch attempt failed for {}: status {}", url, response.statusCode());
            return Optional.empty();
        }

        try {
            return Optional.of(Json.MAPPER.readValue(response.body(), RouterConfig.class));
        } catch (JacksonException e) {
            log.warn("config fetch attempt failed for {}: invalid JSON: {}", url, e.toString());
            return Optional.empty();
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
