package io.flowcatalyst.function;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// An HTTP gateway call, carried by an [HttpInvocation]. Every map is
/// deep-copied and unmodifiable; `body` is cloned in the constructor and
/// again by [#body()]. `headers` keeps the original key spelling — look a
/// header up case-insensitively with [#header(String)] / [#headers(String)]
/// rather than indexing the map directly.
///
/// @param method      the HTTP method
/// @param path        the raw request path
/// @param route       the matched route pattern
/// @param pathParams  path parameters bound by the route
/// @param query       query parameters, possibly multi-valued
/// @param headers     request headers, in original spelling
/// @param body        the request body
/// @param remoteAddress the caller's address
/// @param principal   the authenticated caller, or `null` when the route's
///                    auth mode is `none`
public record HttpRequest(
        String method,
        String path,
        String route,
        Map<String, String> pathParams,
        Map<String, List<String>> query,
        Map<String, List<String>> headers,
        byte[] body,
        String remoteAddress,
        Principal principal) {

    public HttpRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(route, "route");
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
        if (!(o instanceof HttpRequest other)) return false;
        return Objects.equals(method, other.method)
                && Objects.equals(path, other.path)
                && Objects.equals(route, other.route)
                && Objects.equals(pathParams, other.pathParams)
                && Objects.equals(query, other.query)
                && Objects.equals(headers, other.headers)
                && Arrays.equals(body, other.body)
                && Objects.equals(remoteAddress, other.remoteAddress)
                && Objects.equals(principal, other.principal);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(method, path, route, pathParams, query, headers, remoteAddress, principal);
        return 31 * result + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "HttpRequest[method=" + method + ", path=" + path + ", route=" + route
                + ", pathParams=" + pathParams + ", query=" + query + ", headers=" + headers
                + ", body.length=" + (body == null ? 0 : body.length)
                + ", remoteAddress=" + remoteAddress + ", principal=" + principal + "]";
    }
}
