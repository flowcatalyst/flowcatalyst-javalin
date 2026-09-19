package io.flowcatalyst.function;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The invocation was an [HttpInvocation], answered directly. Built through
/// [Result#http]. `headers` is deep-copied and unmodifiable; `body` is
/// cloned in the constructor and again by [#body()] — mutating the array
/// passed in, or the one read out, never changes this record.
///
/// @param status  the HTTP status code; 100-599
/// @param headers response headers, in insertion order
/// @param body    the response body
public record HttpResponse(int status, Map<String, List<String>> headers, byte[] body) implements Result {

    public HttpResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be 100-599, was " + status);
        }
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
        if (!(o instanceof HttpResponse other)) return false;
        return status == other.status && headers.equals(other.headers) && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, headers, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "HttpResponse[status=" + status + ", headers=" + headers
                + ", body.length=" + (body == null ? 0 : body.length) + "]";
    }
}
