package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.function.EmitResult;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.shared.Failures;
import io.flowcatalyst.platform.shared.LogThrottle;
import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
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
    private static final LogThrottle EMIT_FAILURE_LOG = new LogThrottle(Duration.ofSeconds(10));

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

    /// `POST /control/functions/events` (spec `function-context.md` §3).
    /// Same 401-refresh-once handling as [#desiredState]/[#heartbeat] (a
    /// stale HOST bearer token, orthogonal to the platform's own business
    /// outcome for the emit itself) — but every OTHER non-2xx, and any
    /// transport failure, is an [EmitResult.Refused] rather than a
    /// [ControlPlaneException], per this method's own contract.
    @Override
    public EmitResult emit(ControlPlane.EmitRequest request) {
        Objects.requireNonNull(request, "request");
        String body = Json.write(toWire(request));
        String token;
        try {
            token = tokenSource.token();
        } catch (ControlPlaneException e) {
            return unavailable("minting a control-plane token failed", e);
        }
        Sent sent = sendEmit(token, body);
        if (sent instanceof Sent.Answered(HttpResponse<String> first) && first.statusCode() == 401) {
            LOG.atDebug().setMessage("control plane rejected the bearer token on emit; refreshing and retrying once").log();
            try {
                token = tokenSource.refresh();
            } catch (ControlPlaneException e) {
                return unavailable("refreshing a control-plane token failed", e);
            }
            sent = sendEmit(token, body);
        }
        return switch (sent) {
            case Sent.Failed(EmitResult.Refused refused) -> refused;
            case Sent.Answered(HttpResponse<String> response) when response.statusCode() / 100 != 2 -> errorFrom(response);
            case Sent.Answered(HttpResponse<String> response) -> emittedFrom(response);
        };
    }

    /// What one POST came to: an HTTP answer, or a transport failure already
    /// turned into its refusal.
    private sealed interface Sent {
        record Answered(HttpResponse<String> response) implements Sent {
        }

        record Failed(EmitResult.Refused refused) implements Sent {
        }
    }

    private Sent sendEmit(String token, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl + "/control/functions/events"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return new Sent.Answered(client.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (IOException e) {
            return new Sent.Failed(unavailable("control plane request failed: POST /control/functions/events", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Sent.Failed(new EmitResult.Refused("UNAVAILABLE", 503, "control plane request was interrupted"));
        }
    }

    /// Spec §3: a 2xx body is the ingest routes' `{results: [{id, status}]}`,
    /// one item per event, and this host always sends a batch of one. An
    /// unreadable body still means the platform accepted the event, so the id
    /// falls back to `""` rather than turning an accepted emit into a refusal
    /// the function might retry into a duplicate.
    private static EmitResult emittedFrom(HttpResponse<String> response) {
        String id = "";
        try {
            id = Json.MAPPER.readTree(response.body()).path("results").path(0).path("id").asString("");
        } catch (RuntimeException ignored) {
            // Not JSON — accepted all the same.
        }
        return new EmitResult.Emitted(id);
    }

    /// The platform's own `{error, message, details}` envelope (`HttpError`)
    /// read back into an [EmitResult.Refused] naming its code and this
    /// response's status — an unreadable/absent body still carries a real
    /// status, so the code falls back to `"UNKNOWN"` rather than losing it.
    private static EmitResult.Refused errorFrom(HttpResponse<String> response) {
        String code = "UNKNOWN";
        String message = null;
        try {
            JsonNode body = Json.MAPPER.readTree(response.body());
            String fromBody = body.path("error").asString(null);
            if (fromBody != null && !fromBody.isBlank()) {
                code = fromBody;
            }
            message = body.path("message").asString(null);
        } catch (RuntimeException ignored) {
            // Body was not readable JSON — fall through with the status alone.
        }
        // The platform's own reason (which check refused the emit) rides in the message: the
        // function author, and the log line the function may write, see why.
        String prefix = "emit refused: " + code + " (" + response.statusCode() + ")";
        return new EmitResult.Refused(code, response.statusCode(),
                message == null || message.isBlank() ? prefix : prefix + ": " + message);
    }

    /// A transport or token failure: the function gets the `UNAVAILABLE` code it
    /// branches on, and the host operator gets the cause — [EmitResult.Refused]
    /// (function-api, a published contract) carries none, so it is logged here,
    /// throttled (a platform outage fails every emit).
    private static EmitResult.Refused unavailable(String what, Exception cause) {
        EMIT_FAILURE_LOG.admit().ifPresent(suppressed -> LOG.atWarn()
                .setMessage("a function's event emit could not reach the platform")
                .addKeyValue("what", what)
                .addKeyValue("suppressed_since_last", suppressed)
                .setCause(cause)
                .log());
        return new EmitResult.Refused("UNAVAILABLE", 503, what + ": " + Failures.describe(cause));
    }

    private static Object toWire(ControlPlane.EmitRequest request) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("hostId", request.hostId());
        node.put("address", request.address().render());
        node.put("version", request.version());
        ArrayNode events = node.putArray("events");
        for (ControlPlane.EmitItem item : request.events()) {
            ObjectNode e = Json.MAPPER.createObjectNode();
            e.put("type", item.type());
            if (item.subject() != null) e.put("subject", item.subject());
            e.put("dedupId", item.dedupId());
            e.set("data", Json.MAPPER.readTree(item.data()));
            if (item.correlationId() != null) e.put("correlationId", item.correlationId());
            if (item.causationId() != null) e.put("causationId", item.causationId());
            if (item.messageGroup() != null) e.put("messageGroup", item.messageGroup());
            events.add(e);
        }
        return node;
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
