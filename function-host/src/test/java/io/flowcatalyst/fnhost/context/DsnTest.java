package io.flowcatalyst.fnhost.context;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec `function-context.md` §2: the DSN's password is in no `toString`.
/// A password can arrive three ways — URL userinfo, a `password=` query
/// parameter on the short form, and a `password=` parameter on the JDBC
/// form — and each must be masked, not only the first.
class DsnTest {

    private static final String MARKER = "dsn-marker-pw-7e41";

    @ParameterizedTest(name = "[{0}]")
    @CsvSource({
            "userinfo on the short form,        postgresql://u:dsn-marker-pw-7e41@localhost:5432/db",
            "userinfo on postgres://,           postgres://u:dsn-marker-pw-7e41@localhost/db?sslmode=disable",
            "password parameter on the short form, postgresql://localhost:5432/db?user=u&password=dsn-marker-pw-7e41",
            "password parameter on the JDBC form, jdbc:postgresql://localhost:5432/db?user=u&password=dsn-marker-pw-7e41",
            "password parameter not last,       jdbc:postgresql://localhost:5432/db?password=dsn-marker-pw-7e41&user=u",
    })
    void toStringNeverPrintsThePassword(String rule, String raw) {
        assertThat(Dsn.parse(raw).toString()).as(rule).doesNotContain(MARKER);
    }
}
