package io.flowcatalyst.parity;

import java.util.List;

/// One scenario file's run (parity-harness spec §6).
///
/// @param unmetCoversClaims `covers` entries the scenario claimed but never actually requested on
///                           EITHER side — a false claim, which fails the run (spec §3, §6)
public record ScenarioResult(String scenarioName, List<StepResult> steps, List<String> unmetCoversClaims) {
    public ScenarioResult {
        steps = List.copyOf(steps);
        unmetCoversClaims = List.copyOf(unmetCoversClaims);
    }

    public boolean hasDiff() {
        return steps.stream().anyMatch(s -> s.status() == StepStatus.DIFF);
    }

    public boolean hasError() {
        return steps.stream().anyMatch(s -> s.status() == StepStatus.ERROR);
    }
}
