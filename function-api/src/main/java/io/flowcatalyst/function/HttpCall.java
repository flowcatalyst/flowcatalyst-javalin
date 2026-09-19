package io.flowcatalyst.function;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// An outbound HTTP request made through [HttpCaller#send]. `headers` is
/// deep-copied and unmodifiable; `body` is cloned in the constructor and
/// again by [#body()].
///
/// @param method  the HTTP method
/// @param url     the absolute target URL
/// @param headers request headers
/// @param body    the request body
public record HttpCall(String method, String url, Map<String, List<String>> headers, byte[] body) {

    public HttpCall {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");
        headers = Copies.multiMap(headers);
        body = Copies.bytes(body);
    }

    @Override
    public byte[] body() {
        return Copies.bytes(body);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HttpCall other)) return false;
        return Objects.equals(method, other.method)
                && Objects.equals(url, other.url)
                && Objects.equals(headers, other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(method, url, headers);
        return 31 * result + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "HttpCall[method=" + method + ", url=" + url + ", headers=" + headers
                + ", body.length=" + (body == null ? 0 : body.length) + "]";
    }
}
