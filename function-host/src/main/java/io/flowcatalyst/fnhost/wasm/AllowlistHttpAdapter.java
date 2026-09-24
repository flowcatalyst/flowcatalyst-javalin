package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.function.HttpCall;
import io.flowcatalyst.function.HttpCallRefusedException;
import io.flowcatalyst.function.HttpCaller;
import io.flowcatalyst.function.HttpReply;
import io.flowcatalyst.platform.shared.json.Json;
import org.extism.sdk.chicory.http.HttpClientAdapter;
import run.endive.runtime.WasmInterruptedException;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Extism's built-in `http_request` (the PDKs' `Http.request`) over the
/// version's own [HttpCaller] — the host's
/// [io.flowcatalyst.fnhost.context.AllowlistHttpCaller] in production
/// (`docs/spec/function-wasm-runtime.md` §4). For a Wasm function this is the
/// ONLY way out: `manifest.httpAllow`, `https` except loopback, no redirects
/// and the invocation-deadline cap are therefore enforced, not advisory as for
/// a JVM function that could bundle its own client.
///
/// Every failure is a value the guest sees, never a trap that kills the
/// instance: a refused call (host not allowed, scheme, no time left) or a
/// transport failure (connection refused, timeout) answers status `0` —
/// "no HTTP response was received" — with a body `{"error": "<why>"}`. The
/// one exception is interruption: the listener interrupts the invocation's
/// thread at its deadline, and the guest must stop then, so an
/// `InterruptedException` restores the flag and aborts the guest with
/// Endive's own [WasmInterruptedException].
///
/// One per plugin instance (Extism keeps the last response's status and
/// headers here between `http_request` and `http_status_code` /
/// `http_headers`); an instance runs one call at a time, so no locking.
final class AllowlistHttpAdapter implements HttpClientAdapter {

    /// The status a guest sees when no HTTP response was received.
    static final int NO_RESPONSE = 0;

    private final HttpCaller http;
    private int lastStatus = NO_RESPONSE;
    private Map<String, List<String>> lastHeaders = Map.of();

    AllowlistHttpAdapter(HttpCaller http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public byte[] request(String method, URI url, Map<String, String> headers, byte[] requestBody) {
        lastStatus = NO_RESPONSE;
        lastHeaders = Map.of();
        Map<String, List<String>> multi = new LinkedHashMap<>();
        headers.forEach((name, value) -> multi.put(name, List.of(value)));
        HttpReply reply;
        try {
            reply = http.send(new HttpCall(method, url.toString(), multi, requestBody));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WasmInterruptedException("interrupted during an outbound HTTP call", e);
        } catch (HttpCallRefusedException e) {
            return noResponse(e.getMessage());
        } catch (HttpTimeoutException e) {
            return noResponse("outbound call timed out");
        } catch (Exception e) {
            return noResponse("outbound call failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        lastStatus = reply.status();
        lastHeaders = reply.headers();
        return reply.body();
    }

    @Override
    public int statusCode() {
        return lastStatus;
    }

    @Override
    public Map<String, List<String>> headers() {
        return lastHeaders;
    }

    private byte[] noResponse(String why) {
        var body = Json.MAPPER.createObjectNode();
        body.put("error", why);
        return Json.MAPPER.writeValueAsBytes(body);
    }
}
