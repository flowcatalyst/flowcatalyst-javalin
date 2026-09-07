package io.flowcatalyst.http.vertx;

import io.flowcatalyst.router.pool.HttpVersion;
import io.flowcatalyst.router.pool.MediationTransport;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/// The production [MediationTransport] (`docs/spec/router-h2.md` §5, owner
/// ruling 2026-09-07): Vert.x's HTTP client, which — unlike
/// `java.net.http.HttpClient` — does h2c by *prior knowledge* for `http://`
/// targets (no `Upgrade` round trip, so it works on a request with a body)
/// and ALPN for `https://`. Configured once by [VertxMediationClient].
///
/// **Untimed wait, on purpose.** The caller is a router pool worker running
/// on a virtual thread, never the Vert.x event loop. The request's deadline
/// already lives on that loop's timer wheel via [RequestOptions#setTimeout];
/// this method blocks on `toCompletionStage().toCompletableFuture().get()`
/// with **no** timeout of its own, because a second, thread-side timer would
/// duplicate the deadline the loop already owns — the same "the loop owns
/// time, not a per-request timed park" ruling `VertxListener` follows for
/// inbound requests (2026-09-06 pool-wait ruling).
///
/// A timeout is reported as `java.net.http.HttpTimeoutException` — reused
/// here purely as a portable "this was a timeout" signal, not because this
/// class talks to the JDK client — so [io.flowcatalyst.router.pool.HttpMediator]
/// keeps mapping it to `"request timeout"` exactly as it does for
/// [io.flowcatalyst.router.pool.JdkTransport].
public final class VertxTransport implements MediationTransport {

    private final HttpClient client;

    VertxTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public Response send(URI target, byte[] body, List<Map.Entry<String, String>> headers, Duration timeout)
            throws IOException, InterruptedException {
        var options = new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setAbsoluteURI(target.toString())
                // Both connect and idle timeouts — an idle target (never
                // sends data back) and a target that never accepts a
                // connection are both "request timeout" as far as the
                // mediator is concerned.
                .setTimeout(timeout.toMillis())
                // Belt-and-braces: the client-wide default is already
                // false (a redirect downgrades POST to GET and drops the
                // body — §13 Q5), stated per-request so it reads as a
                // decision rather than an accident of the default.
                .setFollowRedirects(false);

        var future = client.request(options)
                .compose(request -> {
                    for (var header : headers) {
                        request.putHeader(header.getKey(), header.getValue());
                    }
                    return request.send(Buffer.buffer(body));
                })
                .compose(response -> response.body().map(responseBody -> toResponse(response, responseBody)));

        try {
            return future.toCompletionStage().toCompletableFuture().get();
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw new HttpTimeoutException("request timeout");
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(cause != null ? cause.getMessage() : e.getMessage(), cause);
        }
    }

    private static Response toResponse(HttpClientResponse response, Buffer body) {
        String retryAfter = response.getHeader("Retry-After");
        HttpVersion version = response.version() == io.vertx.core.http.HttpVersion.HTTP_2
                ? HttpVersion.HTTP_2
                : HttpVersion.HTTP_1_1;
        return new Response(response.statusCode(), body.getBytes(), retryAfter, version);
    }
}
