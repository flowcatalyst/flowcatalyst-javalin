package io.flowcatalyst.fnhost.http;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// The status → `outcome` label table (spec `function-host-process.md` §2).
/// Every boundary is a row: a mutant that moved 500 into `client_error`
/// survived the wiring tests, which only ever used one status per outcome.
class OutcomeForTest {

    @ParameterizedTest(name = "[{0}] {1} -> {2}")
    @CsvSource({
            "ok: lowest 2xx,            200, ok",
            "ok: highest 2xx,           299, ok",
            "ok: a redirect is not an error, 302, ok",
            "ok: highest below 400,     399, ok",
            "client_error: lowest,      400, client_error",
            "client_error: 428 next to retry, 428, client_error",
            "retry: exactly 429,        429, retry",
            "client_error: 430 next to retry, 430, client_error",
            "client_error: highest,     499, client_error",
            "error: lowest 5xx,         500, error",
            "error: 503,                503, error",
            "error: highest,            599, error",
    })
    void statusMapsToOutcome(String rule, int status, String outcome) {
        assertThat(FnHttpServer.outcomeFor(status)).as(rule).isEqualTo(outcome);
    }
}
