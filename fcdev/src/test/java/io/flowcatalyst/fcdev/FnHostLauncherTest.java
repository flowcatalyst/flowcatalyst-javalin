package io.flowcatalyst.fcdev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [FnHostLauncher]'s two branches (spec `docs/spec/function-developer-surface.md`
/// §1, E1/E2), driven directly with injected seams — "both are testable on
/// the JVM" (the child-process branch only ever runs for real under a
/// GraalVM native `fcdev`, which `mvn test` cannot produce).
class FnHostLauncherTest {

    /// A stub reporting an adequate Java feature version — used by tests
    /// whose focus is elsewhere (the command line, the env, graceful
    /// shutdown) so they don't also depend on [FnHostLauncher#MIN_JAVA_FEATURE_VERSION]'s
    /// exact value.
    private static final FnHostLauncher.JavaVersionResolver ADEQUATE_JAVA = java -> OptionalInt.of(25);

    private static FnHostLauncher.Settings settings(Path hostJar, Path cacheDir) {
        return new FnHostLauncher.Settings("default", "http://localhost:18080",
                "fcdev-fn-host", "s3cr3t", 18090, 18092, 18091, cacheDir, hostJar);
    }

    // ── E2: the native (child-process) branch ───────────────────────────

    /// The fake `java` records its argv and the env vars `HostEnv#load`
    /// reads, and traps TERM (exits cleanly instead of needing SIGKILL).
    /// Pins: the command line is `java -jar <hostJar>`; the child's
    /// environment carries the SAME values as the `FC_FN_*` settings plus
    /// `FC_METRICS_PORT` (not `FC_FN_METRICS_PORT` — that name is fcdev's OWN
    /// flag, kept distinct so it can never collide with the platform's own
    /// `FC_METRICS_PORT`) and `FLOWCATALYST_DEV_MODE=true`; `#close` sends
    /// TERM (not KILL — the trap marker proves graceful shutdown) and the
    /// process is gone afterwards. Mutant (E2 table): "leave the child
    /// running" — pinned by asserting `!process.isAlive()` after close AND
    /// that the TERM marker (not a SIGKILL-only path) was written.
    @Test
    void nativeBranchStartsTheRightCommandAndEnvAndStopsGracefullyOnClose(@TempDir Path dir) throws Exception {
        Path hostJar = dir.resolve("host.jar");
        Files.writeString(hostJar, "not a real jar — only its path and existence matter here");
        Path fakeJava = writeFakeJavaScript(dir);

        FnHostLauncher.Settings settings = settings(hostJar, dir.resolve("cache"));
        FnHostLauncher.Result result = FnHostLauncher.launch(settings, () -> true,
                () -> Optional.of(fakeJava), ADEQUATE_JAVA, FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.ChildProcess.class);
        Process process = ((FnHostLauncher.ChildProcess) result).process();
        awaitFile(dir.resolve("started.txt"), Duration.ofSeconds(5));

        List<String> argv = Files.readAllLines(dir.resolve("argv.txt"));
        assertThat(argv).as("java -jar <hostJar>").containsExactly("-jar", hostJar.toString());

        Map<String, String> env = readEnvFile(dir.resolve("env.txt"));
        assertThat(env)
                .containsEntry("FC_FN_POOL", "default")
                .containsEntry("FC_FN_PLATFORM_URL", "http://localhost:18080")
                .containsEntry("FC_FN_CLIENT_ID", "fcdev-fn-host")
                .containsEntry("FC_FN_CLIENT_SECRET", "s3cr3t")
                .containsEntry("FC_FN_PORT", "18090")
                .containsEntry("FC_FN_SIGNATURES", "off")
                .containsEntry("FC_METRICS_PORT", "18091")
                .containsEntry("FLOWCATALYST_DEV_MODE", "true");
        // fcdev's OWN flag name (FC_FN_METRICS_PORT) must never leak into the
        // child's environment under that name — only FC_METRICS_PORT, which
        // is what HostEnv#load actually reads. The script reports "<UNSET>"
        // for a name that is genuinely absent from its process environment
        // (not merely omitted from what we chose to assert on).
        assertThat(env).containsEntry("FC_FN_METRICS_PORT", "<UNSET>");

        Instant beforeClose = Instant.now();
        FnHostLauncher.close(result);
        Duration elapsed = Duration.between(beforeClose, Instant.now());

        assertThat(process.isAlive()).as("mutant: the child must not be left running").isFalse();
        assertThat(dir.resolve("term.txt")).as("graceful TERM, not a SIGKILL-only path").exists();
        assertThat(elapsed).as("a script that traps TERM must not need the 10s destroyForcibly wait")
                .isLessThan(Duration.ofSeconds(8));
    }

