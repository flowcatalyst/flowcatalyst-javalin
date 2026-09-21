package io.flowcatalyst.platform.function;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import io.flowcatalyst.testpg.TestPg;

/// Pins the V13 migration's own guardrails (`docs/spec/function-registry.md`
/// §2, §8 M13) with raw SQL against the shared [TestPg] database — no
/// repository/entity code exists yet (a later slice of package A), so this is
/// Postgres enforcing its own constraints, not Java validating anything. Each
/// assertion here is required, not decorative: §8 M13 names two of its
/// mutations directly (drop the partial unique index; the LABEL regex's
/// trailing-hyphen exclusion), and this class' own report records which
/// assertion each of the six mutations tried against `V13__functions.sql`
/// actually kills.
class FunctionSchemaTest {

    private final DataSource ds = TestPg.dataSource();
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    /// A fresh id under 17 characters — `TestPg`'s shared database is never
    /// truncated between tests, so every row here must be seeded under an id
    /// nothing else could plausibly use.
    private static String freshId() {
        return "x" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private void exec(String sql, Object... args) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) {
                    ps.setNull(i + 1, Types.NULL);
                } else if (args[i] instanceof Integer n) {
                    ps.setInt(i + 1, n);
                } else if (args[i] instanceof OffsetDateTime odt) {
                    ps.setObject(i + 1, odt);
                } else {
                    ps.setString(i + 1, args[i].toString());
                }
            }
            ps.executeUpdate();
        }
    }

    private String createFunction(String serviceName) throws SQLException {
        String id = freshId();
        exec("""
                INSERT INTO fn_functions (id, application_id, application_code, service_name, name, client_id, runtime)
                VALUES (?, ?, 'app-code', ?, 'fn-name', ?, 'JVM')""",
                id, freshId(), serviceName, freshId());
        return id;
    }

    private String createFunction() throws SQLException {
        return createFunction("svc-" + freshId());
    }

    private void insertVersion(String functionId, int version, String digest, String state, OffsetDateTime readyAt,
            OffsetDateTime retiredAt) throws SQLException {
        exec("""
                INSERT INTO fn_versions (id, function_id, version, artifact_ref, digest, manifest, state,
                    published_by, ready_at, retired_at)
                VALUES (?, ?, ?, 'oci://artifact', ?, '{}'::jsonb, ?, ?, ?, ?)""",
                freshId(), functionId, version, digest, state, freshId(), readyAt, retiredAt);
    }

    private void insertRoute(String functionId, String hostname, String pathPrefix) throws SQLException {
        exec("INSERT INTO fn_routes (id, function_id, hostname, path_prefix) VALUES (?, ?, ?, ?)",
                freshId(), functionId, hostname, pathPrefix);
    }

    private void insertClientPolicy(String clientId, Integer maxDurationMs) throws SQLException {
        exec("INSERT INTO fn_client_policies (client_id, max_duration_ms) VALUES (?, ?)", clientId, maxDurationMs);
    }

    private static String validDigest() {
        return "sha256:" + "a".repeat(64);
    }

    // -- fn_functions_service_name_check (LABEL) --------------------------

    @Test
    void serviceNameLabelCheckAcceptsValidLabels() {
        assertThatCode(() -> createFunction("a")).doesNotThrowAnyException();
        assertThatCode(() -> createFunction("a-b")).doesNotThrowAnyException();
        assertThatCode(() -> createFunction("a".repeat(63))).doesNotThrowAnyException();
    }

    @Test
    void serviceNameLabelCheckRejectsInvalidLabels() {
        assertThatThrownBy(() -> createFunction("a-")).hasMessageContaining("fn_functions_service_name_check");
        assertThatThrownBy(() -> createFunction("-a")).hasMessageContaining("fn_functions_service_name_check");
        assertThatThrownBy(() -> createFunction("A")).hasMessageContaining("fn_functions_service_name_check");
        assertThatThrownBy(() -> createFunction("a_b")).hasMessageContaining("fn_functions_service_name_check");
        // 64 chars is rejected by the column width (VARCHAR(63)) before Postgres
        // ever reaches the CHECK constraint — still a DB-level rejection.
        assertThatThrownBy(() -> createFunction("a".repeat(64))).hasMessageContaining("value too long");
    }

    // -- fn_versions_digest_check -------------------------------------------

    @Test
    void digestCheckAcceptsSha256WithSixtyFourHexChars() throws SQLException {
        String functionId = createFunction();
        assertThatCode(() -> insertVersion(functionId, 1, validDigest(), "PUBLISHED", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void digestCheckRejectsAnythingElse() throws SQLException {
        String functionId = createFunction();
        assertThatThrownBy(() -> insertVersion(functionId, 1, "sha256:" + "a".repeat(63), "PUBLISHED", null, null))
                .as("63 hex chars, one short")
                .hasMessageContaining("fn_versions_digest_check");
        assertThatThrownBy(() -> insertVersion(functionId, 2, "sha256:" + "g".repeat(64), "PUBLISHED", null, null))
                .as("non-hex character")
                .hasMessageContaining("fn_versions_digest_check");
        assertThatThrownBy(() -> insertVersion(functionId, 3, "md5:" + "a".repeat(64), "PUBLISHED", null, null))
                .as("wrong algorithm prefix")
                .hasMessageContaining("fn_versions_digest_check");
        assertThatThrownBy(() -> insertVersion(functionId, 4, "a".repeat(64), "PUBLISHED", null, null))
                .as("missing prefix entirely")
                .hasMessageContaining("fn_versions_digest_check");
    }

    // -- fn_versions_ready_at_check ------------------------------------------

    @Test
    void readyStateWithNullReadyAtIsRejected() throws SQLException {
        String functionId = createFunction();
        assertThatThrownBy(() -> insertVersion(functionId, 1, validDigest(), "READY", null, null))
                .hasMessageContaining("fn_versions_ready_at_check");
    }

    @Test
    void readyStateWithReadyAtIsAccepted() throws SQLException {
        String functionId = createFunction();
        assertThatCode(() -> insertVersion(functionId, 1, validDigest(), "READY", OffsetDateTime.now(), null))
                .doesNotThrowAnyException();
    }

    // -- fn_routes uniqueness (spec `function-invocation.md` §3, amending §8 M13) --

    @Test
    void publicRouteUniquenessSpansAllFunctionsAndRejectsTheSecondInsert() throws SQLException {
        String f1 = createFunction();
        String f2 = createFunction();
        insertRoute(f1, "api.example.com", "/shared-public-path");
        assertThatThrownBy(() -> insertRoute(f2, "api.example.com", "/shared-public-path"))
                .hasMessageContaining("fn_routes_hostname_path_prefix_key");
    }

    @Test
    void twoFunctionsMayUseDifferentPrefixesOnTheSameHostname() throws SQLException {
        String f1 = createFunction();
        String f2 = createFunction();
        assertThatCode(() -> insertRoute(f1, "shared.example.com", "/a")).doesNotThrowAnyException();
        assertThatCode(() -> insertRoute(f2, "shared.example.com", "/b")).doesNotThrowAnyException();
    }

    @Test
    void hostnameIsRequired() {
        assertThatThrownBy(() -> insertRoute(createFunction(), null, "/no-hostname"))
                .as("every fn_routes row is public — a private call needs no route row at all (spec §2)")
                .hasMessageContaining("null value in column \"hostname\"");
    }

    // -- fn_client_policies ceiling checks -----------------------------------

    @Test
    void ceilingOfZeroIsRejected() {
        assertThatThrownBy(() -> insertClientPolicy(freshId(), 0))
                .hasMessageContaining("fn_client_policies_max_duration_ms_check");
    }

    @Test
    void ceilingAcceptsNullOrAPositiveValue() {
        assertThatCode(() -> insertClientPolicy(freshId(), null)).doesNotThrowAnyException();
        assertThatCode(() -> insertClientPolicy(freshId(), 500)).doesNotThrowAnyException();
    }
}
