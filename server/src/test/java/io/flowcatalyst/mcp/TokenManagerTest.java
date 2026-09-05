package io.flowcatalyst.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [TokenManager] against a stub `/oauth/token` endpoint (`docs/spec/mcp.md`
/// §2/§5): the client_credentials form, the cache, the refresh-before-expiry
/// boundary (via a fake [Clock]), and a non-2xx surfacing the status.
class TokenManagerTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastForm = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            hits.incrementAndGet();
            lastForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        responseBody.set("""
                {"access_token":"tok-1","token_type":"Bearer","expires_in":3600}""");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /// A [Clock] test code can advance without waiting in real time.
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void firstCallFetchesAndSendsTheClientCredentialsForm() {
        var tm = new TokenManager(baseUrl, "cid", "csecret", HttpClient.newHttpClient(),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        var token = tm.token();

        assertThat(token).isEqualTo("tok-1");
        assertThat(hits).hasValue(1);
        assertThat(lastForm.get()).contains("grant_type=client_credentials")
                .contains("client_id=cid").contains("client_secret=csecret");
    }

    @Test
    void aSecondCallWithinValidityReusesTheCacheAndMakesNoSecondRequest() {
        var tm = new TokenManager(baseUrl, "cid", "csecret", HttpClient.newHttpClient(),
                new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        tm.token();
        var second = tm.token();

        assertThat(second).isEqualTo("tok-1");
        assertThat(hits).as("cached — no second HTTP call").hasValue(1);
    }

    /// Pins the refresh-before-expiry boundary: advancing the clock to
    /// within [TokenManager#REFRESH_BUFFER] of the cached token's expiry
    /// must trigger a refetch, not reuse a token that is about to expire.
    /// Breaking this (e.g. comparing against `expiresAt` with no buffer)
    /// makes this test fail: it would still report hits=1/tok-1 instead of
    /// the expected refetch.
    @Test
    void withinTheRefreshBufferOfExpiryTheNextCallRefetches() {
        responseBody.set("""
                {"access_token":"tok-1","expires_in":3600}""");
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var tm = new TokenManager(baseUrl, "cid", "csecret", HttpClient.newHttpClient(), clock);
        tm.token();
        assertThat(hits).hasValue(1);

        // 3600s TTL, 60s buffer: at T+3550 we are within the buffer of the
        // T+3600 expiry, so the cached token must no longer be reused.
        clock.advance(Duration.ofSeconds(3550));
        responseBody.set("""
                {"access_token":"tok-2","expires_in":3600}""");

        var refreshed = tm.token();

        assertThat(refreshed).isEqualTo("tok-2");
        assertThat(hits).as("must have refetched, not reused the about-to-expire token").hasValue(2);
    }

    @Test
    void wellBeforeExpiryTheCacheIsStillHonoured() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var tm = new TokenManager(baseUrl, "cid", "csecret", HttpClient.newHttpClient(), clock);
        tm.token();

        // Far short of the 60s buffer before the 3600s expiry.
        clock.advance(Duration.ofSeconds(100));
        var stillCached = tm.token();

        assertThat(stillCached).isEqualTo("tok-1");
        assertThat(hits).hasValue(1);
    }

    @Test
    void non2xxThrowsSurfacingTheStatus() {
        status.set(401);
        responseBody.set("""
                {"error":"invalid_client"}""");
        var tm = new TokenManager(baseUrl, "cid", "bad-secret");

        assertThatThrownBy(tm::token)
                .isInstanceOf(TokenManager.TokenException.class)
                .hasMessageContaining("401");
    }

    @Test
    void aMissingExpiresInDefaultsToOneHourRatherThanBeingTreatedAsAlreadyStale() {
        responseBody.set("""
                {"access_token":"tok-1"}""");
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var tm = new TokenManager(baseUrl, "cid", "csecret", HttpClient.newHttpClient(), clock);

        tm.token();
        clock.advance(Duration.ofMinutes(5));
        tm.token();

        assertThat(hits).as("a defaulted 1h TTL must not be treated as already expired").hasValue(1);
    }

    /// The outbox standalone poller's optional `--scope` narrowing
    /// (`docs/spec/fcdev-commands.md` §3): sent as the form field only when
    /// non-blank.
    @Test
    void aNonBlankScopeIsSentAsAFormField() {
        var tm = new TokenManager(baseUrl, "cid", "csecret", "read:events", HttpClient.newHttpClient(), Clock.systemUTC());

        tm.token();

        assertThat(lastForm.get()).contains("scope=read%3Aevents");
    }

    @Test
    void aBlankOrAbsentScopeOmitsTheFormFieldEntirely() {
        var tm = new TokenManager(baseUrl, "cid", "csecret", "  ", HttpClient.newHttpClient(), Clock.systemUTC());

        tm.token();

        assertThat(lastForm.get()).doesNotContain("scope=");
    }

    /// [TokenManager#invalidate()] is the outbox dispatcher's 401 hook
    /// (`HttpDispatcher.TokenSource#invalidate`): dropping the cache must
    /// force a refetch even though the cached token is nowhere near expiry
    /// — a broken `invalidate()` (a no-op) would still show `hits=1` here.
    @Test
    void invalidateForcesARefetchEvenWellBeforeExpiry() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var tm = new TokenManager(baseUrl, "cid", "csecret", HttpClient.newHttpClient(), clock);

        tm.token();
        tm.invalidate();
        tm.token();

        assertThat(hits).as("invalidate() must force a second HTTP call well before the cached token expires")
                .hasValue(2);
    }
}
