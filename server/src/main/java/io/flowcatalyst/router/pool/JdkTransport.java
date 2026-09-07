package io.flowcatalyst.router.pool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/// The JDK [HttpClient] transport (`docs/spec/router-h2.md` §5): what dev
/// mode uses, via [HttpMediator#defaultClient] pinned to HTTP/1.1. Wraps
/// whatever [HttpClient] it is given rather than building its own, so tests
/// exercising other client shapes (TLS+ALPN, HTTP/2) go through the same
/// seam.
///
/// `HttpTimeoutException` (a subtype of [IOException] the JDK client throws
/// on its own timeout) propagates unchanged — [HttpMediator] catches it
/// specifically to report "request timeout" rather than a generic
/// connection failure.
public final class JdkTransport implements MediationTransport {

    private final HttpClient client;

    public JdkTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public Response send(URI target, byte[] body, List<Map.Entry<String, String>> headers, Duration timeout)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(target)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(timeout);
        for (var header : headers) {
            builder.header(header.getKey(), header.getValue());
        }
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        HttpVersion version = response.version() == HttpClient.Version.HTTP_2
                ? HttpVersion.HTTP_2
                : HttpVersion.HTTP_1_1;
        return new Response(response.statusCode(), response.body(), retryAfter, version);
    }
}
