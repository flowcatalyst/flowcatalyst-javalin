package io.flowcatalyst.server.dbsecret;

import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Spec `docs/spec/db-secret.md` §1, §4: applicability + startup-error rules.
class DbSecretModeTest {

    private static EnvReader reader(String... kv) {
        var m = new java.util.HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new EnvReader(m);
    }

    // ── applicability (spec §1) ─────────────────────────────────────────

    @Test
    void notApplicableWhenFcDatabaseUrlSetEvenWithArnAndHost() {
        var mode = DbSecretMode.resolve(reader(
                "FC_DATABASE_URL", "postgresql://x@y/z",
                "DB_SECRET_ARN", "arn:aws:secretsmanager:eu-west-1:1:secret:s",
                "DB_HOST", "db.internal"));
        assertThat(mode).isEqualTo(DbSecretMode.NotApplicable.INSTANCE);
    }

    @Test
    void notApplicableWhenDatabaseUrlSetEvenWithArnAndHost() {
        var mode = DbSecretMode.resolve(reader(
                "DATABASE_URL", "postgresql://x@y/z",
                "DB_SECRET_ARN", "arn:aws:secretsmanager:eu-west-1:1:secret:s",
                "DB_HOST", "db.internal"));
        assertThat(mode).isEqualTo(DbSecretMode.NotApplicable.INSTANCE);
    }

    @Test
    void notApplicableWhenArnMissing() {
        var mode = DbSecretMode.resolve(reader("DB_HOST", "db.internal"));
        assertThat(mode).isEqualTo(DbSecretMode.NotApplicable.INSTANCE);
    }

    @Test
    void notApplicableWhenHostMissing() {
        var mode = DbSecretMode.resolve(reader(
                "DB_SECRET_ARN", "arn:aws:secretsmanager:eu-west-1:1:secret:s"));
        assertThat(mode).isEqualTo(DbSecretMode.NotApplicable.INSTANCE);
    }

    @Test
    void notApplicableWhenNeitherArnNorHostSet() {
        var mode = DbSecretMode.resolve(reader());
        assertThat(mode).isEqualTo(DbSecretMode.NotApplicable.INSTANCE);
    }

    @Test
    void applicableWhenArnAndHostSetAndNoUrl() {
        var mode = DbSecretMode.resolve(reader(
                "DB_SECRET_ARN", "arn:aws:secretsmanager:eu-west-1:1:secret:s",
                "DB_HOST", "db.internal"));
        assertThat(mode).isInstanceOf(DbSecretMode.Applicable.class);
        var applicable = (DbSecretMode.Applicable) mode;
        assertThat(applicable.arn()).isEqualTo("arn:aws:secretsmanager:eu-west-1:1:secret:s");
        assertThat(applicable.host()).isEqualTo("db.internal");
        assertThat(applicable.dbName()).isEqualTo("flowcatalyst");
        assertThat(applicable.dbPort()).isEmpty();
        assertThat(applicable.refreshIntervalMs()).isEqualTo(300_000);
    }

    @Test
    void applicableCarriesDbNameDbPortAndRefreshIntervalOverrides() {
        var mode = DbSecretMode.resolve(reader(
                "DB_SECRET_ARN", "arn:aws:secretsmanager:eu-west-1:1:secret:s",
                "DB_HOST", "db.internal",
                "DB_NAME", "custom_db",
                "DB_PORT", "6000",
                "DB_SECRET_REFRESH_INTERVAL_MS", "1000"));
        var applicable = (DbSecretMode.Applicable) mode;
        assertThat(applicable.dbName()).isEqualTo("custom_db");
        assertThat(applicable.dbPort()).isEqualTo("6000");
        assertThat(applicable.refreshIntervalMs()).isEqualTo(1000);
    }

    // ── DB_SECRET_PROVIDER (spec §1) ─────────────────────────────────────

