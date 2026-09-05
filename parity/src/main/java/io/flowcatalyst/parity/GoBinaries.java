package io.flowcatalyst.parity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/// The `fcdev` and `fc-server` binaries the harness drives (parity-harness
/// spec §1): built fresh from `PARITY_GO_SRC` (default `../flowcatalyst-go`),
/// or taken as-is from `PARITY_GO_BIN_DIR` when set — the Go repository is
/// READ-ONLY, so a build here only ever runs `go build -o <scratch>/…`,
/// never writes anything under the Go tree itself.
public final class GoBinaries {

    private static final Logger LOG = LoggerFactory.getLogger(GoBinaries.class);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(2);

    private final Path fcdev;
    private final Path fcServer;
    private final Duration buildDuration;

    private GoBinaries(Path fcdev, Path fcServer, Duration buildDuration) {
        this.fcdev = fcdev;
        this.fcServer = fcServer;
        this.buildDuration = buildDuration;
    }

    public Path fcdev() {
        return fcdev;
    }

    public Path fcServer() {
        return fcServer;
    }

    /// `Duration.ZERO` when [#PARITY_GO_BIN_DIR] was used (nothing built).
    public Duration buildDuration() {
        return buildDuration;
    }

    public static final String ENV_GO_SRC = "PARITY_GO_SRC";
    public static final String ENV_GO_BIN_DIR = "PARITY_GO_BIN_DIR";

    /// [#resolve(Path, String, String)] reading `PARITY_GO_SRC` /
    /// `PARITY_GO_BIN_DIR` from the process environment — what `ParityRunTest`
    /// uses (its `Assumptions.assumeTrue` already requires one of them set).
    public static GoBinaries resolve(Path scratchDir) {
        return resolve(scratchDir, System.getenv(ENV_GO_SRC), System.getenv(ENV_GO_BIN_DIR));
    }

    /// `goBinDir` (prebuilt `fcdev` / `fc-server` inside it) if non-blank,
    /// else builds both from `goSrc` (default `../flowcatalyst-go` when
    /// `goSrc` is null/blank) into `scratchDir`. `ParityMain`'s `--go-src` /
    /// `--go-bin-dir` flags are threaded in here explicitly rather than read
    /// from the environment a second time, so a flag always wins over
    /// whatever the process environment happens to carry.
    public static GoBinaries resolve(Path scratchDir, String goSrc, String goBinDir) {
        if (goBinDir != null && !goBinDir.isBlank()) {
            Path dir = Path.of(goBinDir);
            LOG.info("using prebuilt Go binaries from {}", dir);
            return new GoBinaries(dir.resolve("fcdev"), dir.resolve("fc-server"), Duration.ZERO);
        }
        Path srcDir = Path.of(goSrc == null || goSrc.isBlank() ? "../flowcatalyst-go" : goSrc).toAbsolutePath().normalize();
        Path fcdevOut = scratchDir.resolve("fcdev");
        Path fcServerOut = scratchDir.resolve("fc-server");

        Instant start = Instant.now();
        build(srcDir, "./cmd/fcdev", fcdevOut);
        build(srcDir, "./cmd/fc-server", fcServerOut);
        Duration elapsed = Duration.between(start, Instant.now());
        LOG.info("go build fcdev + fc-server from {} took {}", srcDir, elapsed);
        return new GoBinaries(fcdevOut, fcServerOut, elapsed);
    }

    private static void build(Path srcDir, String pkg, Path out) {
        var pb = new ProcessBuilder("go", "build", "-o", out.toString(), pkg)
                .directory(srcDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.PIPE);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("start 'go build " + pkg + "' in " + srcDir, e);
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("read 'go build " + pkg + "' output", e);
        }
        boolean finished;
        try {
            finished = process.waitFor(BUILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for 'go build " + pkg + "'", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("'go build " + pkg + "' timed out after " + BUILD_TIMEOUT);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("'go build " + pkg + "' failed (exit " + process.exitValue() + "):\n" + output);
        }
    }
}
