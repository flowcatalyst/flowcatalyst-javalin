package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.platform.shared.json.Json;
import org.extism.sdk.chicory.http.ExtismJsonException;
import org.extism.sdk.chicory.http.HttpJsonCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Extism's HTTP request/headers JSON on Jackson 3 (`docs/spec/function-wasm-runtime.md`
/// §4) — the codec the ported SDK's built-in `http_request` / `http_headers`
/// use, replacing upstream's Jackson 2 one.
///
/// Request (what an Extism PDK's `Http.request` writes):
/// `{"url": "...", "method": "GET"|null, "headers": {"name": "value"}}` — an
/// absent method is `GET` (the PDKs' own default). Response headers go back as
/// `{"name": "v1, v2"}`: the PDKs read a flat string map, so a repeated
/// header's values are joined with `", "` (RFC 9110 §5.3) rather than
/// silently keeping only one, as upstream's codec did.
final class ExtismHttpJson implements HttpJsonCodec {

    @Override
    public RequestMetadata decodeMetadata(byte[] data) {
        JsonNode request;
        try {
            request = Json.MAPPER.readTree(data);
        } catch (JacksonException e) {
            throw new ExtismJsonException(e);
        }
        String url = request.path("url").asString("");
        String method = request.path("method").isString() ? request.path("method").asString() : "GET";
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> header : request.path("headers").properties()) {
            if (header.getValue().isString()) {
                headers.put(header.getKey(), header.getValue().asString());
            }
        }
        URI uri = URI.create(url);
        Map<String, String> frozen = Map.copyOf(headers);
        return new RequestMetadata() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public URI uri() {
                return uri;
            }

            @Override
            public Map<String, String> headers() {
                return frozen;
            }
        };
    }

    @Override
    public byte[] encodeHeaders(Map<String, List<String>> headers) {
        ObjectNode out = Json.MAPPER.createObjectNode();
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            out.put(header.getKey(), String.join(", ", header.getValue()));
        }
        return Json.MAPPER.writeValueAsBytes(out);
    }
}
