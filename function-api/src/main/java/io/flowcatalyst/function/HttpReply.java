package io.flowcatalyst.function;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The response to an [HttpCall]. `headers` is deep-copied and
/// unmodifiable; `body` is cloned in the constructor and again by
/// [#body()].
///
/// @param status  the response status code
/// @param headers response headers
/// @param body    the response body
public record HttpReply(int status, Map<String, List<String>> headers, byte[] body) {

    public HttpReply {
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
        if (!(o instanceof HttpReply other)) return false;
        return status == other.status && headers.equals(other.headers) && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, headers, Arrays.hashCode(body));
    }

    @Override
    public String toString() {
        return "HttpReply[status=" + status + ", headers=" + headers
                + ", body.length=" + (body == null ? 0 : body.length) + "]";
    }
}
