package io.flowcatalyst.sdk.usecase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/// Runs every case in `docs/spec/audit-redaction-vectors.json` — the
/// contract `docs/spec/audit-redaction.md` says every implementation (Java,
/// TypeScript, Laravel) tests against — plus a couple of direct-call tests
/// for behaviour the vector set doesn't exercise (see
/// [#secretKeyWithABooleanValueIsKept]: the spec text requires booleans to
/// stay unmasked even behind a genuinely secret key, but none of the
/// canonical vectors pairs a secret key name with a boolean value).
class AuditRedactionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VECTORS_RESOURCE = "/audit-redaction-vectors.json";

    /// This module's copy MUST be kept byte-identical to the canonical file
    /// (`docs/spec/audit-redaction.md`: "a test in this repo fails if a copy
    /// differs from the canonical file"). Mutant: edit either copy without
    /// the other — this fails.
    @Test
    void testResourceCopyIsByteIdenticalToTheCanonicalFile() throws IOException {
        byte[] resourceCopy = readResourceBytes(VECTORS_RESOURCE);
        byte[] canonical = Files.readAllBytes(canonicalVectorsPath());
        assertThat(resourceCopy)
                .as("usecase/src/test/resources/audit-redaction-vectors.json must stay byte-identical "
                        + "to docs/spec/audit-redaction-vectors.json")
                .isEqualTo(canonical);
    }

    /// One dynamic test per vector (spec test 1). Mutant: drop the
    /// `endsWith("token")` clause -> the token-suffix vectors ("tokens, keys
    /// and secrets by name, any case") fail; mutant: over-match substrings
    /// instead of suffixes -> "look-alikes that are not secrets are kept"
    /// fails.
    @TestFactory
    List<DynamicTest> everyVector() throws IOException {
        JsonNode vectors = MAPPER.readTree(readResourceBytes(VECTORS_RESOURCE));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : vectors) {
            String name = vector.get("name").asString();
            JsonNode input = vector.get("input");
            JsonNode expected = vector.get("expected");
            Set<String> masked = StreamSupport.stream(vector.get("masked").spliterator(), false)
                    .map(JsonNode::asString)
                    .collect(Collectors.toUnmodifiableSet());
            tests.add(DynamicTest.dynamicTest(name, () -> {
                JsonNode actual = AuditRedaction.redact(input, masked);
                assertThat(actual).as(name).isEqualTo(expected);
            }));
        }
        assertThat(tests).as("the vectors file actually has cases").isNotEmpty();
        return tests;
    }

    /// The spec text: "except null (kept) and booleans (kept)" — for a key
    /// that genuinely matches the name rule, not merely one that looks like
    /// it (none of the canonical vectors pairs a truly secret key with a
    /// boolean value, so this is asserted directly). Mutant: mask booleans
    /// too -> this fails; the vector suite alone would not catch it.
    @Test
    void secretKeyWithABooleanValueIsKept() throws IOException {
        JsonNode input = MAPPER.readTree("{\"apiKey\": true, \"cookie\": false}");
        JsonNode expected = MAPPER.readTree("{\"apiKey\": true, \"cookie\": false}");
        assertThat(AuditRedaction.redact(input, Set.of())).isEqualTo(expected);
    }

    /// "pure, never mutates its input" (spec: `AuditRedaction`'s contract).
    /// Mutant: redact in place with `ObjectNode#set` on the input tree
    /// instead of building a fresh one -> the pre-call snapshot below stops
    /// matching `input` after the call.
    @Test
    void neverMutatesItsInput() throws IOException {
        JsonNode input = MAPPER.readTree("{\"password\":\"hunter2\",\"nested\":{\"token\":\"t\"}}");
        JsonNode snapshotBeforeCall = input.deepCopy();

        JsonNode redacted = AuditRedaction.redact(input, Set.of());

        assertThat(input).as("the input tree is unchanged after redact()").isEqualTo(snapshotBeforeCall);
        assertThat(redacted).as("but the returned tree IS redacted").isNotEqualTo(input);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static byte[] readResourceBytes(String resource) throws IOException {
        try (InputStream in = AuditRedactionTest.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("test resource not found: " + resource);
            return in.readAllBytes();
        }
    }

    /// Walks up from the current working directory (the module base
    /// directory under a reactor/surefire run, or the repo root under an
    /// IDE run) until it finds `docs/spec/audit-redaction-vectors.json`.
    private static Path canonicalVectorsPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("docs/spec/audit-redaction-vectors.json");
            if (Files.isRegularFile(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new UncheckedIOException(new IOException(
                "could not find docs/spec/audit-redaction-vectors.json above " + Path.of("").toAbsolutePath()));
    }
}
