package io.flowcatalyst.parity;

import java.util.List;

/// The whole run (parity-harness spec §6). Exit status is non-zero on any
/// `DIFF`, any `ERROR`, any stale allow-list entry, any false `covers`
/// claim, or coverage under [#REQUIRED_COVERAGE] — a gate, not a dashboard.
///
/// @param staleEntries `expected-diffs.json` entries that matched nothing this run
public record Report(List<ScenarioResult> scenarios, Coverage.Result coverage, List<ExpectedDiff> staleEntries) {

    /// Raised to `1.0` when parity-harness spec §8 phase S3 lands, exactly as `LockfileCoverageTest` did.
    public static final double REQUIRED_COVERAGE = 0.0;

    public Report {
        scenarios = List.copyOf(scenarios);
        staleEntries = List.copyOf(staleEntries);
    }

    public boolean anyDiff() {
        return scenarios.stream().anyMatch(ScenarioResult::hasDiff);
    }

    public boolean anyError() {
        return scenarios.stream().anyMatch(ScenarioResult::hasError);
    }

    public boolean anyFalseCoversClaim() {
        return scenarios.stream().anyMatch(s -> !s.unmetCoversClaims().isEmpty());
    }

    public boolean anyStaleAllowListEntry() {
        return !staleEntries.isEmpty();
    }

    public boolean coverageBelowThreshold() {
        return coverage.lockfileCoverage() < REQUIRED_COVERAGE || coverage.surfaceCoverage() < REQUIRED_COVERAGE;
    }

    /// `0` when the run is clean by every rule above; `1` otherwise.
    public int exitCode() {
        return anyDiff() || anyError() || anyFalseCoversClaim() || anyStaleAllowListEntry() || coverageBelowThreshold()
                ? 1 : 0;
    }
}
