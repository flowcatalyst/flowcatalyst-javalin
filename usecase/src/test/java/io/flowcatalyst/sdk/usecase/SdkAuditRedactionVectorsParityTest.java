package io.flowcatalyst.sdk.usecase;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Test 6 (`docs/spec/audit-redaction.md`): "Each SDK carries a
/// byte-identical copy [of the vectors file] ... and a test in this repo
/// fails if a copy differs from the canonical file." The TypeScript and
/// Laravel SDK repos live under `clients/` in THIS repo (`clients/typescript-sdk`,
/// `clients/laravel-sdk`), owned by a parallel coder task that is adding
/// their vector copies at the same time this test was written — so this
/// test is EXPECTED to fail (with a clear "no copy found" message, not a
/// crash) until that lands. Once it lands, point [#TS_CANDIDATES] /
/// [#LARAVEL_CANDIDATES] at wherever the copy actually is, if it isn't one
/// of the guessed candidates below.
class SdkAuditRedactionVectorsParityTest {

    /// Candidate locations for the TypeScript SDK's copy, most likely first.
    private static final List<String> TS_CANDIDATES = List.of(
            "clients/typescript-sdk/tests/fixtures/audit-redaction-vectors.json",
            "clients/typescript-sdk/test/fixtures/audit-redaction-vectors.json",
            "clients/typescript-sdk/src/outbox/audit-redaction-vectors.json");

    /// Candidate locations for the Laravel SDK's copy, most likely first
    /// (`tests/Fixtures` already holds this SDK's other fixture files, e.g.
    /// `ScopedConnectionFixture.php`).
    private static final List<String> LARAVEL_CANDIDATES = List.of(
            "clients/laravel-sdk/tests/Fixtures/audit-redaction-vectors.json",
            "clients/laravel-sdk/tests/fixtures/audit-redaction-vectors.json",
            "clients/laravel-sdk/src/Outbox/audit-redaction-vectors.json");

    @Test
    void typeScriptSdkVectorsCopyIsByteIdenticalToTheCanonicalFile() throws IOException {
        assertCopyMatchesCanonical("clients/typescript-sdk", TS_CANDIDATES);
    }

    @Test
    void laravelSdkVectorsCopyIsByteIdenticalToTheCanonicalFile() throws IOException {
        assertCopyMatchesCanonical("clients/laravel-sdk", LARAVEL_CANDIDATES);
    }

    private static void assertCopyMatchesCanonical(String sdkLabel, List<String> candidates) throws IOException {
        Path repoRoot = repoRoot();
        Path found = null;
        for (String candidate : candidates) {
            Path p = repoRoot.resolve(candidate);
            if (Files.isRegularFile(p)) {
                found = p;
                break;
            }
        }
        assertThat(found)
                .as("%s's copy of audit-redaction-vectors.json — checked %s. Owned by a parallel coder task; "
                        + "if it lands somewhere else, update the candidate list in this test.", sdkLabel, candidates)
                .isNotNull();

        byte[] canonical = Files.readAllBytes(repoRoot.resolve("docs/spec/audit-redaction-vectors.json"));
        byte[] copy = Files.readAllBytes(found);
        assertThat(copy).as("%s's vectors copy (%s) must be byte-identical to the canonical file", sdkLabel, found)
                .isEqualTo(canonical);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("docs/spec/audit-redaction-vectors.json"))) return dir;
            dir = dir.getParent();
        }
        throw new UncheckedIOException(new IOException(
                "could not find docs/spec/audit-redaction-vectors.json above " + Path.of("").toAbsolutePath()));
    }
}
