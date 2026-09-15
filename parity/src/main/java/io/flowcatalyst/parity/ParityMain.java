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
///     [--sides go,java|rust,java] [--rust-src <path>] [--rust-bin-dir <path>]
/// ```
///
/// `--sides` (L1 lane, `docs/java-parity-plan.md` §3) selects the pair
/// under test; default `go,java` is the original behaviour, unchanged.
/// `rust,java` builds/uses `--rust-src`/`--rust-bin-dir` (else
/// `PARITY_RUST_SRC`/`PARITY_RUST_BIN_DIR`) instead of Go for the "left"
/// side — see [RustBinaries], [RustSeed].
///
/// Exits with [Report#exitCode()] — non-zero on any `DIFF`, `ERROR`, stale
/// allow-list entry, false `covers` claim, or coverage under threshold.
@Command(name = "parity", mixinStandardHelpOptions = true,
        description = "Runs the platform parity harness: the same scenarios against two sides on cloned seed databases.")
public final class ParityMain implements Callable<Integer> {

    @Option(names = "--go-src", description = "Go source tree to build fcdev/fc-server from (default: ${env:PARITY_GO_SRC:-../flowcatalyst-go})")
    private String goSrc;

    @Option(names = "--go-bin-dir", description = "Directory with prebuilt fcdev/fc-server binaries (skips the build)")
    private String goBinDir;

    @Option(names = "--rust-src", description = "Rust source tree to build fc-dev/fc-server from (default: ${env:PARITY_RUST_SRC:-../flowcatalyst-rust}); only used with --sides rust,java")
    private String rustSrc;

    @Option(names = "--rust-bin-dir", description = "Directory with prebuilt fc-dev/fc-server binaries (skips the build); only used with --sides rust,java")
    private String rustBinDir;

    @Option(names = "--sides", description = "Which pair to run: go,java (default) or rust,java", defaultValue = "go,java")
    private String sides;

    @Option(names = "--scenarios", description = "Scenario directory", defaultValue = "parity/scenarios")
    private Path scenarios;

    @Option(names = "--only", description = "Glob filtering scenario files, relative to --scenarios (e.g. smoke/*.json)")
    private String only;

    @Option(names = "--report", description = "Report output directory", defaultValue = "parity/target/parity-report")
    private Path report;

    @Override
    public Integer call() {
        Parity.Sides parsedSides = parseSides(sides);
        var config = new Parity.Config(goSrc, goBinDir, rustSrc, rustBinDir, parsedSides, scenarios, only, report,
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

    private static Parity.Sides parseSides(String value) {
        String normalised = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (normalised) {
            case "", "go,java", "go-java", "go" -> Parity.Sides.GO_JAVA;
            case "rust,java", "rust-java", "rust" -> Parity.Sides.RUST_JAVA;
            default -> throw new IllegalArgumentException(
                    "--sides must be 'go,java' or 'rust,java' (got '" + value + "')");
        };
    }
}
