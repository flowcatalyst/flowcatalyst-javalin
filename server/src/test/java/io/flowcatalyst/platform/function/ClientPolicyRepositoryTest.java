package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// `ClientPolicyRepository` against the embedded Postgres (spec
/// `function-registry.md` §6.4, §8 M14).
class ClientPolicyRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final ClientPolicyRepository REPO = new ClientPolicyRepository(DS);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// `fn_client_policies.client_id` is `VARCHAR(17)` — a fresh TSID fits, a
    /// hand-built run-suffixed string does not.
    private static String fresh() {
        return EntityType.CLIENT.generate();
    }

    private static void persist(ClientPolicy p) {
        UOW.inTransaction(tx -> {
            REPO.persist(p, tx.dbTx());
            return null;
        });
    }

    @Test
    void findByOwnerRoundTripsSignersAndCeilings() {
        FunctionOwner owner = FunctionOwner.ofClientId(fresh());
        Instant now = Instant.now();
        ClientPolicy p = new ClientPolicy(owner,
                List.of(new ClientPolicy.SignerRule("https://issuer", "subject-1", Set.of(Runtime.JVM, Runtime.WASM))),
                1000, 10, 64, 4, now, now);
        persist(p);

        ClientPolicy reloaded = REPO.findByOwner(owner).orElseThrow();
        assertThat(reloaded.signers()).hasSize(1);
        assertThat(reloaded.signers().get(0).runtimes()).containsExactlyInAnyOrder(Runtime.JVM, Runtime.WASM);
        assertThat(reloaded.maxDurationMs()).isEqualTo(1000);
        assertThat(reloaded.maxConcurrency()).isEqualTo(10);
        assertThat(reloaded.maxWasmMemoryMb()).isEqualTo(64);
        assertThat(reloaded.maxDbPoolSize()).isEqualTo(4);

        ClientPolicy updated = new ClientPolicy(owner, List.of(), null, null, null, null, now, Instant.now());
        persist(updated);
        ClientPolicy reloadedAgain = REPO.findByOwner(owner).orElseThrow();
        assertThat(reloadedAgain.signers()).isEmpty();
        assertThat(reloadedAgain.maxDurationMs()).isNull();

        assertThat(REPO.findByOwner(FunctionOwner.ofClientId("clt_doesnotexist"))).isEmpty();
    }

    // ── §8 M19: the platform policy is found by findByOwner(Platform) and by no client id ──

    @Test
    void platformPolicyIsFoundByOwnerPlatformAndByNoClientId() {
        Instant now = Instant.now();
        ClientPolicy platformPolicy = new ClientPolicy(new FunctionOwner.Platform(),
                List.of(new ClientPolicy.SignerRule("https://issuer", "subject-platform", Set.of(Runtime.JVM))),
                5000, null, null, null, now, now);
        persist(platformPolicy);

        ClientPolicy reloaded = REPO.findByOwner(new FunctionOwner.Platform()).orElseThrow();
        assertThat(reloaded.owner()).isEqualTo(new FunctionOwner.Platform());
        assertThat(reloaded.maxDurationMs()).isEqualTo(5000);

        assertThat(REPO.findByOwner(FunctionOwner.ofClientId(fresh())))
                .as("a fresh client id never resolves to the platform's row").isEmpty();
    }

    // ── §8 M14: fn_client_policies.signers — unknown runtime and blank issuer ──

    @Test
    void signersDropsABlankIssuerRuleAndAnUnknownRuntimeWithinARule() throws SQLException {
        String clientId = fresh();
        String signersJson = """
                [
                  {"issuer":"","subject":"sub-1","runtimes":["JVM"]},
                  {"issuer":"https://issuer-2","subject":"sub-2","runtimes":["JVM","BOGUS_RUNTIME"]}
                ]""";
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fn_client_policies (client_id, signers) VALUES (?, ?::jsonb)")) {
            ps.setString(1, clientId);
            ps.setString(2, signersJson);
            ps.executeUpdate();
        }

        ClientPolicy reloaded = REPO.findByOwner(FunctionOwner.ofClientId(clientId)).orElseThrow();
        assertThat(reloaded.signers()).as("the blank-issuer rule is dropped entirely").hasSize(1);
        ClientPolicy.SignerRule kept = reloaded.signers().get(0);
        assertThat(kept.issuer()).isEqualTo("https://issuer-2");
        assertThat(kept.runtimes()).as("the unknown runtime is dropped from the rule, not the whole rule")
                .containsExactly(Runtime.JVM);
    }

    @Test
    void signersReadsAsEmptyWhenTheColumnIsAnEmptyArray() throws SQLException {
        String clientId = fresh();
        try (Connection c = DS.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fn_client_policies (client_id, signers) VALUES (?, '[]'::jsonb)")) {
            ps.setString(1, clientId);
            ps.executeUpdate();
        }
        assertThat(REPO.findByOwner(FunctionOwner.ofClientId(clientId)).orElseThrow().signers()).isEmpty();
    }
}
