package io.flowcatalyst.server;

import io.flowcatalyst.http.Group;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.ClaimsResolver;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// Observability audit, 2026-09-14: a signed-in browser hitting an unknown
/// `/api/*` path (served by the SPA catch-all, a `NO_DB` route) made the
/// authenticator look the session's principal up, which the `NO_DB` pool
/// guard refuses — a warning with a stack trace per request. A `NO_DB`
/// request can never borrow a connection and none of those routes read a
/// session, so the authenticator must not run for it at all.
class PlatformAuthenticatedHandlerTest {

    @Test
    @DisplayName("the authenticator never runs for a NO_DB request, and still runs for a pool-backed one")
    void aNoDbRequestNeverReachesTheSessionLookup() {
        var keys = SigningKeys.generateEphemeral();
        String base = "http://test.local";
        var issuer = new TokenIssuer(keys, TokenIssuer.Config.of(base));
        var verifier = new JwtVerifier(new JwtVerifier.Config(base, new JwtVerifier.RsaKeys(keys.publicKey())));
        var lookups = new AtomicInteger();
        ClaimsResolver counting = principalId -> {
            lookups.incrementAndGet();
            return Optional.empty();
        };
        var authenticator = new Authenticator(verifier, counting, Authenticator.Config.of(false));
        String cookie = "fc_session=" + issuer.sessionToken("prn_test", "someone@example.com");

        try (var http = TestHttp.routes(routes -> {
            routes.before(Platform.authenticated(authenticator));
            routes.get("/api/db-backed", ctx -> ctx.result("db")); // ungrouped under /api/ → API_READ
            // The SPA catch-all shape, registered last as Frontend does: an
            // /api/* path nothing else claimed, DB-free.
            routes.in(Group.NO_DB).get("/api/<path>", ctx -> ctx.result("spa"));
        })) {
            var spa = http.get("/api/nothing-here", "Cookie", cookie);
            assertThat(spa.statusCode()).isEqualTo(200);
            assertThat(lookups.get()).as("mutant: the authenticator ran for a NO_DB request").isZero();

            http.get("/api/db-backed", "Cookie", cookie);
            assertThat(lookups.get()).as("sanity: the same cookie is looked up on a pool-backed route").isEqualTo(1);
        }
    }
}
