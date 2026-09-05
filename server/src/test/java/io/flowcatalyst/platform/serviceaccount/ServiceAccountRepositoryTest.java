package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The repository's row-level rules that need package access to pin properly
/// (spec §11): the legacy-plaintext compare-and-set upgrade (Go drift
/// `fdcd2c1`) and the X-06 corrupt-row read. `ServiceAccountOperationsTest`
/// covers everything reachable through the use-case envelope; this covers
/// what only the repository itself can be made to race.
class ServiceAccountRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final Encryption ENCRYPTION = Encryption.withKey(Encryption.generateKey());
    private static final ServiceAccountRepository repo = new ServiceAccountRepository(DS, Optional.of(ENCRYPTION));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private static String seedLegacyRow(String tokenRef, String signingSecretRef) {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        DB.insertInto(DSL.table("iam_service_accounts"),
                        DSL.field("id"), DSL.field("code"), DSL.field("name"), DSL.field("active"), DSL.field("wh_auth_type"),
                        DSL.field("wh_auth_token_ref"), DSL.field("wh_signing_secret_ref"))
                .values(id, "legacy-" + RUN + "-" + id, "Legacy", true, "BEARER_TOKEN", tokenRef, signingSecretRef)
                .execute();
        return id;
    }

    private static String storedTokenRef(String id) {
        return DB.fetchOne("SELECT wh_auth_token_ref FROM iam_service_accounts WHERE id = ?", id).get(0, String.class);
    }

    private static String storedSigningSecretRef(String id) {
        return DB.fetchOne("SELECT wh_signing_secret_ref FROM iam_service_accounts WHERE id = ?", id).get(0, String.class);
    }

    // ── Legacy plaintext upgrade (spec §11, Go drift fdcd2c1) ───────────────

    /// A Go-created database still holds plaintext in the `_ref` columns;
    /// every read that finds it rewrites it encrypted, and keeps resolving to
    /// the same plaintext afterwards.
    @Test
    void findByIdUpgradesLegacyPlaintextToEncryptedInPlace() {
        String id = seedLegacyRow("legacy-plaintext-token", null);

        var loaded = repo.findById(id).orElseThrow();
        assertThat(loaded.webhookCredentials().token()).as("reads through unchanged").isEqualTo("legacy-plaintext-token");

        assertThat(storedTokenRef(id)).as("the read upgraded the column in place").startsWith("encrypted:");

        var again = repo.findById(id).orElseThrow();
        assertThat(again.webhookCredentials().token()).as("still resolves to the same plaintext after the upgrade")
                .isEqualTo("legacy-plaintext-token");
    }

    /// The hazard the compare-and-set exists for: a reader loads plaintext P
    /// (the "as seen" snapshot) and schedules an upgrade, but a rotation
    /// stores a NEW secret Q **before** that upgrade's `UPDATE` runs. A blind
    /// `UPDATE … WHERE id = ?` would overwrite Q with an encryption of the
    /// stale P; every delivery signed with Q would then fail verification
    /// with nothing in the logs to say why.
    ///
    /// [ServiceAccountRepository#upgradeLegacySecrets] takes the "as seen"
    /// values as explicit parameters (not derived from a fresh read) so this
    /// race is reproducible without real thread interleaving: call it with a
    /// snapshot that is already stale by the time it runs.
    @Test
    void upgradeLegacySecretsDoesNotClobberARotationThatWonTheRace() {
        String id = seedLegacyRow(null, "stale-plaintext");

        // The rotation that "won the race" — it lands before the upgrade fires.
        String rotated = "encrypted:rotated-by-someone-else";
        DB.update(DSL.table("iam_service_accounts")).set(DSL.field("wh_signing_secret_ref", String.class), rotated)
                .where(DSL.field("id", String.class).eq(id)).execute();

        // The upgrade fires anyway, on the stale snapshot a hypothetical earlier read took.
        repo.upgradeLegacySecrets(id, null, "stale-plaintext");

        assertThat(storedSigningSecretRef(id)).as("the concurrent rotation must survive, not be overwritten by the stale read")
                .isEqualTo(rotated);
    }

    /// The other half: when nothing raced, the upgrade proceeds normally.
    @Test
    void upgradeLegacySecretsUpgradesWhenNothingRaced() {
        String id = seedLegacyRow(null, "plain-secret");

        repo.upgradeLegacySecrets(id, null, "plain-secret");

        String after = storedSigningSecretRef(id);
        assertThat(after).startsWith("encrypted:");
        assertThat(ENCRYPTION.decrypt(after)).isEqualTo(new io.flowcatalyst.platform.shared.encryption.Decryption.Plaintext("plain-secret"));
    }

    // ── Corrupt stored auth type (spec §11, X-06) ───────────────────────────

    @Test
    void findByIdFailsLoudlyOnACorruptWebhookAuthType() {
        String id = EntityType.SERVICE_ACCOUNT.generate();
        TestPg.withConstraintDropped(DS, "iam_service_accounts", "chk_iam_service_accounts_wh_auth_type", () -> {
            DB.insertInto(DSL.table("iam_service_accounts"),
                            DSL.field("id"), DSL.field("code"), DSL.field("name"), DSL.field("active"), DSL.field("wh_auth_type"))
                    .values(id, "corrupt-" + RUN, "Corrupt", true, "NOT_A_REAL_AUTH_TYPE")
                    .execute();
            try {
                assertThatThrownBy(() -> repo.findById(id)).isInstanceOf(CorruptServiceAccountException.class);
            } finally {
                DB.deleteFrom(DSL.table("iam_service_accounts")).where(DSL.field("id", String.class).eq(id)).execute();
            }
        });
    }
}
