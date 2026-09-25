package io.flowcatalyst.parity;

import io.flowcatalyst.parity.model.Request;
import io.flowcatalyst.parity.model.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The `!go-expect` allow-list entry (parity-harness spec §6): Go failing a
/// step's own `expect` is `ACCEPTED` only when Go answered, Java met the
/// expectation, and an entry names that step. Every other shape stays the
/// `ERROR` it was.
class KnownGoFailureTest {

    @TempDir
    Path tempDir;

    private static final Step STEP = new Step("sync-create",
            new Request("POST", "/x", null, null, null, null, null), new Step.Expect(200), null, null, null, null);
    private static final StepRecord GO_500 = new StepRecord(500, Map.of(), null, "boom", null);
    private static final StepRecord JAVA_200 = new StepRecord(200, Map.of(), null, "ok", null);

    private ExpectedDiffs allowList(String step) throws IOException {
        Path file = tempDir.resolve("expected-diffs.json");
        Files.writeString(file, """
                [{"scenario":"s","step":"%s","pointer":"!go-expect","reason":"Go's sync 500","ruling":"r"}]
                """.formatted(step));
        return ExpectedDiffs.load(file);
    }

    private static Runner.StepOutcome goFailed(StepRecord record) {
        return new Runner.StepOutcome.Failed("sync-create", "expected status 200 but got 500", record);
    }

    private static Runner.StepOutcome javaRan() {
        return new Runner.StepOutcome.Ran("sync-create", JAVA_200, "POST", "/x");
    }

    @Test
    void goMissingTheExpectationUnderAnEntryIsAccepted() throws IOException {
        ExpectedDiffs expected = allowList("sync-create");

        StepResult result = Parity.knownGoFailure("s", STEP, goFailed(GO_500), javaRan(), expected);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(StepStatus.ACCEPTED);
        assertThat(result.diffs()).extracting(DiffEntry::pointer).containsExactly(Parity.GO_EXPECT);
        assertThat(expected.stale()).as("the entry was used, so it is not stale").isEmpty();
    }

    @Test
    void withoutAnEntryItStaysAnError() throws IOException {
        ExpectedDiffs expected = allowList("some-other-step");

        assertThat(Parity.knownGoFailure("s", STEP, goFailed(GO_500), javaRan(), expected)).isNull();
        assertThat(expected.stale()).as("an entry for a step Go now passes goes stale").hasSize(1);
    }

    @Test
    void javaFailingTooIsNeverAccepted() throws IOException {
        ExpectedDiffs expected = allowList("sync-create");
        Runner.StepOutcome javaFailed = new Runner.StepOutcome.Failed("sync-create", "expected status 200 but got 500", GO_500);

        assertThat(Parity.knownGoFailure("s", STEP, goFailed(GO_500), javaFailed, expected)).isNull();
    }

    @Test
    void goNeverAnsweringIsNeverAccepted() throws IOException {
        ExpectedDiffs expected = allowList("sync-create");

        assertThat(Parity.knownGoFailure("s", STEP, goFailed(null), javaRan(), expected))
                .as("a transport failure or bad substitution is a harness problem, not a known Go defect").isNull();
    }

    @Test
    void aWildcardEntryIsRefusedAtLoad() throws IOException {
        Path file = tempDir.resolve("wild.json");
        Files.writeString(file, """
                [{"scenario":"s","step":"*","pointer":"!go-expect","reason":"x","ruling":"r"}]
                """);

        assertThatThrownBy(() -> ExpectedDiffs.load(file))
                .as("a wildcard would excuse every Go failure in the scenario, the harness's own breakage included")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aScenarioNamePrefixIsAllowed() throws IOException {
        Path file = tempDir.resolve("prefix.json");
        Files.writeString(file, """
                [{"scenario":"s*","step":"sync-create","pointer":"!go-expect","reason":"x","ruling":"r"}]
                """);
        ExpectedDiffs expected = ExpectedDiffs.load(file);

        assertThat(Parity.knownGoFailure("s: the long scenario name", STEP, goFailed(GO_500), javaRan(), expected))
                .as("the file names scenarios by prefix; the guard is for a bare \"*\"").isNotNull();
    }
}
