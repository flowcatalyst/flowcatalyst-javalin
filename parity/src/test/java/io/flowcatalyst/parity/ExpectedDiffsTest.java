package io.flowcatalyst.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The `expected-diffs.json` allow-list (parity-harness spec §6, amended for
/// `"*"` scenario/step and `"**/"` pointer-suffix wildcards): a matching
/// entry is `ACCEPTED`, an entry that matched nothing is stale and fails the
/// run — the rule applies to wildcard entries too.
class ExpectedDiffsTest {

    @TempDir
    Path tempDir;

    private Path write(String json) throws IOException {
        Path file = tempDir.resolve("expected-diffs.json");
        Files.writeString(file, json);
        return file;
    }

    @Test
    void anExactMatchIsAccepted() throws IOException {
        ExpectedDiffs diffs = ExpectedDiffs.load(write("""
                [{"scenario":"s0","step":"health","pointer":"/version","reason":"build id differs","ruling":"backlog#1"}]
                """));

        assertThat(diffs.accepts("s0", "health", "/version")).isTrue();
        assertThat(diffs.stale()).isEmpty();
    }

    @Test
    void aNonMatchingEntryIsNotAcceptedAndIsStale() throws IOException {
        ExpectedDiffs diffs = ExpectedDiffs.load(write("""
                [{"scenario":"s0","step":"health","pointer":"/version","reason":"build id differs","ruling":"backlog#1"}]
                """));

        assertThat(diffs.accepts("s0", "other-step", "/version")).isFalse();
        assertThat(diffs.stale()).hasSize(1);
    }

    /// A stale entry fails the run — this is the assertion CLAUDE.md's
    /// testing policy calls for: the entry existing is not enough, it must
    /// actually have been exercised by a real diff during the run.
    @Test
    void anEntryThatMatchedNothingInTheRunIsStale() throws IOException {
        ExpectedDiffs diffs = ExpectedDiffs.load(write("""
                [{"scenario":"s0","step":"health","pointer":"/version","reason":"x","ruling":"r"}]
                """));

        // no accepts() call at all this run — nothing matched it
        assertThat(diffs.stale()).extracting("pointer").containsExactly("/version");
    }

    @Test
    void aWildcardScenarioAndStepMatchAnything() throws IOException {
        ExpectedDiffs diffs = ExpectedDiffs.load(write("""
                [{"scenario":"*","step":"*","pointer":"/schema","reason":"x","ruling":"r"}]
                """));

        assertThat(diffs.accepts("any-scenario", "any-step", "/schema")).isTrue();
    }

    @Test
    void aDoubleStarPointerMatchesItsSuffixAtAnyDepth() throws IOException {
        ExpectedDiffs diffs = ExpectedDiffs.load(write("""
                [{"scenario":"*","step":"*","pointer":"**/$schema","reason":"huma schema-link","ruling":"owner? parity-harness.md §10"}]
                """));

        assertThat(diffs.accepts("s0", "health", "/$schema")).isTrue();
        assertThat(diffs.accepts("s0", "list", "/items/3/$schema")).isTrue();
        assertThat(diffs.accepts("s0", "list", "/items/3/notSchema")).isFalse();
    }

    /// The stale rule applies to wildcard entries too (coordinator amendment
    /// to spec §6): declaring `"**/$schema"` and never actually seeing a
    /// `$schema` diff must still fail the run.
    @Test
    void aWildcardEntryThatMatchedNothingIsStillStale() throws IOException {
        ExpectedDiffs diffs = ExpectedDiffs.load(write("""
                [{"scenario":"*","step":"*","pointer":"**/$schema","reason":"x","ruling":"r"}]
                """));

        assertThat(diffs.accepts("s0", "health", "/status")).isFalse(); // unrelated diff — never matches
        assertThat(diffs.stale()).hasSize(1);
    }

    @Test
    void aWildcardEntryThatMatchedAtLeastOnceIsNotStale() throws IOException {
        ExpectedDiffs diffs = ExpectedDiffs.load(write("""
                [{"scenario":"*","step":"*","pointer":"**/$schema","reason":"x","ruling":"r"}]
                """));

        assertThat(diffs.accepts("s0", "health", "/$schema")).isTrue();
        assertThat(diffs.stale()).isEmpty();
    }

    @Test
    void anEntryWithNoRulingIsRejectedAtLoadTime() throws IOException {
        Path file = write("""
                [{"scenario":"s0","step":"health","pointer":"/version","reason":"x","ruling":null}]
                """);

        assertThatThrownBy(() -> ExpectedDiffs.load(file)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingFileIsAnEmptyAllowList() {
        ExpectedDiffs diffs = ExpectedDiffs.load(tempDir.resolve("does-not-exist.json"));
        assertThat(diffs.accepts("s0", "health", "/version")).isFalse();
        assertThat(diffs.stale()).isEmpty();
    }

    /// `/prefix/**` matches the prefix itself and everything below it — for a
    /// diff that is one row count (a list with a different number of entries
    /// walks element by element).
    @Test
    void aPrefixWildcardMatchesEverythingBelowThePointer() throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("expected", ".json");
        java.nio.file.Files.writeString(f, "[{\"scenario\":\"*\",\"step\":\"s\",\"pointer\":\"/entries/**\",\"reason\":\"r\",\"ruling\":\"x\"}]");
        ExpectedDiffs e = ExpectedDiffs.load(f);
        assertThat(e.accepts("any", "s", "/entries")).isTrue();
        assertThat(e.accepts("any", "s", "/entries/3/id")).isTrue();
        assertThat(e.accepts("any", "s", "/entriesX")).isFalse();
        assertThat(e.stale()).isEmpty();
    }
}
