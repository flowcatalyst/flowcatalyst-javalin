package io.flowcatalyst.router.pool;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/// The seam between [HttpMediator]'s decision-making (request shape,
/// classification, breaker, warnings — all unchanged) and the HTTP client
/// that actually makes the call (`docs/spec/router-h2.md` §5, owner ruling
/// 2026-09-07).
///
/// Two implementations: [JdkTransport] (the JDK client, HTTP/1.1 only, dev
/// mode) and `io.flowcatalyst.http.vertx.VertxTransport` (prior-knowledge
/// h2c / ALPN, deployed) — the latter lives in the Vert.x adapter package
/// rather than here because `NoFrameworkLeakTest` forbids an `io.vertx`
/// import anywhere outside `io.flowcatalyst.http.vertx`.
///
/// A timeout is signalled with `java.net.http.HttpTimeoutException` (a
/// subtype of [IOException]) so [HttpMediator] can keep distinguishing "the
/// target never answered in time" from any other connection failure without
/// this interface depending on either client's own timeout exception type.
public interface MediationTransport {

    Response send(URI target, byte[] body, List<Map.Entry<String, String>> headers, Duration timeout)
            throws IOException, InterruptedException;

    /// @param body       defensively copied both in and out — callers must
    ///                    not be able to mutate this record's state through
    ///                    the array they passed or the array they got back
    /// @param retryAfter the raw `Retry-After` header value, unparsed, or
    ///                   `null` when the target sent none — the HTTP-date
    ///                   form is deliberately not honoured
    ///                   (`HttpMediator#retryAfterSeconds`); no empty-string
    ///                   sentinel (CONVENTIONS §8)
    /// @param version    the HTTP version the target actually spoke, fed to
    ///                   [PoolMetrics#recordHttpVersion]
    record Response(int status, byte[] body, String retryAfter, HttpVersion version) {

        public Response {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
