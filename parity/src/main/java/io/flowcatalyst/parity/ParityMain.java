package io.flowcatalyst.parity;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/// The harness's CLI entry point (parity-harness spec, brief §9):
///
/// ```
/// java -cp … io.flowcatalyst.parity.ParityMain \
///     [--go-src <path>] [--go-bin-dir <path>] [--scenarios <dir>] [--only <glob>] [--report <dir>]
/// ```
///
/// Exits with [Report#exitCode()] — non-zero on any `DIFF`, `ERROR`, stale
/// allow-list entry, false `covers` claim, or coverage under threshold.
@Command(name = "parity", mixinStandardHelpOptions = true,
        description = "Runs the platform parity harness: the same scenarios against Go and Java on cloned seed databases.")
public final class ParityMain implements Callable<Integer> {

    @Option(names = "--go-src", description = "Go source tree to build fcdev/fc-server from (default: ${env:PARITY_GO_SRC:-../flowcatalyst-go})")
    private String goSrc;

    @Option(names = "--go-bin-dir", description = "Directory with prebuilt fcdev/fc-server binaries (skips the build)")
    private String goBinDir;

    @Option(names = "--scenarios", description = "Scenario directory", defaultValue = "parity/scenarios")
    private Path scenarios;

    @Option(names = "--only", description = "Glob filtering scenario files, relative to --scenarios (e.g. smoke/*.json)")
    private String only;

    @Option(names = "--report", description = "Report output directory", defaultValue = "parity/target/parity-report")
    private Path report;

    @Override
    public Integer call() {
        var config = new Parity.Config(goSrc, goBinDir, scenarios, only, report,
                Path.of("parity/surface.json"), Path.of("parity/expected-diffs.json"));
        Report result = Parity.run(config);
        System.out.println("parity report written to " + report.resolve("report.md"));
        System.out.println("exit code " + result.exitCode()
                + " (diff=" + result.anyDiff() + " error=" + result.anyError()
                + " falseCoversClaim=" + result.anyFalseCoversClaim()
                + " staleAllowListEntry=" + result.anyStaleAllowListEntry()
                + " coverageBelowThreshold=" + result.coverageBelowThreshold() + ")");
        return result.exitCode();
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ParityMain()).execute(args);
        System.exit(exitCode);
    }
}
