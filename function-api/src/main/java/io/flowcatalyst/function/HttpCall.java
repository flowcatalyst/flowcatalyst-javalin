package io.flowcatalyst.function;

import java.time.Duration;
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
/// @param timeout this call's own timeout, or `null` to use the host's
///                 default (`docs/spec/function-context.md` §2: the actual
///                 request timeout is `min(timeout, time left before the
///                 invocation deadline)`); must be positive when given
public record HttpCall(String method, String url, Map<String, List<String>> headers, byte[] body, Duration timeout) {

    public HttpCall {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");
        headers = Copies.multiMap(headers);
        body = Copies.bytes(body);
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
    }

    /// No per-call timeout — the host's own default applies.
    public HttpCall(String method, String url, Map<String, List<String>> headers, byte[] body) {
        this(method, url, headers, body, null);
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
                && Arrays.equals(body, other.body)
                && Objects.equals(timeout, other.timeout);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(method, url, headers, timeout);
        return 31 * result + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "HttpCall[method=" + method + ", url=" + url + ", headers=" + headers
                + ", body.length=" + (body == null ? 0 : body.length) + ", timeout=" + timeout + "]";
    }
}