    @Test
    void providerDefaultsToAwsAndIsApplicable() {
        var mode = DbSecretMode.resolve(reader(
                "DB_SECRET_ARN", "arn:aws:secretsmanager:eu-west-1:1:secret:s",
                "DB_HOST", "db.internal",
                "DB_SECRET_PROVIDER", "aws"));
        assertThat(mode).isInstanceOf(DbSecretMode.Applicable.class);
    }

    @Test
    void providerIsCaseInsensitive() {
        var mode = DbSecretMode.resolve(reader(
                "DB_SECRET_ARN", "arn:aws:secretsmanager:eu-west-1:1:secret:s",
                "DB_HOST", "db.internal",
                "DB_SECRET_PROVIDER", "AWS"));
        assertThat(mode).isInstanceOf(DbSecretMode.Applicable.class);
    }

    @Test
    void unsupportedProviderThrowsWithExactMessage() {
        assertThatThrownBy(() -> DbSecretMode.resolve(reader(
                "DB_SECRET_ARN", "arn:aws:secretsmanager:eu-west-1:1:secret:s",
                "DB_HOST", "db.internal",
                "DB_SECRET_PROVIDER", "vault")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB_SECRET_PROVIDER \"vault\" not supported (only \"aws\")");
    }

    // ── secret parsing (spec §2, §4), through DbSecretFetcher's fetch entry ──

    @Test
    void nullSecretStringYieldsExactMessage() {
        assertThatThrownBy(() -> DbSecretFetcher.fetch(arn -> null, "arn:aws:secretsmanager:eu-west-1:1:secret:s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("secret arn:aws:secretsmanager:eu-west-1:1:secret:s has no string value");
    }

    @Test
    void nonJsonSecretStringYieldsExactMessagePrefixAndWrapsCause() {
        assertThatThrownBy(() -> DbSecretFetcher.fetch(arn -> "not json at all",
                "arn:aws:secretsmanager:eu-west-1:1:secret:s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("parse secret arn:aws:secretsmanager:eu-west-1:1:secret:s JSON")
                .cause().isNotNull();
    }

    @Test
    void secretMissingUsernameYieldsExactMessage() {
        assertThatThrownBy(() -> DbSecretFetcher.fetch(arn -> "{\"password\":\"p\"}",
                "arn:aws:secretsmanager:eu-west-1:1:secret:s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("secret arn:aws:secretsmanager:eu-west-1:1:secret:s is missing username/password");
    }

    @Test
    void secretMissingPasswordYieldsExactMessage() {
        assertThatThrownBy(() -> DbSecretFetcher.fetch(arn -> "{\"username\":\"u\"}",
                "arn:aws:secretsmanager:eu-west-1:1:secret:s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("secret arn:aws:secretsmanager:eu-west-1:1:secret:s is missing username/password");
    }

    @Test
    void secretWithBlankUsernameYieldsExactMessage() {
        assertThatThrownBy(() -> DbSecretFetcher.fetch(arn -> "{\"username\":\"  \",\"password\":\"p\"}",
                "arn:aws:secretsmanager:eu-west-1:1:secret:s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("secret arn:aws:secretsmanager:eu-west-1:1:secret:s is missing username/password");
    }

    @Test
    void wellFormedSecretParsesUsernamePasswordAndPort() {
        var creds = DbSecretFetcher.fetch(arn -> "{\"username\":\"u\",\"password\":\"p\",\"port\":6543}",
                "arn:aws:secretsmanager:eu-west-1:1:secret:s");
        assertThat(creds.username()).isEqualTo("u");
        assertThat(creds.password()).isEqualTo("p");
        assertThat(creds.port()).isEqualTo(6543);
    }

    @Test
    void wellFormedSecretWithoutPortDefaultsPortToZero() {
        var creds = DbSecretFetcher.fetch(arn -> "{\"username\":\"u\",\"password\":\"p\"}",
                "arn:aws:secretsmanager:eu-west-1:1:secret:s");
        assertThat(creds.port()).isZero();
    }
}
