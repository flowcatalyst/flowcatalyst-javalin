package io.flowcatalyst.function;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `docs/spec/function-host-core.md` §1, L9: each `Result` factory's
/// validation, and — for `HttpResponse` — that a `byte[]` and the `headers`
/// map read back out are independent of the ones passed in.
class ResultTest {

    @Test
    void ackIsAlwaysTheSameKindOfResult() {
        assertThat(Result.ack()).isInstanceOf(Ack.class);
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
    void retryAcceptsZeroAndPositiveDuration() {
        assertThat(((Retry) Result.retry(Duration.ZERO)).after()).isEqualTo(Duration.ZERO);
        assertThat(((Retry) Result.retry(Duration.ofMinutes(5))).after()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void failRejectsBlankReason() {
        assertThatThrownBy(() -> Result.fail(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Result.fail("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Result.fail("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failKeepsTheReason() {
        assertThat(((Fail) Result.fail("bad input")).reason()).isEqualTo("bad input");
    }

    @Test
    void httpResponseRejectsStatusOutsideRange() {
        assertThatThrownBy(() -> Result.http(99, Map.of(), new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Result.http(600, Map.of(), new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpResponseAcceptsBoundaryStatuses() {
        assertThat(((HttpResponse) Result.http(100, Map.of(), new byte[0])).status()).isEqualTo(100);
        assertThat(((HttpResponse) Result.http(599, Map.of(), new byte[0])).status()).isEqualTo(599);
    }

    @Test
    void httpResponseBodyIsIndependentOfTheArrayPassedIn() {
        byte[] body = {1, 2, 3};
        HttpResponse response = (HttpResponse) Result.http(200, Map.of(), body);
        body[0] = 99;
        assertThat(response.body()).containsExactly(1, 2, 3);
    }

    @Test
    void httpResponseBodyIsIndependentOfTheArrayReadOut() {
        HttpResponse response = (HttpResponse) Result.http(200, Map.of(), new byte[] {1, 2, 3});
        byte[] read = response.body();
        read[0] = 99;
        assertThat(response.body()).containsExactly(1, 2, 3);
    }

    @Test
    void httpResponseHeadersAreUnmodifiable() {
        HttpResponse response = (HttpResponse) Result.http(
                200, Map.of("Content-Type", List.of("text/plain")), new byte[0]);
        assertThatThrownBy(() -> response.headers().put("X", List.of("y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.headers().get("Content-Type").add("y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void httpResponseHeadersAreIndependentOfTheMapPassedIn() {
        Map<String, List<String>> headers = new java.util.HashMap<>();
        headers.put("X", new java.util.ArrayList<>(List.of("1")));
        HttpResponse response = (HttpResponse) Result.http(200, headers, new byte[0]);
        headers.get("X").add("2");
        assertThat(response.headers().get("X")).containsExactly("1");
    }

    @Test
    void httpResponseEqualityIsByContent() {
        HttpResponse a = (HttpResponse) Result.http(200, Map.of("X", List.of("1")), new byte[] {1, 2});
        HttpResponse b = (HttpResponse) Result.http(200, Map.of("X", List.of("1")), new byte[] {1, 2});
        HttpResponse c = (HttpResponse) Result.http(200, Map.of("X", List.of("1")), new byte[] {1, 3});
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void httpResponseToStringDoesNotDumpBodyBytesOnlyLength() {
        HttpResponse response = (HttpResponse) Result.http(200, Map.of(), new byte[] {1, 2, 3});
        assertThat(response.toString()).contains("body.length=3").doesNotContain("[1, 2, 3]");
    }
}
