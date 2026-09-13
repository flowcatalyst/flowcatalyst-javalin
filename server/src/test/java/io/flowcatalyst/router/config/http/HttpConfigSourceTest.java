package io.flowcatalyst.router.config.http;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.observability.Warnings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// [HttpConfigSource] against `docs/spec/router.md` §8.1. Every test binds a
/// real [HttpServer] on loopback — no mocking library, and no network beyond
/// this process (CONVENTIONS §6). Retry attempts/interval are always
/// constructor-injected small so the *mechanism* (count, spacing, ordering,
/// parallelism) is provable in milliseconds rather than at the production
/// 12×5 s schedule, which is pinned separately as constants.
class HttpConfigSourceTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    private HttpServer startServer(HttpHandler handler) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/config", handler);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            servers.add(server);
            return server;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/config";
    }

    private static void respondOk(HttpExchange exchange, RouterConfig config) throws IOException {
        byte[] body = Json.MAPPER.writeValueAsBytes(config);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void respondInvalidJson(HttpExchange exchange) throws IOException {
        byte[] body = "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void respondStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static RouterConfig configWithQueue(String uri, int visibilityTimeout) {
        return new RouterConfig(List.of(), List.of(new QueueConfig(uri, "q", 1, visibilityTimeout)));
    }

    private HttpConfigSource source(List<String> urls, int maxAttempts, Duration interval, Duration requestTimeout) {
        var client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        return new HttpConfigSource(urls, client, maxAttempts, interval, requestTimeout);
    }

    private HttpConfigSource sourceWithWarnings(List<String> urls, int maxAttempts, Duration interval,
                                                Duration requestTimeout, Warnings warnings) {
        var client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        return new HttpConfigSource(urls, client, maxAttempts, interval, requestTimeout, warnings);
    }

    // ---- URL parsing --------------------------------------------------------------

    @Test
    @DisplayName("splits FLOWCATALYST_CONFIG_URL on commas, trims each part and drops empties")
    void parsesCommaSeparatedUrls() {
        assertThat(HttpConfigSource.parseUrls(" http://a/config , http://b/config ,, http://c/config"))
                .containsExactly("http://a/config", "http://b/config", "http://c/config");
    }

    @Test
    @DisplayName("parses a null or blank env value as no URLs")
    void parsesAbsentAsEmpty() {
        assertThat(HttpConfigSource.parseUrls(null)).isEmpty();
        assertThat(HttpConfigSource.parseUrls("   ")).isEmpty();
    }

    // ---- production constants (spec pin) -------------------------------------------

    @Test
    @DisplayName("pins the spec's retry schedule: 12 attempts, 5s apart, 10s per-attempt timeout")
    void pinsProductionConstants() {
        assertThat(HttpConfigSource.DEFAULT_MAX_ATTEMPTS).isEqualTo(12);
        assertThat(HttpConfigSource.DEFAULT_RETRY_INTERVAL).isEqualTo(Duration.ofSeconds(5));
        assertThat(HttpConfigSource.DEFAULT_REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    // ---- parallel fetch: timing --------------------------------------------------

    @Test
    @DisplayName("fetches every URL in parallel: elapsed time tracks the slowest URL, not their sum")
    void fetchesInParallel() throws IOException {
        var delay = Duration.ofMillis(350);
        var s1 = startServer(exchange -> {
            sleepQuietly(delay);
            respondOk(exchange, configWithQueue("postgres://a/db", 10));
        });
        var s2 = startServer(exchange -> {
            sleepQuietly(delay);
            respondOk(exchange, configWithQueue("postgres://b/db", 20));
        });

        var src = source(List.of(urlOf(s1), urlOf(s2)), 1, Duration.ofMillis(10), Duration.ofSeconds(5));

        var start = Instant.now();
        var result = src.fetch();
        var elapsed = Duration.between(start, Instant.now());

        assertThat(result).isPresent();
        // Sequential would cost ~2x delay (~700ms); parallel costs ~1x (~350ms).
        // The midpoint (525ms) cleanly separates the two.
        assertThat(elapsed).as("elapsed %s should track the slowest URL (%s), not the sum", elapsed, delay)
                .isLessThan(delay.multipliedBy(2).minus(Duration.ofMillis(150)));
    }

    // ---- retry count and spacing ---------------------------------------------------

    @Test
    @DisplayName("retries a failing URL up to the configured attempt count, then gives up")
    void retriesUpToMaxAttempts() {
        var requests = new AtomicInteger();
        var server = startServer(exchange -> {
            requests.incrementAndGet();
            respondStatus(exchange, 500);
        });

        var src = source(List.of(urlOf(server)), 4, Duration.ofMillis(20), Duration.ofSeconds(5));
        var result = src.fetch();

        assertThat(result).isEmpty();
        assertThat(requests.get()).as("exactly maxAttempts requests, no more, no fewer").isEqualTo(4);
    }

    @Test
    @DisplayName("stops retrying as soon as an attempt succeeds")
    void stopsRetryingOnSuccess() {
        var requests = new AtomicInteger();
        var server = startServer(exchange -> {
            int n = requests.incrementAndGet();
            if (n < 3) {
                respondStatus(exchange, 503);
            } else {
                respondOk(exchange, configWithQueue("postgres://ok/db", 10));
            }
        });

        var src = source(List.of(urlOf(server)), 10, Duration.ofMillis(20), Duration.ofSeconds(5));
        var result = src.fetch();

        assertThat(result).isPresent();
        assertThat(requests.get()).as("must not keep retrying past the first success").isEqualTo(3);
    }

    @Test
    @DisplayName("spaces retries by the configured interval")
    void spacesRetriesByInterval() {
        var interval = Duration.ofMillis(150);
        var timestamps = new CopyOnWriteArrayList<Instant>();
        var server = startServer(exchange -> {
            timestamps.add(Instant.now());
            respondStatus(exchange, 500);
        });

        source(List.of(urlOf(server)), 3, interval, Duration.ofSeconds(5)).fetch();

        assertThat(timestamps).hasSize(3);
        for (int i = 1; i < timestamps.size(); i++) {
            var gap = Duration.between(timestamps.get(i - 1), timestamps.get(i));
            assertThat(gap).as("gap between attempt %d and %d", i, i + 1)
                    .isGreaterThanOrEqualTo(interval.minusMillis(30));
        }
    }

    // ---- all-fail vs partial-fail ---------------------------------------------------

    @Test
    @DisplayName("answers empty when every URL fails")
    void emptyWhenAllUrlsFail() {
        var s1 = startServer(exchange -> respondStatus(exchange, 500));
        var s2 = startServer(exchange -> respondStatus(exchange, 500));

        var src = source(List.of(urlOf(s1), urlOf(s2)), 2, Duration.ofMillis(10), Duration.ofSeconds(5));

        assertThat(src.fetch()).isEmpty();
    }

    @Test
    @DisplayName("a source that recovers and breaks again logs a stack trace for the new streak")
    void recoveryResetsTheCauseLatch() {
        // Without the reset, "one trace per streak" degrades into "one trace
        // ever": a source that comes back and then fails for a *different*
        // reason would be diagnosed with no cause at all.
        var attempt = new AtomicInteger();
        var server = startServer(exchange -> {
            if (attempt.incrementAndGet() == 2) {
                respondOk(exchange, configWithQueue("postgres://ok/db", 1));
            } else {
                respondInvalidJson(exchange);
            }
        });
        var src = source(List.of(urlOf(server)), 4, Duration.ofMillis(10), Duration.ofSeconds(2));

        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        var log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HttpConfigSource.class);
        log.addAppender(captured);
        try {
            assertThat(src.fetch()).as("attempt 1 bad JSON, attempt 2 recovers").isPresent();
            src.fetch();   // attempts 3+ are a fresh streak

            var traces = captured.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("invalid JSON"))
                    .filter(e -> e.getThrowableProxy() != null)
                    .toList();
            assertThat(traces).as("one trace per streak, and the recovery started a new one").hasSize(2);
        } finally {
            log.detachAppender(captured);
        }
    }

    @Test
    @DisplayName("a URL that never succeeds logs one stack trace for the streak, not one per retry")
    void neverSucceededUrlLogsTheCauseOnce() throws IOException {
        // The case a latch keyed on the `failing` set would miss entirely:
        // nothing has ever succeeded here, so there is no last-known-good and
        // the URL never enters that set — yet it is retried every
        // DEFAULT_RETRY_INTERVAL forever, which is exactly the misconfigured
        // config URL that never recovers on its own.
        int deadPort;
        try (var s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        var url = "http://127.0.0.1:" + deadPort + "/config";

        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        var log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HttpConfigSource.class);
        log.addAppender(captured);
        try {
            source(List.of(url), 4, Duration.ofMillis(10), Duration.ofSeconds(2)).fetch();

            var attempts = captured.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("config fetch attempt failed"))
                    .toList();
            assertThat(attempts).as("every attempt is still logged").hasSizeGreaterThanOrEqualTo(2);
            assertThat(attempts.stream().filter(e -> e.getThrowableProxy() != null).toList())
                    .as("exactly one stack trace for the streak")
                    .hasSize(1);
            assertThat(attempts.getLast().getKeyValuePairs())
                    .as("the later attempts still name the error")
                    .anySatisfy(kv -> assertThat(kv.key).isEqualTo("reason"));
        } finally {
            log.detachAppender(captured);
        }
    }

    @Test
    @DisplayName("a URL that has never succeeded has no last-known-good and is dropped")
    void neverSucceededUrlIsDropped() {
        var alwaysFailing = startServer(exchange -> respondStatus(exchange, 500));
        var succeeding = startServer(exchange -> respondOk(exchange, configWithQueue("postgres://survivor/db", 42)));

        var src = source(List.of(urlOf(alwaysFailing), urlOf(succeeding)), 2, Duration.ofMillis(10), Duration.ofSeconds(5));
        var result = src.fetch();

        assertThat(result).isPresent();
        assertThat(result.get().queues()).extracting(QueueConfig::queueUri).containsExactly("postgres://survivor/db");
    }

    // ---- R-30: per-source last-known-good --------------------------------------------

    @Test
    @DisplayName("R-30: a source that starts failing after succeeding keeps serving its last-known-good pools")
    void failingSourceServesLastKnownGood() {
        var bIsFailing = new AtomicBoolean(false);
        var b = startServer(exchange -> {
            if (bIsFailing.get()) {
                respondStatus(exchange, 500);
            } else {
                respondOk(exchange, configWithQueue("postgres://b/db", 99));
            }
        });
        var a = startServer(exchange -> respondOk(exchange, configWithQueue("postgres://a/db", 1)));

        var src = source(List.of(urlOf(a), urlOf(b)), 2, Duration.ofMillis(10), Duration.ofSeconds(5));
        assertThat(src.fetch()).as("first fetch: both URLs succeed and seed the cache").isPresent();

        bIsFailing.set(true);
        var result = src.fetch();

        assertThat(result).isPresent();
        assertThat(result.get().queues()).extracting(QueueConfig::queueUri)
                .as("B's last-known-good pools still participate, not just A's fresh ones")
                .containsExactlyInAnyOrder("postgres://a/db", "postgres://b/db");
    }

    @Test
    @DisplayName("R-30: a stale last-known-good from the first-declared URL still wins a merge conflict over a fresh, later URL")
    void staleLastKnownGoodKeepsDeclaredOrderPrecedence() {
        // Both URLs define the SAME queue; A is declared first and later
        // fails, B stays fresh. Precedence is a property of the configured
        // order, not of freshness — otherwise a source outage would silently
        // flip which definition of a shared queue is live.
        var aIsFailing = new AtomicBoolean(false);
        var a = startServer(exchange -> {
            if (aIsFailing.get()) {
                respondStatus(exchange, 500);
            } else {
                respondOk(exchange, configWithQueue("postgres://shared/db", 1));
            }
        });
        var b = startServer(exchange -> respondOk(exchange, configWithQueue("postgres://shared/db", 99)));

        var src = source(List.of(urlOf(a), urlOf(b)), 2, Duration.ofMillis(10), Duration.ofSeconds(5));
        assertThat(src.fetch()).as("first fetch seeds A's cache").isPresent();

        aIsFailing.set(true);
        var result = src.fetch();

        assertThat(result).isPresent();
        assertThat(result.get().queues())
                .as("one definition of the shared queue, and it is A's cached one")
                .extracting(QueueConfig::queueUri, QueueConfig::visibilityTimeout)
                .containsExactly(tuple("postgres://shared/db", 1));
    }

    @Test
    @DisplayName("R-30: a failing streak raises exactly one CONFIGURATION warning, not one per fetch")
    void failingStreakWarnsOnce() {
        var isFailing = new AtomicBoolean(false);
        var server = startServer(exchange -> {
            if (isFailing.get()) {
                respondStatus(exchange, 500);
            } else {
                respondOk(exchange, configWithQueue("postgres://a/db", 1));
            }
        });
        var warnings = new RecordingWarnings();
        var src = sourceWithWarnings(List.of(urlOf(server)), 2, Duration.ofMillis(10), Duration.ofSeconds(5), warnings);
        assertThat(src.fetch()).isPresent();

        isFailing.set(true);
        src.fetch();
        src.fetch();
        src.fetch();

        assertThat(warnings.raised).as("one warning for the whole streak, not one per fetch").hasSize(1);
        assertThat(warnings.raised.getFirst())
                .contains("WARNING").contains("CONFIGURATION").contains(urlOf(server));
    }

    @Test
    @DisplayName("R-30: recovery raises an INFO notice and clears the streak, so a later failure warns again")
    void recoveryClearsStreakThenWarnsAgain() {
        var isFailing = new AtomicBoolean(false);
        var server = startServer(exchange -> {
            if (isFailing.get()) {
                respondStatus(exchange, 500);
            } else {
                respondOk(exchange, configWithQueue("postgres://a/db", 1));
            }
        });
        var warnings = new RecordingWarnings();
        var src = sourceWithWarnings(List.of(urlOf(server)), 2, Duration.ofMillis(10), Duration.ofSeconds(5), warnings);
        src.fetch(); // seeds the cache

        isFailing.set(true);
        src.fetch(); // -> WARNING
        isFailing.set(false);
        src.fetch(); // -> INFO recovered
        isFailing.set(true);
        src.fetch(); // -> WARNING again

        assertThat(warnings.raised).hasSize(3);
        assertThat(warnings.raised.get(0)).contains("WARNING").contains("CONFIGURATION");
        assertThat(warnings.raised.get(1)).contains("INFO").contains("CONFIGURATION").contains("recovered");
        assertThat(warnings.raised.get(2)).contains("WARNING").contains("CONFIGURATION");
    }

    @Test
    @DisplayName("a source that has never answered warns once per streak, then reports its recovery")
    void neverSucceededSourceWarnsOnceThenRecovers() {
        var isFailing = new AtomicBoolean(true);
        var server = startServer(exchange -> {
            if (isFailing.get()) {
                respondStatus(exchange, 500);
            } else {
                respondOk(exchange, configWithQueue("postgres://a/db", 1));
            }
        });
        var warnings = new RecordingWarnings();
        var src = sourceWithWarnings(List.of(urlOf(server)), 2, Duration.ofMillis(10), Duration.ofSeconds(5), warnings);

        assertThat(src.fetch()).as("nothing to fall back to").isEmpty();
        src.fetch();
        assertThat(warnings.raised).as("the operator hears about it — once, not per poll").hasSize(1);
        assertThat(warnings.raised.getFirst())
                .contains("WARNING").contains("CONFIGURATION").contains(urlOf(server)).contains("never supplied");

        isFailing.set(false);
        assertThat(src.fetch()).isPresent();
        assertThat(warnings.raised).hasSize(2);
        assertThat(warnings.raised.get(1)).contains("INFO").contains("recovered");
    }

    // ---- URL-order collection (first-definition-wins, independent of completion order) --

    @Test
    @DisplayName("merges successes in URL order, not completion order")
    void mergesInUrlOrderNotCompletionOrder() {
        // The FIRST url in the list answers slow; the SECOND answers fast.
        // If the merge used completion order, the fast (second) URL's value
        // would win. The spec requires the declared order to win instead.
        var slowFirst = startServer(exchange -> {
            sleepQuietly(Duration.ofMillis(200));
            respondOk(exchange, configWithQueue("postgres://shared/db", 111));
        });
        var fastSecond = startServer(exchange -> respondOk(exchange, configWithQueue("postgres://shared/db", 222)));

        var src = source(List.of(urlOf(slowFirst), urlOf(fastSecond)), 1, Duration.ofMillis(10), Duration.ofSeconds(5));
        var result = src.fetch();

        assertThat(result).isPresent();
        assertThat(result.get().queues()).singleElement()
                .extracting(QueueConfig::visibilityTimeout)
                .as("the first URL in the list wins, even though the second answered first")
                .isEqualTo(111);
    }

    // ---- unchanged configuration is still returned -----------------------------------

    @Test
    @DisplayName("returns the same configuration again on a second fetch rather than suppressing it as unchanged")
    void doesNotSuppressAnUnchangedConfiguration() {
        var server = startServer(exchange -> respondOk(exchange, configWithQueue("postgres://steady/db", 30)));
        var src = source(List.of(urlOf(server)), 1, Duration.ofMillis(10), Duration.ofSeconds(5));

        var first = src.fetch();
        var second = src.fetch();

        assertThat(first).isPresent();
        assertThat(second).as("an unchanged config must still be handed back, never suppressed").isPresent();
        assertThat(second).isEqualTo(first);
    }

    // ---- cancellation is interruption -------------------------------------------------

    @Test
    @DisplayName("a blocked fetch exits promptly on interruption, with the interrupt flag restored")
    void cancellationIsInterruption() throws InterruptedException {
        var requestReceived = new CountDownLatch(1);
        var releaseServer = new CountDownLatch(1);
        var server = startServer(exchange -> {
            requestReceived.countDown();
            try {
                // Hangs until the test releases it (or the test times out and
                // the server is torn down in @AfterEach). Long enough that
                // only interruption — never the request timeout — explains a
                // prompt return.
                releaseServer.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                respondStatus(exchange, 200);
            } catch (IOException ignored) {
            }
        });

        var src = source(List.of(urlOf(server)), 5, Duration.ofSeconds(1), Duration.ofSeconds(30));
        var result = new AtomicReference<Optional<RouterConfig>>();
        var interruptedAfterReturn = new AtomicBoolean();

        Thread worker = new Thread(() -> {
            result.set(src.fetch());
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        }, "config-fetch-worker");
        worker.start();

        assertThat(requestReceived.await(5, TimeUnit.SECONDS)).as("the request must have started").isTrue();
        worker.interrupt();
        worker.join(5_000);

        assertThat(worker.isAlive()).as("interruption must make the fetch return promptly").isFalse();
        assertThat(interruptedAfterReturn.get()).as("the interrupt flag must be restored, not swallowed").isTrue();
        assertThat(result.get()).as("an interrupted fetch reports no configuration").isEmpty();

        releaseServer.countDown();
    }

    // ---- optional bearer auth (`docs/spec/router-config-auth.md` §2) ---------------

    private HttpServer startServerWithAuth(HttpHandler configHandler, HttpHandler tokenHandler) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/config", configHandler);
            server.createContext("/oauth/token", tokenHandler);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            servers.add(server);
            return server;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void respondToken(HttpExchange exchange, String accessToken) throws IOException {
        byte[] body = ("{\"access_token\":\"" + accessToken + "\",\"expires_in\":3600}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String originOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpConfigSource sourceWithAuth(List<String> urls, int maxAttempts, Duration interval,
                                             Duration requestTimeout, io.flowcatalyst.http.oauth.TokenManager tokenManager,
                                             String platformOrigin) {
        var client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        return new HttpConfigSource(urls, client, maxAttempts, interval, requestTimeout, Warnings.NO_OP,
                tokenManager, platformOrigin);
    }

    /// Pins the same-origin rule (§2): the platform's own URL carries the
    /// bearer header, a different origin never sees the credential at all —
    /// not merely "not the platform's token", but no `Authorization` header
    /// whatsoever. A mutant that attached the header to every URL (dropping
    /// the origin check) would turn the third-party assertion from `null`
    /// into a real header value.
    @Test
    @DisplayName("with a token manager, a request to the platform's own origin carries the bearer header; a different origin does not")
    void tokenManagerAttachesBearerOnlyToThePlatformOrigin() {
        var tokenHits = new AtomicInteger();
        var platformAuthSeen = new AtomicReference<String>();
        var platform = startServerWithAuth(
                exchange -> {
                    platformAuthSeen.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    respondOk(exchange, configWithQueue("postgres://platform/db", 1));
                },
                exchange -> {
                    tokenHits.incrementAndGet();
                    respondToken(exchange, "tok-1");
                });
        var thirdPartyAuthSeen = new AtomicReference<String>();
        var thirdParty = startServer(exchange -> {
            thirdPartyAuthSeen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respondOk(exchange, configWithQueue("postgres://third-party/db", 2));
        });

        String platformOrigin = originOf(platform);
        var tokenManager = new io.flowcatalyst.http.oauth.TokenManager(platformOrigin, "cid", "secret");
        var src = sourceWithAuth(List.of(urlOf(platform), urlOf(thirdParty)), 2, Duration.ofMillis(10),
                Duration.ofSeconds(5), tokenManager, platformOrigin);

        var result = src.fetch();

        assertThat(result).isPresent();
        assertThat(platformAuthSeen.get()).as("the platform's own origin carries the token").isEqualTo("Bearer tok-1");
        assertThat(thirdPartyAuthSeen.get()).as("a different origin never sees the credential").isNull();
        assertThat(tokenHits).as("one mint for the whole fetch (both URLs share the cached token)").hasValue(1);
    }

    /// A `401` from a request that carried the token invalidates the cache,
    /// so the NEXT attempt re-mints rather than replaying a token already
    /// known to be rejected (§2). Counting token-endpoint hits, not merely
    /// "the fetch eventually succeeds", is what a mutant that forgot to call
    /// `invalidate()` cannot fake: it would still show exactly one mint.
    @Test
    @DisplayName("a 401 from the platform invalidates the cached token; the next attempt re-mints")
    void a401FromThePlatformInvalidatesTheCachedToken() {
        var tokenHits = new AtomicInteger();
        var rejectNext = new AtomicBoolean(true);
        var platform = startServerWithAuth(
                exchange -> {
                    if (rejectNext.get()) {
                        respondStatus(exchange, 401);
                    } else {
                        respondOk(exchange, configWithQueue("postgres://platform/db", 1));
                    }
                },
                exchange -> {
                    tokenHits.incrementAndGet();
                    respondToken(exchange, "tok-" + tokenHits.get());
                });

        String platformOrigin = originOf(platform);
        var tokenManager = new io.flowcatalyst.http.oauth.TokenManager(platformOrigin, "cid", "secret");
        var src = sourceWithAuth(List.of(urlOf(platform)), 1, Duration.ofMillis(10), Duration.ofSeconds(5),
                tokenManager, platformOrigin);

        assertThat(src.fetch()).as("first attempt: minted token rejected with 401, no last-known-good yet").isEmpty();
        assertThat(tokenHits).as("first mint").hasValue(1);

        rejectNext.set(false);
        assertThat(src.fetch()).as("second attempt: the invalidated cache forces a re-mint, which the platform now accepts").isPresent();
        assertThat(tokenHits).as("the 401 must have invalidated the cache — a second mint, not a replay of tok-1").hasValue(2);
    }

    /// A minting failure ([io.flowcatalyst.http.oauth.TokenManager.TokenException])
    /// is an attempt failure like any other transport failure — `fetch()`
    /// answers empty, it never throws out of the retry loop. A mutant that
    /// let the exception propagate uncaught would fail this test with the
    /// exception itself rather than a clean `isEmpty()`.
    @Test
    @DisplayName("a token-minting failure is an ordinary attempt failure, not an exception out of fetch()")
    void aMintingFailureIsAnAttemptFailureNotAnException() {
        // The token endpoint always fails (a real, counted HTTP round trip —
        // TokenManager surfaces its non-2xx as a TokenException).
        var tokenHits = new AtomicInteger();
        var platform = startServerWithAuth(
                exchange -> respondOk(exchange, configWithQueue("postgres://platform/db", 1)),
                exchange -> {
                    tokenHits.incrementAndGet();
                    respondStatus(exchange, 500);
                });
        String platformOrigin = originOf(platform);
        var tokenManager = new io.flowcatalyst.http.oauth.TokenManager(platformOrigin, "cid", "secret");
        // 3 attempts: if the TokenException escaped attemptOnce() uncaught (the
        // mutant this pins), it would blow straight past fetchWithRetry's loop and
        // abort the whole subtask on the FIRST failure — exactly 1 hit, not 3 — even
        // though src.fetch() would still (via the StructuredTaskScope's own FAILED-subtask
        // handling) come back empty either way, which is why isEmpty() alone cannot
        // tell the two apart.
        var src = sourceWithAuth(List.of(urlOf(platform)), 3, Duration.ofMillis(10), Duration.ofSeconds(5),
                tokenManager, platformOrigin);

        assertThat(src.fetch()).as("minting failed on every attempt; no last-known-good to fall back to").isEmpty();
        assertThat(tokenHits).as("the retry loop must keep retrying past a minting failure, same as any other "
                + "transport failure — not abort the streak on the first one").hasValue(3);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingWarnings implements Warnings {
        final List<String> raised = new CopyOnWriteArrayList<>();

        @Override
        public void raise(Severity severity, String category, String message) {
            raised.add(severity + " " + category + " " + message);
        }
    }
}