    /// No `java` resolvable at all: `Disabled`, never an exception —
    /// `fcdev start` must still succeed. The reason names BOTH remedies
    /// (`JAVA_HOME`/`PATH`) — a mutant that named only one, or that threw
    /// instead of returning `Disabled`, fails this.
    @Test
    void missingJavaRuntimeIsDisabledNamingBothRemediesAndNeverThrows(@TempDir Path dir) {
        Path hostJar = dir.resolve("host.jar");
        FnHostLauncher.Result result = FnHostLauncher.launch(settings(hostJar, dir.resolve("cache")),
                () -> true, Optional::empty, ADEQUATE_JAVA, FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.Disabled.class);
        String reason = ((FnHostLauncher.Disabled) result).reason();
        assertThat(reason).contains("JAVA_HOME").contains("PATH");
    }

    /// A resolvable `java` but too old for the function host (release 25 +
    /// `--enable-preview`, `CONVENTIONS.md` §8): `Disabled`, naming the
    /// version actually found AND the required one — never an exception, and
    /// never a real child process spawned (a real Java 21 would die in the
    /// child with `UnsupportedClassVersionError` instead, a confusing
    /// failure this check exists to pre-empt). Mutant: skip the version
    /// check entirely (always "adequate").
    @Test
    void javaOlderThanTheMinimumIsDisabledNamingTheVersionFoundAndNeverThrows(@TempDir Path dir) throws Exception {
        Path fakeJava = writeFakeJavaScript(dir);
        var capturedPath = new AtomicReference<Path>();
        FnHostLauncher.JavaVersionResolver tooOld = java -> {
            capturedPath.set(java);
            return OptionalInt.of(21);
        };

        FnHostLauncher.Result result = FnHostLauncher.launch(settings(dir.resolve("host.jar"), dir.resolve("cache")),
                () -> true, () -> Optional.of(fakeJava), tooOld, FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.Disabled.class);
        String reason = ((FnHostLauncher.Disabled) result).reason();
        assertThat(reason).as("names the version found").contains("21")
                .as("names the version required").contains("25");
        assertThat(capturedPath.get()).as("the resolver must be asked about the RESOLVED java")
                .isEqualTo(fakeJava);
    }

    /// A version that could not be determined at all (spawn failure,
    /// unparseable output) is refused the same as "too old" — never treated
    /// as "assume it's fine". Mutant: `OptionalInt.empty()` slips past the
    /// `< MIN_JAVA_FEATURE_VERSION` check.
    @Test
    void undeterminableJavaVersionIsDisabledNotAssumedAdequate(@TempDir Path dir) throws Exception {
        Path fakeJava = writeFakeJavaScript(dir);

        FnHostLauncher.Result result = FnHostLauncher.launch(settings(dir.resolve("host.jar"), dir.resolve("cache")),
                () -> true, () -> Optional.of(fakeJava), java -> OptionalInt.empty(),
                FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.Disabled.class);
    }

    /// A resolvable `java` but no host jar at the configured/default path:
    /// `Disabled`, naming the remedy — never an exception.
    @Test
    void missingHostJarIsDisabledNamingTheRemedyAndNeverThrows(@TempDir Path dir) throws Exception {
        Path fakeJava = writeFakeJavaScript(dir);
        Path missingJar = dir.resolve("does-not-exist.jar");

        FnHostLauncher.Result result = FnHostLauncher.launch(settings(missingJar, dir.resolve("cache")),
                () -> true, () -> Optional.of(fakeJava), ADEQUATE_JAVA, FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.Disabled.class);
        String reason = ((FnHostLauncher.Disabled) result).reason();
        assertThat(reason).contains("fn-host-jar").contains("no-functions");
    }

    /// The process-starter seam itself failing (a real fork error) is also
    /// `Disabled`, never an exception escaping `#launch`.
    @Test
    void aProcessStartFailureIsDisabledNotAnException(@TempDir Path dir) throws Exception {
        Path hostJar = dir.resolve("host.jar");
        Files.writeString(hostJar, "x");
        Path fakeJava = writeFakeJavaScript(dir);

        FnHostLauncher.ProcessStarter failing = (command, env) -> {
            throw new IOException("synthetic fork failure");
        };
        FnHostLauncher.Result result = FnHostLauncher.launch(settings(hostJar, dir.resolve("cache")),
                () -> true, () -> Optional.of(fakeJava), ADEQUATE_JAVA, failing);

        assertThat(result).isInstanceOf(FnHostLauncher.Disabled.class);
    }

    // ── E1: the JVM (in-process) branch ─────────────────────────────────

