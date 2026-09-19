package io.flowcatalyst.function;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The one shape every invocation takes (`docs/spec/function-invocation.md`
/// §1, §7): "a function is an HTTP application". There is no separate event
/// or schedule invocation any more — a subscription delivery, a direct
/// dispatch job, a scheduled firing, a browser call and a platform API
/// client all arrive here, distinguished only by [#caller] and by parsing
/// [#body] with [Webhook#event] / [Webhook#schedule] when the endpoint's
/// `auth` is `webhook`.
///
/// Every map is deep-copied and unmodifiable; `body` is cloned in the
/// constructor and again by [#body()]. `headers` keeps the original key
/// spelling — look a header up case-insensitively with [#header(String)] /
/// [#headers(String)] rather than indexing the map directly.
///
/// @param address       the function this call targets
/// @param version       the loaded version handling this call
/// @param invocationId  the host-assigned id for this one call, unique per attempt
/// @param method        the HTTP method
/// @param path          the request path with the `/functions/{address}[:{version}]`
///                      prefix stripped, or the public route's prefix stripped (spec §2)
/// @param originalHost  the `Host` the call actually arrived on, `null` when not applicable
/// @param originalPath  the path before any prefix was stripped, `null` when not applicable
/// @param pathParams    path parameters bound by the matched endpoint pattern
/// @param query         query parameters, possibly multi-valued
/// @param headers       request headers, in original spelling
/// @param body          the request body
/// @param remoteAddress the caller's address
/// @param caller        who the host believes this call came from (spec §7, [Caller])
public record Request(
        FunctionAddress address,
        int version,
        String invocationId,
        String method,
        String path,
        String originalHost,
        String originalPath,
        Map<String, String> pathParams,
        Map<String, List<String>> query,
        Map<String, List<String>> headers,
        byte[] body,
        String remoteAddress,
        Caller caller) {

    public Request {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(caller, "caller");
        pathParams = Copies.stringMap(pathParams);
        query = Copies.multiMap(query);
        headers = Copies.multiMap(headers);
        body = Copies.bytes(body);
    }

    @Override
    public byte[] body() {
        return Copies.bytes(body);
    }

    /// The first value of the header named `name`, matched case-insensitively.
    public Optional<String> header(String name) {
        return headers(name).stream().findFirst();
    }

    /// Every value of the header named `name`, matched case-insensitively —
    /// values from every matching key, in header order.
    public List<String> headers(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Request other)) return false;
        return version == other.version
                && Objects.equals(address, other.address)
                && Objects.equals(invocationId, other.invocationId)
                && Objects.equals(method, other.method)
                && Objects.equals(path, other.path)
                && Objects.equals(originalHost, other.originalHost)
                && Objects.equals(originalPath, other.originalPath)
                && Objects.equals(pathParams, other.pathParams)
                && Objects.equals(query, other.query)
                && Objects.equals(headers, other.headers)
                && Arrays.equals(body, other.body)
                && Objects.equals(remoteAddress, other.remoteAddress)
                && Objects.equals(caller, other.caller);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(address, version, invocationId, method, path, originalHost, originalPath,
                pathParams, query, headers, remoteAddress, caller);
        return 31 * result + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "Request[address=" + address + ", version=" + version + ", invocationId=" + invocationId
                + ", method=" + method + ", path=" + path + ", originalHost=" + originalHost
                + ", originalPath=" + originalPath + ", pathParams=" + pathParams + ", query=" + query
                + ", headers=" + headers + ", body.length=" + (body == null ? 0 : body.length)
                + ", remoteAddress=" + remoteAddress + ", caller=" + caller + "]";
    }
}
