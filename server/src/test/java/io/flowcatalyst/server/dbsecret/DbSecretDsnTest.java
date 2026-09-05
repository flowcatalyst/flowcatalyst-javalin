package io.flowcatalyst.server.dbsecret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec `docs/spec/db-secret.md` §2, §4: the pure DSN/ARN string helpers.
class DbSecretDsnTest {

    // ── port precedence: secret port > DB_PORT > 5432 (spec §2) ───────────

    @Test
    void secretPortWinsOverDbPortAndDefault() {
        var dsn = DbSecretDsn.build("alice", "s3cret", "db.internal", 6543, "5555", "flowcatalyst");
        assertThat(dsn).isEqualTo("postgresql://alice:s3cret@db.internal:6543/flowcatalyst");
    }

    @Test
    void dbPortWinsWhenSecretPortAbsent() {
        var dsn = DbSecretDsn.build("alice", "s3cret", "db.internal", 0, "5555", "flowcatalyst");
        assertThat(dsn).isEqualTo("postgresql://alice:s3cret@db.internal:5555/flowcatalyst");
    }

    @Test
    void dbPortWinsWhenSecretPortNonPositive() {
        var dsn = DbSecretDsn.build("alice", "s3cret", "db.internal", -1, "5555", "flowcatalyst");
        assertThat(dsn).isEqualTo("postgresql://alice:s3cret@db.internal:5555/flowcatalyst");
    }

    @Test
    void defaultPortWinsWhenNeitherSecretNorDbPortSet() {
        var dsn = DbSecretDsn.build("alice", "s3cret", "db.internal", 0, "", "flowcatalyst");
        assertThat(dsn).isEqualTo("postgresql://alice:s3cret@db.internal:5432/flowcatalyst");
    }

    // ── host already carrying a port is used as-is (spec §2) ──────────────

    @Test
    void hostAlreadyContainingPortIsUsedAsIs() {
        // The secret port, DB_PORT and the 5432 default are all live here, but
        // none of them should touch a host that already carries its own port —
        // pinning this the naive way (only checking the DSN string) would still
        // pass if build() silently ignored secretPort's precedence rule; the
        // separate port-precedence tests above are what actually kill that
        // mutant, this one only pins "host wins outright when it has a port".
        var dsn = DbSecretDsn.build("alice", "s3cret", "db.internal:7777", 6543, "5555", "flowcatalyst");
        assertThat(dsn).isEqualTo("postgresql://alice:s3cret@db.internal:7777/flowcatalyst");
    }

    // ── default db name ─────────────────────────────────────────────────

    @Test
    void emptyDbNameDefaultsToFlowcatalyst() {
        var dsn = DbSecretDsn.build("alice", "s3cret", "db.internal", 0, "", "");
        assertThat(dsn).isEqualTo("postgresql://alice:s3cret@db.internal:5432/flowcatalyst");
    }

    // ── password URL-encoding (spec §2, §4) ────────────────────────────────

    @Test
    void passwordSpecialCharactersAreUrlEncoded() {
        var dsn = DbSecretDsn.build("alice", "p@ss:w/rd%x", "db.internal", 0, "5432", "flowcatalyst");
        assertThat(dsn).isEqualTo("postgresql://alice:p%40ss%3Aw%2Frd%25x@db.internal:5432/flowcatalyst");
    }

    // ── regionFromArn (spec §2, §4) ─────────────────────────────────────

    @Test
    void regionFromArnExtractsTheFourthField() {
        assertThat(DbSecretDsn.regionFromArn("arn:aws:secretsmanager:eu-west-1:123:secret:x"))
                .isEqualTo("eu-west-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a-bare-secret-name",
            "arn:aws:secretsmanager", // too few colon-separated fields
            "notarn:aws:secretsmanager:eu-west-1:123:secret:x", // does not start with "arn"
            ""
    })
    void regionFromArnIsEmptyForNonArnOrShortInput(String s) {
        assertThat(DbSecretDsn.regionFromArn(s)).isEmpty();
    }
}
