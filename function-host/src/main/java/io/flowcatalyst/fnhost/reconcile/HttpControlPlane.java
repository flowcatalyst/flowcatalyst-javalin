package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/// [ControlPlane] over `java.net.http.HttpClient` (spec
/// `function-host-reconciler.md` §1.1). Every request carries a bearer token
/// from [TokenSource]; a 401 refreshes it once and retries the SAME request
/// once — a second 401 is [ControlPlaneException.Reason#UNAUTHORIZED]. The
/// client secret and the token never appear in a log field, an exception
/// message, or a `toString` (this class holds neither directly — only
/// [TokenSource], whose own `toString` is masked).
public final class HttpControlPlane implements ControlPlane {

    private static final Logger LOG = LoggerFactory.getLogger(HttpControlPlane.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;
    private final String platformUrl;
    private final TokenSource tokenSource;

    public HttpControlPlane(String platformUrl, TokenSource tokenSource) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), platformUrl, tokenSource);
    }

    /// @param client injectable so a test can point at a loopback `HttpServer`
    public HttpControlPlane(HttpClient client, String platformUrl, TokenSource tokenSource) {
        this.client = Objects.requireNonNull(client, "client");
        this.platformUrl = Objects.requireNonNull(platformUrl, "platformUrl");
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
    }

    @Override
    public Fetched desiredState(DnsLabel pool, String knownEtag) throws ControlPlaneException {
        Objects.requireNonNull(pool, "pool");
        return sendWithAuth(token -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(platformUrl + "/control/functions/desired-state?pool=" + pool.value()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .GET();
            if (knownEtag != null) {
                builder.header("If-None-Match", knownEtag);
            }
            return builder.build();
        }, response -> {
            if (response.statusCode() == 304) {
                return new Fetched.NotModified();
            }
            if (response.statusCode() != 200) {
                throw unavailable("desired-state", response.statusCode());
            }
            String etag = response.headers().firstValue("ETag").orElseThrow(
                    () -> unavailable("desired-state response carried no ETag", response.statusCode()));
            DesiredDocument document;
            try {
                document = DesiredDocument.parse(response.body());
            } catch (RuntimeException e) {
                throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE,
                        "desired-state response was not a readable document", e);
            }
            return new Fetched.Changed(etag, document);
        });
    }

    @Override
    public void heartbeat(HeartbeatReport report) throws ControlPlaneException {
        Objects.requireNonNull(report, "report");
        String body = Json.write(toWire(report));
        sendWithAuth(token -> HttpRequest.newBuilder(URI.create(platformUrl + "/control/functions/heartbeat"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), response -> {
            if (response.statusCode() != 204) {
                throw unavailable("heartbeat", response.statusCode());
            }
            return null;
        });
    }

    /// Sends `requestBuilder.apply(token)` with the cached token; on a 401,
    /// refreshes once and retries the identical request once more (spec
    /// §1.1) before handing the (possibly second) response to `handler`.
    private <T> T sendWithAuth(Function<String, HttpRequest> requestBuilder,
                                ThrowingFunction<HttpResponse<String>, T> handler) throws ControlPlaneException {
        String token = tokenSource.token();
        HttpResponse<String> response = send(requestBuilder.apply(token));
        if (response.statusCode() == 401) {
            // debug-only (CONVENTIONS §10) — never the token itself (spec §1.1, R9).
            LOG.atDebug().setMessage("control plane rejected the bearer token; refreshing and retrying once").log();
            token = tokenSource.refresh();
            response = send(requestBuilder.apply(token));
            if (response.statusCode() == 401) {
                throw new ControlPlaneException(ControlPlaneException.Reason.UNAUTHORIZED,
                        "control plane rejected the refreshed bearer token");
            }
        }
        return handler.apply(response);
    }

    private HttpResponse<String> send(HttpRequest request) throws ControlPlaneException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE,
                    "control plane request failed: " + request.method() + " " + request.uri().getPath(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "control plane request was interrupted", e);
        }
    }

    private static ControlPlaneException unavailable(String what, int status) {
        return new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, what + ": unexpected status " + status);
    }

    private static Object toWire(HeartbeatReport report) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("hostId", report.hostId());
        node.put("pool", report.pool().value());
        node.put("state", report.state().name());
        ArrayNode loaded = node.putArray("loaded");
        for (HeartbeatReport.LoadedEntry entry : report.loaded()) {
            ObjectNode e = Json.MAPPER.createObjectNode();
            e.put("address", entry.address().render());
            e.put("version", entry.version());
            e.put("state", wireState(entry.state()));
            if (entry.state() instanceof HeartbeatReport.LoadState.Failed(String error)) {
                e.put("error", error);
            }
            loaded.add(e);
        }
        return node;
    }

    private static String wireState(HeartbeatReport.LoadState state) {
        return switch (state) {
            case HeartbeatReport.LoadState.Registered ignored -> "REGISTERED";
            case HeartbeatReport.LoadState.Loaded ignored -> "LOADED";
            case HeartbeatReport.LoadState.Failed ignored -> "FAILED";
        };
    }

    @FunctionalInterface
    private interface ThrowingFunction<A, B> {
        B apply(A a) throws ControlPlaneException;
    }
}
