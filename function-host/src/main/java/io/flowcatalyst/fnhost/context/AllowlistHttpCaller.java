package io.flowcatalyst.fnhost.context;

import io.flowcatalyst.function.HttpCall;
import io.flowcatalyst.function.HttpCallRefusedException;
import io.flowcatalyst.function.HttpCaller;
import io.flowcatalyst.function.HttpReply;
import io.flowcatalyst.function.HttpResponseTooLargeException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// [HttpCaller] over one shared [HttpClient] (spec `function-context.md`
/// §2): the target host must be on `manifest.httpAllow` ([HttpAllowlist]);
/// `https` only, except a loopback host; redirects are never followed (the
/// client is built with [HttpClient.Redirect#NEVER] — the function sees the
/// 3xx itself); the request timeout is `min(call.timeout() or
/// [#DEFAULT_CALL_TIMEOUT], time left before the invocation's own deadline)`
/// ([InvocationDeadline]).
public final class AllowlistHttpCaller implements HttpCaller {

    /// The host's own default call timeout, used when [HttpCall#timeout()]
    /// is `null` — generous enough never to be the binding constraint in
    /// practice; the invocation deadline is almost always the tighter of
    /// the two.
    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;
    private final HttpAllowlist allowlist;
    private final Clock clock;

    public AllowlistHttpCaller(HttpClient client, List<String> httpAllow, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowlist = new HttpAllowlist(httpAllow);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// The client every [AllowlistHttpCaller] shares — one per host process,
    /// built once with redirects disabled (spec §2: "redirects are **not**
    /// followed").
    public static HttpClient newSharedClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public HttpReply send(HttpCall call) throws Exception {
        Objects.requireNonNull(call, "call");
        URI uri;
        try {
            uri = URI.create(call.url());
        } catch (RuntimeException e) {
            throw new HttpCallRefusedException(call.url(), "not a valid URL");
        }
        String host = uri.getHost();
        String scheme = uri.getScheme();
        boolean loopback = HttpAllowlist.isLoopback(host);
        if (!"https".equalsIgnoreCase(scheme) && !loopback) {
            throw new HttpCallRefusedException(String.valueOf(host),
                    "scheme '" + scheme + "' is not permitted (https only, except loopback)");
        }
        if (!allowlist.allows(host)) {
            throw new HttpCallRefusedException(String.valueOf(host), "not on this function's httpAllow list");
        }

        Duration remaining = InvocationDeadline.remaining(clock);
        Duration ceiling = call.timeout() != null ? call.timeout() : DEFAULT_CALL_TIMEOUT;
        Duration timeout = remaining.compareTo(ceiling) < 0 ? remaining : ceiling;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new HttpCallRefusedException(String.valueOf(host), "no time left before the invocation deadline");
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        for (Map.Entry<String, List<String>> header : call.headers().entrySet()) {
            for (String value : header.getValue()) {
                builder.header(header.getKey(), value);
            }
        }
        byte[] body = call.body();
        builder.method(call.method(), body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        Map<String, List<String>> headers = response.headers().map();
        return new HttpReply(response.statusCode(), headers, cappedBody(response, String.valueOf(host)));
    }

    /// The body, read as a stream and refused past [HttpResponseTooLargeException#MAX_BYTES]
    /// (owner ruling 2026-09-25, item 9). A declared `Content-Length` over the cap fails
    /// before anything is read. Closing the stream early releases the connection.
    static byte[] cappedBody(HttpResponse<InputStream> response, String host) throws IOException {
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        try (InputStream in = response.body()) {
            if (declared > HttpResponseTooLargeException.MAX_BYTES) {
                throw new HttpResponseTooLargeException(host);
            }
            byte[] body = in.readNBytes(HttpResponseTooLargeException.MAX_BYTES + 1);
            if (body.length > HttpResponseTooLargeException.MAX_BYTES) {
                throw new HttpResponseTooLargeException(host);
            }
            return body;
        }
    }
}
