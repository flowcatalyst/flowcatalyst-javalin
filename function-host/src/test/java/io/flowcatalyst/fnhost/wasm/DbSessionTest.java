package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [DbSession] and [HostFunctions#dbInput] without a guest in between — the
/// input edges a guest could send that the Rust fixture cannot (its serde_json
/// reads every number as an f64), over this class's own test database.
class DbSessionTest {

    @Test
    void aDecimalParameterReachesTheServerWithEveryDigitTheGuestSent() {
        try (DbSession session = new DbSession(Map.of("main", TestPg.dataSource()), Clock.systemUTC())) {
            JsonNode answer = answer(session.query(input(
                    "{\"db\":\"main\",\"sql\":\"SELECT ?::numeric AS v\",\"params\":[1.00000000000000000001]}")));

            assertThat(answer.path("rows").get(0).path("v").asString())
                    .as("mutant: read the input's decimals as doubles — the server would get 1.0")
                    .isEqualTo("1.00000000000000000001");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "not JSON                  | {nope",
            "not an object             | [1]",
            "db missing                | {\"sql\":\"SELECT 1\"}",
            "db not a string           | {\"db\":1,\"sql\":\"SELECT 1\"}",
            "sql missing               | {\"db\":\"main\"}",
            "sql blank                 | {\"db\":\"main\",\"sql\":\"  \"}",
            "params not an array       | {\"db\":\"main\",\"sql\":\"SELECT 1\",\"params\":{}}",
            "an object parameter       | {\"db\":\"main\",\"sql\":\"SELECT ?\",\"params\":[{\"a\":1}]}",
            "an array parameter        | {\"db\":\"main\",\"sql\":\"SELECT ?\",\"params\":[[1]]}",
            "tx not a string           | {\"db\":\"main\",\"sql\":\"SELECT 1\",\"tx\":7}",
    })
    void aMalformedInputIsDbBadRequest(String rule, String json) {
        try (DbSession session = new DbSession(Map.of("main", TestPg.dataSource()), Clock.systemUTC())) {
            Result<byte[], DbFailure> outcome = HostFunctions.dbInput(json.getBytes(StandardCharsets.UTF_8))
                    .flatMap(session::query);

            assertThat(outcome).as(rule).isInstanceOfSatisfying(Result.Err.class,
                    err -> assertThat(((DbFailure) err.error()).code()).isEqualTo("DB_BAD_REQUEST"));
        }
    }

    @Test
    void closeRollsBackAndForgetsEveryOpenTransactionAndIsIdempotent() {
        DbSession session = new DbSession(Map.of("main", TestPg.dataSource()), Clock.systemUTC());
        String tx = ((Result.Ok<String, DbFailure>) session.begin(input("{\"db\":\"main\"}"))).value();
        assertThat(session.openTransactions()).isEqualTo(1);

        session.close();
        session.close();

        assertThat(session.openTransactions()).isZero();
        assertThat(session.commit(input("{\"tx\":\"" + tx + "\"}")))
                .isInstanceOfSatisfying(Result.Err.class,
                        err -> assertThat(((DbFailure) err.error()).code()).isEqualTo("DB_TX_UNKNOWN"));
    }

    private static JsonNode input(String json) {
        return ((Result.Ok<JsonNode, DbFailure>) HostFunctions.dbInput(json.getBytes(StandardCharsets.UTF_8)))
                .value();
    }

    private static JsonNode answer(Result<byte[], DbFailure> outcome) {
        assertThat(outcome).isInstanceOf(Result.Ok.class);
        return Json.MAPPER.readTree(((Result.Ok<byte[], DbFailure>) outcome).value());
    }
}