    /// `isNative = false` never spawns a process — the launcher builds
    /// [io.flowcatalyst.fnhost.reconcile.HostEnv] directly and starts
    /// `FnHost` in THIS JVM. The platform URL is deliberately unreachable
    /// (an unbound loopback port): `FnHost#start` guards a failed first
    /// reconcile (`function-host-process.md` §3 item 2) and still binds the
    /// function listener, so this proves the in-process branch actually
    /// starts a real, bound host without needing a real platform up.
    @Test
    void jvmBranchStartsInProcessWithNoChildProcessAtAll(@TempDir Path dir) throws Exception {
        int deadPort;
        try (var probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        var settings = new FnHostLauncher.Settings("default", "http://127.0.0.1:" + deadPort,
                "fcdev-fn-host", "s3cr3t", 0, 0, 0, dir.resolve("cache"), null);

        FnHostLauncher.Result result = FnHostLauncher.launch(settings, () -> false,
                FnHostLauncher.DEFAULT_JAVA_RESOLVER, FnHostLauncher.DEFAULT_JAVA_VERSION_RESOLVER,
                FnHostLauncher.DEFAULT_PROCESS_STARTER);

        assertThat(result).isInstanceOf(FnHostLauncher.InProcess.class);
        var host = ((FnHostLauncher.InProcess) result).host();
        assertThat(host.port()).as("the function listener must be bound").isGreaterThan(0);
        assertThat(host.publicPort()).as("the public listener must be bound too (Settings#toHostEnv wires it)")
                .isGreaterThan(0);

        FnHostLauncher.close(result);
    }

    // ── java.specification.version parsing (production resolver) ────────

    /// The exact shape `-XshowSettings:properties` prints on a modern JDK.
    @Test
    void parsesTheFeatureVersionFromShowSettingsOutput() {
        String output = """
                Property settings:
                    java.specification.version = 25
                    java.vendor = Eclipse Adoptium
                openjdk version "25" 2025-09-16
                """;
        assertThat(FnHostLauncher.parseFeatureVersion(output)).hasValue(25);
    }

    /// Pre-JEP-223 versioning (Java 8 and earlier): `"1.8"` → feature 8.
    @Test
    void parsesTheLegacyOneDotEightStyleVersion() {
        String output = "    java.specification.version = 1.8\n";
        assertThat(FnHostLauncher.parseFeatureVersion(output)).hasValue(8);
    }

    /// No matching property line (unrecognised/garbled output): empty, never
    /// a thrown exception — [#launchChildProcess] treats this as "too old".
    @Test
    void unparseableOutputYieldsAnEmptyVersion() {
        assertThat(FnHostLauncher.parseFeatureVersion("not a properties dump at all")).isEmpty();
    }

    /// The REAL production resolver ([FnHostLauncher#DEFAULT_JAVA_VERSION_RESOLVER])
    /// against the REAL `java` this build runs on (`JAVA_HOME`, per
    /// `CLAUDE.md`) — proves the subprocess + parsing wiring works end to
    /// end, not just the pure parser above. This repo is pinned to JDK 25
    /// (`CLAUDE.md` "Build"), so this must resolve to exactly that.
    @Test
    void theRealJavaThisBuildRunsOnResolvesToFeatureVersion25() {
        Optional<Path> java = FnHostLauncher.DEFAULT_JAVA_RESOLVER.resolve();
        assertThat(java).as("JAVA_HOME must be set for this build").isPresent();

        OptionalInt version = FnHostLauncher.DEFAULT_JAVA_VERSION_RESOLVER.featureVersion(java.get());

        assertThat(version).hasValue(25);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void awaitFile(Path file, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) return;
            Thread.sleep(20);
        }
        assertThat(Files.exists(file)).as(file + " must appear within " + timeout).isTrue();
    }

    private static Map<String, String> readEnvFile(Path file) throws IOException {
        var map = new java.util.LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            map.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return map;
    }

    /// A `/bin/sh` script standing in for `java`: on invocation it writes its
    /// argv and a fixed set of `FC_FN_*`/`FC_METRICS_PORT`/`FLOWCATALYST_DEV_MODE`
    /// env values next to the jar path it was given ($2), traps TERM (writes
    /// a marker and exits 0 instead of needing SIGKILL), then parks.
    private static Path writeFakeJavaScript(Path dir) throws IOException {
        Path script = dir.resolve("fake-java.sh");
        String body = """
                #!/bin/sh
                JAR="$2"
                DIR=$(dirname "$JAR")
                : > "$DIR/argv.txt"
                for a in "$@"; do printf '%s\\n' "$a" >> "$DIR/argv.txt"; done
                : > "$DIR/env.txt"
                for name in FC_FN_POOL FC_FN_PLATFORM_URL FC_FN_CLIENT_ID FC_FN_CLIENT_SECRET FC_FN_PORT \\
                            FC_FN_CACHE_DIR FC_FN_SIGNATURES FC_METRICS_PORT FLOWCATALYST_DEV_MODE FC_FN_METRICS_PORT; do
                  if env | grep -q "^${name}="; then
                    val=$(eval echo "\\$$name")
                  else
                    val="<UNSET>"
                  fi
                  printf '%s=%s\\n' "$name" "$val" >> "$DIR/env.txt"
                done
                trap "echo TERM > '$DIR/term.txt'; exit 0" TERM
                touch "$DIR/started.txt"
                while true; do sleep 1; done
                """;
        Files.writeString(script, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }
}
