package io.flowcatalyst.function;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `docs/spec/function-invocation.md` §7, `Result`'s class doc table: each
/// factory's own local behaviour (status code, headers, body it produces).
/// Whether the platform actually honours what each status/header means is
/// a `SubscriberDelivery`/`JobDispatcher` behaviour, out of this module's
/// reach — pinned instead by reading those classes, cited in `Result`'s doc.
class ResultTest {

    @Test
    void ackIsStatus200WithAnEmptyBody() {
        Result result = Result.ack();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).isEmpty();
    }

    @Test
    void retryRejectsNullDuration() {
        assertThatThrownBy(() -> Result.retry(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryRejectsNegativeDuration() {
        assertThatThrownBy(() -> Result.retry(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryIsStatus429WithARetryAfterHeader() {
        Result result = Result.retry(Duration.ofSeconds(45));
        assertThat(result.status()).isEqualTo(429);
        assertThat(result.headers().get("Retry-After")).containsExactly("45");
    }

    @Test
    void retryAcceptsZeroDurationAsAnImmediateRetryRequest() {
        Result result = Result.retry(Duration.ZERO);
        assertThat(result.headers().get("Retry-After")).containsExactly("0");
    }

    @Test
    void retryRoundsASubSecondRemainderUpNeverDown() {
        // 1500ms must not be reported as "1" (Retry-After is whole seconds; rounding
        // down would ask the platform to wait LESS than the caller actually requested).
        assertThat(Result.retry(Duration.ofMillis(1500)).headers().get("Retry-After")).containsExactly("2");
        // An exact whole-second duration is not bumped up by one.
        assertThat(Result.retry(Duration.ofSeconds(2)).headers().get("Retry-After")).containsExactly("2");
        // A tiny sub-second remainder still rounds up to a full second, not zero.
        assertThat(Result.retry(Duration.ofMillis(1)).headers().get("Retry-After")).containsExactly("1");
    }

    @Test
    void failRejectsBlankReason() {
        assertThatThrownBy(() -> Result.fail(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Result.fail("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Result.fail("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failIsStatus500WithAJsonErrorBody() {
        Result result = Result.fail("bad input");
        assertThat(result.status()).isEqualTo(500);
        assertThat(result.headers().get("Content-Type")).containsExactly("application/json");
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"bad input\"}");
    }

    @Test
    void failEscapesQuotesAndBackslashesInTheReason() {
        Result result = Result.fail("said \"hi\" then \\ broke");
        String body = new String(result.body(), StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("{\"error\":\"said \\\"hi\\\" then \\\\ broke\"}");
    }

    @Test
    void failEscapesControlCharacters() {
        Result result = Result.fail("line1\nline2\ttabbed");
        String body = new String(result.body(), StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("{\"error\":\"line1\\nline2\\ttabbed\"}");
    }

    @Test
    void failEscapesNonAsciiAsUnicodeEscapes() {
        Result result = Result.fail("caf\u00e9 \u2603");
        String body = new String(result.body(), StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("{\"error\":\"caf\\u00e9 \\u2603\"}");
    }

    @Test
    void httpResponseRejectsStatusOutsideRange() {
        assertThatThrownBy(() -> Result.http(99, Map.of(), new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Result.http(600, Map.of(), new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpResponseAcceptsBoundaryStatuses() {
        assertThat(Result.http(100, Map.of(), new byte[0]).status()).isEqualTo(100);
        assertThat(Result.http(599, Map.of(), new byte[0]).status()).isEqualTo(599);
    }

    @Test
    void httpResponseBodyIsIndependentOfTheArrayPassedIn() {
        byte[] body = {1, 2, 3};
        Result response = Result.http(200, Map.of(), body);
        body[0] = 99;
        assertThat(response.body()).containsExactly(1, 2, 3);
    }

    @Test
    void httpResponseBodyIsIndependentOfTheArrayReadOut() {
        Result response = Result.http(200, Map.of(), new byte[] {1, 2, 3});
        byte[] read = response.body();
        read[0] = 99;
        assertThat(response.body()).containsExactly(1, 2, 3);
    }

    @Test
    void httpResponseHeadersAreUnmodifiable() {
        Result response = Result.http(200, Map.of("Content-Type", List.of("text/plain")), new byte[0]);
        assertThatThrownBy(() -> response.headers().put("X", List.of("y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.headers().get("Content-Type").add("y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void httpResponseHeadersAreIndependentOfTheMapPassedIn() {
        Map<String, List<String>> headers = new java.util.HashMap<>();
        headers.put("X", new java.util.ArrayList<>(List.of("1")));
        Result response = Result.http(200, headers, new byte[0]);
        headers.get("X").add("2");
        assertThat(response.headers().get("X")).containsExactly("1");
    }

    @Test
    void jsonSetsContentTypeAndBody() {
        Result result = Result.json(201, "{\"a\":1}");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.headers().get("Content-Type")).containsExactly("application/json");
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    void jsonRejectsNullBody() {
        assertThatThrownBy(() -> Result.json(200, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityIsByContent() {
        Result a = Result.http(200, Map.of("X", List.of("1")), new byte[] {1, 2});
        Result b = Result.http(200, Map.of("X", List.of("1")), new byte[] {1, 2});
        Result c = Result.http(200, Map.of("X", List.of("1")), new byte[] {1, 3});
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toStringDoesNotDumpBodyBytesOnlyLength() {
        Result result = Result.http(200, Map.of(), new byte[] {1, 2, 3});
        assertThat(result.toString()).contains("body.length=3").doesNotContain("[1, 2, 3]");
    }
}
