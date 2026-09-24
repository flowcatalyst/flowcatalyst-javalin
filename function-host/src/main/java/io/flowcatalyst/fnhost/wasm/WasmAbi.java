package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.result.Result;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The invocation ABI between the host and a Wasm export
/// (`docs/spec/function-wasm-runtime.md` §3): a [Request] in, a reply out,
/// both UTF-8 JSON — the same bytes for any guest language.
///
/// In — [#encode]:
/// `{"address","version","invocationId","method","path","originalHost","originalPath",
///   "pathParams":{},"query":{"k":["v"]},"headers":{"k":["v"]},"bodyBase64","remoteAddress",
///   "caller":{...}}`; `caller` is `{"kind":"platform"}`, `{"kind":"anonymous"}` or
/// `{"kind":"principal","id","type","tier","clients","roles","applications","allApplications",
///   "permissions"}` (`permissions` sorted, so the bytes are deterministic).
///
/// Out — [#decode]: `{"status": 100-599, "headers": {"k": ["v"]}, "bodyBase64" | "body"}`.
/// `headers` and the body are optional (absent = none, empty); `body` is text
/// sent as UTF-8, `bodyBase64` bytes; giving both is ambiguous and refused.
/// Unknown keys are ignored. Anything else is a [Malformed] reply.
final class WasmAbi {

    private WasmAbi() {
    }

    /// What a guest answered, once it has the result shape.
    record GuestReply(int status, Map<String, List<String>> headers, byte[] body) {
        GuestReply {
            headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    /// The guest's output is not the result shape. `detail` says how, for
    /// the one WARN the host logs.
    record Malformed(String detail) {
        Malformed {
            Objects.requireNonNull(detail, "detail");
        }
    }

    static byte[] encode(Request in) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("address", in.address().render());
        root.put("version", in.version());
        root.put("invocationId", in.invocationId());
        root.put("method", in.method());
        root.put("path", in.path());
        root.put("originalHost", in.originalHost());
        root.put("originalPath", in.originalPath());
        ObjectNode pathParams = root.putObject("pathParams");
        in.pathParams().forEach(pathParams::put);
        putMulti(root.putObject("query"), in.query());
        putMulti(root.putObject("headers"), in.headers());
        root.put("bodyBase64", Base64.getEncoder().encodeToString(in.body()));
        root.put("remoteAddress", in.remoteAddress());
        putCaller(root.putObject("caller"), in.caller());
        return Json.MAPPER.writeValueAsBytes(root);
    }

    private static void putMulti(ObjectNode target, Map<String, List<String>> values) {
        values.forEach((name, list) -> {
            ArrayNode array = target.putArray(name);
            list.forEach(array::add);
        });
    }

    private static void putCaller(ObjectNode target, Caller caller) {
        switch (caller) {
            case Caller.Platform ignored -> target.put("kind", "platform");
            case Caller.Anonymous ignored -> target.put("kind", "anonymous");
            case Caller.Principal p -> {
                target.put("kind", "principal");
                target.put("id", p.id());
                target.put("type", p.type());
                target.put("tier", p.tier());
                p.clients().forEach(target.putArray("clients")::add);
                p.roles().forEach(target.putArray("roles")::add);
                p.applications().forEach(target.putArray("applications")::add);
                target.put("allApplications", p.allApplications());
                p.permissions().stream().sorted().forEach(target.putArray("permissions")::add);
            }
        }
    }

    static Result<GuestReply, Malformed> decode(byte[] output) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(output);
        } catch (JacksonException e) {
            return Result.err(new Malformed("output is not JSON"));
        }
        if (root == null || !root.isObject()) {
            return Result.err(new Malformed("output is not a JSON object"));
        }
        JsonNode status = root.path("status");
        if (!status.isInt() || status.intValue() < 100 || status.intValue() > 599) {
            return Result.err(new Malformed("status must be an integer 100-599"));
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        JsonNode headersNode = root.path("headers");
        if (!headersNode.isMissingNode() && !headersNode.isNull()) {
            if (!headersNode.isObject()) {
                return Result.err(new Malformed("headers must be an object of string arrays"));
            }
            for (Map.Entry<String, JsonNode> header : headersNode.properties()) {
                if (!header.getValue().isArray()) {
                    return Result.err(new Malformed("header '" + header.getKey() + "' must be an array of strings"));
                }
                List<String> values = new ArrayList<>();
                for (JsonNode value : header.getValue()) {
                    if (!value.isString()) {
                        return Result.err(new Malformed("header '" + header.getKey() + "' must be an array of strings"));
                    }
                    values.add(value.asString());
                }
                headers.put(header.getKey(), List.copyOf(values));
            }
        }

        JsonNode text = root.path("body");
        JsonNode base64 = root.path("bodyBase64");
        boolean hasText = !text.isMissingNode() && !text.isNull();
        boolean hasBase64 = !base64.isMissingNode() && !base64.isNull();
        byte[] body;
        if (hasText && hasBase64) {
            return Result.err(new Malformed("give either body or bodyBase64, not both"));
        } else if (hasText) {
            if (!text.isString()) {
                return Result.err(new Malformed("body must be a string"));
            }
            body = text.asString().getBytes(StandardCharsets.UTF_8);
        } else if (hasBase64) {
            if (!base64.isString()) {
                return Result.err(new Malformed("bodyBase64 must be a string"));
            }
            try {
                body = Base64.getDecoder().decode(base64.asString());
            } catch (IllegalArgumentException e) {
                return Result.err(new Malformed("bodyBase64 is not base64"));
            }
        } else {
            body = new byte[0];
        }
        return Result.ok(new GuestReply(status.intValue(), headers, body));
    }
}
