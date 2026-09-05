package io.flowcatalyst.parity.model;

import java.util.List;

/// One scenario file: an ordered list of [Step]s run to completion on Go
/// first and then on Java, each side against its own clone (parity-harness
/// spec §3).
///
/// @param name   human-readable, used in the report
/// @param covers the lockfile `operationId`s this scenario claims to
///               exercise; the runner fails the scenario if a claimed id was
///               never actually requested (spec §7)
/// @param steps  run in order; a step's `id` must be unique within the scenario
public record Scenario(String name, List<String> covers, List<Step> steps) {
    public Scenario {
        covers = covers == null ? List.of() : List.copyOf(covers);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
