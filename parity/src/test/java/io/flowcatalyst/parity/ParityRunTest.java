package io.flowcatalyst.parity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// Runs the full harness against a real Go `fc-server` (parity-harness spec,
/// brief §9). Skipped — cleanly, so `mvn test` stays green without a Go
/// toolchain (spec §10) — unless `PARITY_GO_SRC` or `PARITY_GO_BIN_DIR` is set.
/// `PARITY_ONLY` (optional), a glob over scenario file paths relative to
/// `parity/scenarios` (e.g. `smoke/*`, `applications/*`), narrows the run to
/// one group — so a scenario-authoring agent can run just its own group
/// without editing this class.
///
/// This asserts the HARNESS worked, not that Go and Java agree: the S0
/// scenario is expected to surface real diffs (that is the point of
/// building it), so the pinned assertion is "no step broke on its own
/// terms" (an `ERROR`: a bad substitution, a missing capture, a transport
/// failure, an `expect.status` mismatch) — never that the comparison itself
/// came back clean. Whether Go and Java actually agree is the orchestrator's
/// triage over `report.md`, not this test's job.
class ParityRunTest {

    private static final Logger LOG = LoggerFactory.getLogger(ParityRunTest.class);

    @Test
    void theHarnessRunsAgainstRealGoWithoutBreaking() {
        Assumptions.assumeTrue(System.getenv("PARITY_GO_SRC") != null || System.getenv("PARITY_GO_BIN_DIR") != null,
                "set PARITY_GO_SRC or PARITY_GO_BIN_DIR to run the real Go/Java parity comparison");

        var config = new Parity.Config(
                System.getenv("PARITY_GO_SRC"),
                System.getenv("PARITY_GO_BIN_DIR"),
                Path.of("scenarios"),
                System.getenv("PARITY_ONLY"),
                Path.of("target/parity-report"),
                Path.of("surface.json"),
                Path.of("expected-diffs.json"));

        Report report = Parity.run(config);

        for (ScenarioResult scenario : report.scenarios()) {
            for (StepResult step : scenario.steps()) {
                if (step.status() == StepStatus.ERROR) {
                    LOG.error("scenario '{}' step '{}' broke: {}", scenario.scenarioName(), step.stepId(), step.error());
                }
                if (step.status() == StepStatus.DIFF) {
                    LOG.info("scenario '{}' step '{}': {} unaccepted diff(s) — see target/parity-report/report.md",
                            scenario.scenarioName(), step.stepId(), step.unaccepted().size());
                }
            }
        }

        // The pinned assertion: the harness itself must not have broken any step. A DIFF is
        // expected and reported, not asserted away here — see the class doc.
        assertThat(report.scenarios())
                .flatExtracting(ScenarioResult::steps)
                .as("no step should ERROR — that means the harness (not Go-vs-Java parity) broke")
                .noneMatch(s -> s.status() == StepStatus.ERROR);

        assertThat(report.scenarios()).as("at least the S0 smoke scenario should have run").isNotEmpty();
    }
}
