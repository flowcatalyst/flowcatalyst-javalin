package io.flowcatalyst.fnhost.wasm;

import io.flowcatalyst.fnhost.wasm.DbFailure.SqlClass;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

/// The `fc_db_*` error codes (`docs/spec/function-wasm-db.md`: "`code` from the
/// SQLSTATE class") — a pinned table (`CONVENTIONS.md` §8), grouped by rule.
class DbFailureTest {

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @CsvSource({
            // rule,                          sqlstate, code
            "integrity constraint (23),       23505,    DB_CONSTRAINT",
            "integrity constraint (23),       23503,    DB_CONSTRAINT",
            "integrity constraint (23),       23502,    DB_CONSTRAINT",
            "syntax / access rule (42),       42601,    DB_SYNTAX",
            "syntax / access rule (42),       42P01,    DB_SYNTAX",
            "syntax / access rule (42),       42501,    DB_SYNTAX",
            "query canceled (57014),          57014,    DB_TIMEOUT",
            "connection exception (08),       08006,    DB_UNAVAILABLE",
            "connection exception (08),       08001,    DB_UNAVAILABLE",
            "operator intervention (57),      57P01,    DB_UNAVAILABLE",
            "operator intervention (57),      57P03,    DB_UNAVAILABLE",
            "insufficient resources (53),     53300,    DB_UNAVAILABLE",
            "everything else,                 22012,    DB_ERROR",
            "everything else,                 40001,    DB_ERROR",
            "everything else,                 40P01,    DB_ERROR",
            "everything else,                 25P02,    DB_ERROR",
            "near-miss: 57 but not 014,       57015,    DB_UNAVAILABLE",
            "near-miss: 2 not 23,             22023,    DB_ERROR",
    })
    void theCodeIsTheSqlStatesClass(String rule, String state, String code) {
        assertThat(SqlClass.of(new SQLException("m", state)).name()).as(rule).isEqualTo(code);
    }

    @Test
    void withNoSqlStateTheExceptionKindDecides() {
        assertThat(SqlClass.of(new SQLTransientConnectionException("pool timed out"))).isEqualTo(SqlClass.DB_UNAVAILABLE);
        assertThat(SqlClass.of(new SQLTimeoutException("slow"))).isEqualTo(SqlClass.DB_TIMEOUT);
        assertThat(SqlClass.of(new SQLException("?"))).isEqualTo(SqlClass.DB_ERROR);
        assertThat(SqlClass.of(new SQLException("?", "0"))).as("a one-character state is no class").isEqualTo(SqlClass.DB_ERROR);
    }

    @Test
    void theGuestSeesTheCodeAndTheDriversMessage() {
        JsonNode answer = Json.MAPPER.readTree(HostFunctions.errorAnswer(
                DbFailure.Sql.of(new SQLException("ERROR: duplicate key value", "23505"))));

        assertThat(answer.path("error").path("code").asString()).isEqualTo("DB_CONSTRAINT");
        assertThat(answer.path("error").path("message").asString()).isEqualTo("ERROR: duplicate key value");
        assertThat(answer.size()).as("an error answer is only the error").isEqualTo(1);
    }
}
