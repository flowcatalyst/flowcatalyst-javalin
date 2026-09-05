package io.flowcatalyst.parity;

import io.flowcatalyst.platform.shared.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// The `parity/expected-diffs.json` allow-list (parity-harness spec §6): a
/// diff matching an entry is `ACCEPTED`; an entry that matched nothing in
/// the run is stale and fails the run regardless — the file cannot rot, and
/// a Java fix that removes a difference has to remove its excuse too.
public final class ExpectedDiffs {

    private final List<ExpectedDiff> entries;
    private final Set<ExpectedDiff> used = new HashSet<>();

    private ExpectedDiffs(List<ExpectedDiff> entries) {
        this.entries = entries;
    }

    /// An empty allow-list — every diff is a `DIFF`.
    public static ExpectedDiffs empty() {
        return new ExpectedDiffs(List.of());
    }

    /// Loads and validates `path`: every entry must carry a non-blank
    /// `ruling` (spec §6: "No `ruling` field, no entry").
    ///
    /// @throws IllegalStateException an entry has no ruling
    public static ExpectedDiffs load(Path path) {
        if (!Files.exists(path)) return empty();
        try {
            byte[] bytes = Files.readAllBytes(path);
            ExpectedDiff[] parsed = Json.MAPPER.readValue(bytes, ExpectedDiff[].class);
            for (ExpectedDiff e : parsed) {
                if (e.ruling() == null || e.ruling().isBlank()) {
                    throw new IllegalStateException(
                            "expected-diffs.json entry has no ruling: " + e.scenario() + "/" + e.step() + " " + e.pointer());
                }
            }
            return new ExpectedDiffs(List.of(parsed));
        } catch (IOException e) {
            throw new UncheckedIOException("read " + path, e);
        }
    }

    /// Whether `diff` at `scenario`/`step` is allow-listed; when it is, the
    /// matching entry is marked used (spec's amended wildcard rule: `"*"`
    /// scenario/step, `"**/"`-prefixed pointer matching any depth).
    public boolean accepts(String scenario, String step, String pointer) {
        boolean accepted = false;
        for (ExpectedDiff e : entries) {
            if (matches(e, scenario, step, pointer)) {
                used.add(e);
                accepted = true;
            }
        }
        return accepted;
    }

    private static boolean matches(ExpectedDiff e, String scenario, String step, String pointer) {
        boolean scenarioOk = "*".equals(e.scenario()) || e.scenario().equals(scenario);
        boolean stepOk = "*".equals(e.step()) || e.step().equals(step);
        boolean pointerOk = e.pointer().startsWith("**/")
                ? pointer.endsWith(e.pointer().substring(2))
                : e.pointer().equals(pointer);
        return scenarioOk && stepOk && pointerOk;
    }

    /// Entries that matched nothing in this run — the run fails when this is non-empty.
    public List<ExpectedDiff> stale() {
        return entries.stream().filter(e -> !used.contains(e)).toList();
    }
}
