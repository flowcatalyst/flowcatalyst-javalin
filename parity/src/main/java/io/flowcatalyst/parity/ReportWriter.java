package io.flowcatalyst.parity;

import io.flowcatalyst.platform.shared.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// Writes `report.json` and `report.md` (parity-harness spec §6): one line
/// per `OK` step in the Markdown, the full diff otherwise, the coverage
/// table at the end.
public final class ReportWriter {

    private ReportWriter() {
    }

    public static void writeJson(Report report, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (IOException e) {
            throw new UncheckedIOException("write " + file, e);
        }
    }

    public static void writeMarkdown(Report report, Path file) {
        StringBuilder md = new StringBuilder();
        md.append("# Parity report\n\n");
        for (ScenarioResult scenario : report.scenarios()) {
            md.append("## ").append(scenario.scenarioName()).append("\n\n");
            if (!scenario.unmetCoversClaims().isEmpty()) {
                md.append("**False `covers` claim(s):** ").append(String.join(", ", scenario.unmetCoversClaims())).append("\n\n");
            }
            for (StepResult step : scenario.steps()) {
                switch (step.status()) {
                    case OK -> md.append("- `OK` ").append(step.stepId()).append('\n');
                    case ACCEPTED -> {
                        md.append("- `ACCEPTED` ").append(step.stepId()).append(" (").append(step.diffs().size()).append(" allow-listed diff(s))\n");
                        step.diffs().forEach(d -> appendDiffLine(md, d));
                    }
                    case DIFF -> {
                        md.append("- `DIFF` ").append(step.stepId()).append('\n');
                        step.diffs().forEach(d -> appendDiffLine(md, d));
                    }
                    case ERROR -> md.append("- `ERROR` ").append(step.stepId()).append(": ").append(step.error()).append('\n');
                }
            }
            md.append('\n');
        }

        if (!report.staleEntries().isEmpty()) {
            md.append("## Stale allow-list entries\n\n");
            report.staleEntries().forEach(e -> md.append("- ").append(e.scenario()).append('/').append(e.step())
                    .append(' ').append(e.pointer()).append(" (ruling ").append(e.ruling()).append(")\n"));
            md.append('\n');
        }

        md.append("## Coverage\n\n");
        md.append(String.format(Locale.ROOT, "- lockfile: %d / %d (%.1f%%)%n",
                report.coverage().hitLockfile().size(), report.coverage().lockfileRoutes().size(),
                report.coverage().lockfileCoverage() * 100));
        md.append(String.format(Locale.ROOT, "- outside-lockfile surface: %d / %d (%.1f%%)%n",
                report.coverage().hitSurface().size(), report.coverage().surfaceRoutes().size(),
                report.coverage().surfaceCoverage() * 100));
        md.append('\n').append("Exit code: ").append(report.exitCode()).append('\n');

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, md.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("write " + file, e);
        }
    }

    private static void appendDiffLine(StringBuilder md, DiffEntry d) {
        md.append("  - `").append(d.pointer()).append("` go=`").append(d.go()).append("` java=`").append(d.java()).append("`\n");
    }
}
