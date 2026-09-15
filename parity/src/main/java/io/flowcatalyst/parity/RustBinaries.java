package io.flowcatalyst.parity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/// The `fc-dev` and `fc-server` binaries for a Rust-vs-Java harness run
/// (parity-harness spec §1, extended by the L1 lane of
/// `docs/java-parity-plan.md`): built fresh from `PARITY_RUST_SRC` (default
/// `../flowcatalyst-rust`), or taken as-is from `PARITY_RUST_BIN_DIR` when
/// set — mirrors [GoBinaries] exactly, one level up: a `cargo build
/// --release` of both binaries in one invocation (sharing the dependency
/// graph, unlike Go's two separate `go build`s), written OUT of the Rust
/// tree via `--target-dir`, so the Rust repository is never written to.
///
/// A cold `cargo build --release` of the whole workspace is much slower
/// than `go build` — [#BUILD_TIMEOUT] is 30 minutes, not [GoBinaries]'s 2.
public final class RustBinaries {

    private static final Logger LOG = LoggerFactory.getLogger(RustBinaries.class);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);

    private final Path fcdev;
    private final Path fcServer;
    private final Duration buildDuration;

    private RustBinaries(Path fcdev, Path fcServer, Duration buildDuration) {
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

    /// `Duration.ZERO` when [#ENV_RUST_BIN_DIR] was used (nothing built).
    public Duration buildDuration() {
        return buildDuration;
    }

    public static final String ENV_RUST_SRC = "PARITY_RUST_SRC";
    public static final String ENV_RUST_BIN_DIR = "PARITY_RUST_BIN_DIR";

    /// [#resolve(Path, String, String)] reading `PARITY_RUST_SRC` /
    /// `PARITY_RUST_BIN_DIR` from the process environment.
    public static RustBinaries resolve(Path scratchDir) {
        return resolve(scratchDir, System.getenv(ENV_RUST_SRC), System.getenv(ENV_RUST_BIN_DIR));
    }

    /// `rustBinDir` (prebuilt `fc-dev` / `fc-server` inside it) if
    /// non-blank, else builds both from `rustSrc` (default
    /// `../flowcatalyst-rust` when `rustSrc` is null/blank) with one
    /// `cargo build --release --bin fc-server --bin fc-dev --target-dir
    /// <scratchDir>/rust-target` invocation. `ParityMain`'s `--rust-src` /
    /// `--rust-bin-dir` flags are threaded in here explicitly rather than
    /// read from the environment a second time, so a flag always wins over
    /// whatever the process environment happens to carry.
    public static RustBinaries resolve(Path scratchDir, String rustSrc, String rustBinDir) {
        if (rustBinDir != null && !rustBinDir.isBlank()) {
            Path dir = Path.of(rustBinDir);
            LOG.info("using prebuilt Rust binaries from {}", dir);
            return new RustBinaries(dir.resolve("fc-dev"), dir.resolve("fc-server"), Duration.ZERO);
        }
        Path srcDir = Path.of(rustSrc == null || rustSrc.isBlank() ? "../flowcatalyst-rust" : rustSrc)
                .toAbsolutePath().normalize();
        Path targetDir = scratchDir.resolve("rust-target");

        Instant start = Instant.now();
        build(srcDir, targetDir);
        Duration elapsed = Duration.between(start, Instant.now());
        Path releaseDir = targetDir.resolve("release");
        Path fcdevOut = releaseDir.resolve("fc-dev");
        Path fcServerOut = releaseDir.resolve("fc-server");
        LOG.info("cargo build --release fc-dev + fc-server from {} took {}", srcDir, elapsed);
        return new RustBinaries(fcdevOut, fcServerOut, elapsed);
    }

    private static void build(Path srcDir, Path targetDir) {
        if (!Files.isDirectory(srcDir)) {
            throw new IllegalStateException("Rust source tree not found at " + srcDir
                    + " (set " + ENV_RUST_SRC + " or " + ENV_RUST_BIN_DIR + ")");
        }
        var pb = new ProcessBuilder("cargo", "build", "--release",
                "--bin", "fc-server", "--bin", "fc-dev",
                "--target-dir", targetDir.toString())
                .directory(srcDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.PIPE);
        // fc-dev's build.rs embeds frontend/dist via rust-embed; the harness
        // doesn't need a real SPA bundle, only a non-empty directory (same
        // convention as `.github/workflows/ci.yml`'s FC_SKIP_FRONTEND_BUILD).
        pb.environment().put("FC_SKIP_FRONTEND_BUILD", "1");
        Process process;
        try {
            Path frontendDist = srcDir.resolve("frontend").resolve("dist");
            if (!Files.isDirectory(frontendDist)) {
                Files.createDirectories(frontendDist);
                Files.writeString(frontendDist.resolve("index.html"), "<!doctype html><title>stub</title>");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("stub frontend/dist for " + srcDir, e);
        }
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("start 'cargo build --release' in " + srcDir, e);
        }
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("read 'cargo build --release' output", e);
        }
        boolean finished;
        try {
            finished = process.waitFor(BUILD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for 'cargo build --release'", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("'cargo build --release' timed out after " + BUILD_TIMEOUT);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("'cargo build --release' failed (exit " + process.exitValue() + "):\n" + output);
        }
    }
}
