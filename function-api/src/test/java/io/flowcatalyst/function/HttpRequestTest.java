package io.flowcatalyst.function;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `docs/spec/function-host-core.md` §1, L9: `HttpRequest`'s
/// case-insensitive header lookup keeps the map's own key spelling, and
/// every array/collection component is a defensive copy both ways.
class HttpRequestTest {

    @Test
    void bodyIsIndependentOfTheArrayPassedInAndReadOut() {
        byte[] body = {1, 2, 3};
        HttpRequest request = request(Map.of(), body);
        body[0] = 99;
        assertThat(request.body()).containsExactly(1, 2, 3);

        byte[] read = request.body();
        read[0] = 42;
        assertThat(request.body()).containsExactly(1, 2, 3);
    }

    @Test
    void pathParamsQueryAndHeadersAreUnmodifiable() {
        HttpRequest request = request(Map.of("Content-Type", List.of("text/plain")), new byte[0]);
        assertThatThrownBy(() -> request.pathParams().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.query().put("x", List.of("y"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.headers().put("x", List.of("y"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.headers().get("Content-Type").add("z"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void headersAreIndependentOfTheMapPassedIn() {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put("X-Trace", new ArrayList<>(List.of("abc")));
        HttpRequest request = request(headers, new byte[0]);
        headers.get("X-Trace").add("mutated");
        assertThat(request.headers().get("X-Trace")).containsExactly("abc");
    }

    @Test
    void headerLookupIsCaseInsensitiveButTheMapKeepsOriginalSpelling() {
        HttpRequest request = request(Map.of("X-Request-Id", List.of("abc-123")), new byte[0]);

        assertThat(request.header("x-request-id")).contains("abc-123");
        assertThat(request.header("X-REQUEST-ID")).contains("abc-123");
        assertThat(request.headers("x-request-id")).containsExactly("abc-123");
        assertThat(request.header("x-missing")).isEmpty();

        // The map itself was not lower-cased or otherwise normalised.
        assertThat(request.headers()).containsOnlyKeys("X-Request-Id");
    }

    @Test
    void headerLookupReturnsEmptyOptionalNotNull() {
        HttpRequest request = request(Map.of(), new byte[0]);
        Optional<String> value = request.header("Absent");
        assertThat(value).isEmpty();
    }

    private static HttpRequest request(Map<String, List<String>> headers, byte[] body) {
        return new HttpRequest("GET", "/x", "/x", Map.of(), Map.of(), headers, body, "127.0.0.1", null);
    }
}
