package io.flowcatalyst.fcdev;

import io.flowcatalyst.fnhost.FnHost;
import io.flowcatalyst.fnhost.reconcile.HostEnv;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.artifact.SignaturesMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/// Starts fcdev's function host beside the platform (spec
/// `docs/spec/function-developer-surface.md` §1). Which branch runs is
/// decided by an injectable `isNative` predicate so both are testable from a
/// plain JVM test — `fcdev start` in production only ever takes the JVM
/// branch on an ordinary JVM and the child-process branch inside a GraalVM
/// native `fcdev` binary:
///
/// - **JVM branch (`InProcess`)**: builds [HostEnv] directly from
///   [Settings] and starts `io.flowcatalyst.fnhost.FnHost` in THIS process —
///   fcdev's class path holds the whole server, so the function loader's
///   filtering parent loader is exercised exactly as production depends on
///   it (a published function cannot load `io.flowcatalyst.server.Platform`
///   or `io.flowcatalyst.fcdev.FcDev`).
/// - **Native branch (`ChildProcess`)**: a native `fcdev` binary embeds no
///   JVM class loader a function could run inside, so it shells out to
///   `java -jar <host jar>` instead, with the host's own `FC_FN_*`
///   environment variables (never round-tripped through fcdev's in-process
///   `DevEnv`), output relayed line-by-line with a `[fn-host] ` prefix.
/// - **`Disabled`**: functions are on but no JDK, no JDK new enough
///   ([#MIN_JAVA_FEATURE_VERSION] — the host jar is release 25 +
///   `--enable-preview`; an older `java` dies in the child with
///   `UnsupportedClassVersionError` instead, a confusing failure `fcdev
///   start` never sees), or no host jar could be found — logged as ONE
///   warning naming exactly what was looked for and every remedy. `fcdev
///   start` still succeeds: a developer not writing functions must not need
///   a JDK.
public final class FnHostLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(FnHostLauncher.class);

    private FnHostLauncher() {
    }

    /// Everything the launcher needs, independent of which branch runs.
    ///
    /// @param pool            the manifest pool label this host serves (fcdev: `default`)
    /// @param platformUrl     the platform's own base URL (the REAL, bound API
    ///                        port — resolved after `Server#start`, never the
    ///                        `--api-port` flag verbatim, which may be `0`)
    /// @param hostClientId    `fcdev-fn-host`'s client id ([FunctionDevBootstrap])
    /// @param hostClientSecret `fcdev-fn-host`'s freshly minted secret
    /// @param port            the function listener's bind port (`--fn-port` / `FC_FN_PORT`)
    /// @param publicPort      the PUBLIC listener's bind port (`--fn-public-port` /
    ///                        `FC_FN_PUBLIC_PORT`, spec `function-public-routes.md`
    ///                        §5) — [io.flowcatalyst.fnhost.reconcile.HostEnv#PUBLIC_PORT_DISABLED]
    ///                        starts no public listener at all
    /// @param metricsPort     the host's own observability port (`FC_FN_METRICS_PORT`)
    /// @param cacheDir        the host's artifact cache directory
    /// @param hostJar         the child-process branch's exec jar, or `null`
    ///                        when none could be resolved (`--fn-host-jar` /
    ///                        `FC_FN_HOST_JAR`, else `fc-fnhost.jar` beside
    ///                        the fcdev binary)
    public record Settings(String pool, String platformUrl, String hostClientId, String hostClientSecret,
                            int port, int publicPort, int metricsPort, Path cacheDir, Path hostJar) {
        public Settings {
            Objects.requireNonNull(pool, "pool");
            Objects.requireNonNull(platformUrl, "platformUrl");
            Objects.requireNonNull(hostClientId, "hostClientId");
            Objects.requireNonNull(hostClientSecret, "hostClientSecret");
            Objects.requireNonNull(cacheDir, "cacheDir");
        }

        /// [HostEnv] for the JVM branch — built directly, never round-tripped
        /// through environment variables (the JVM branch never spawns a
        /// process, so there is no environment to build). Signatures are
        /// hard OFF (fcdev is dev-mode by definition, spec §1); the host id
        /// is derived from this JVM's own pid so two fcdev instances never
        /// collide. `trustedProxies` is the production default (RFC 1918 +
        /// loopback) — fcdev's public listener is only ever reached over
        /// loopback in the dev loop, so this is never exercised in practice.
        HostEnv toHostEnv() {
            return new HostEnv(new DnsLabel(pool), platformUrl, hostClientId, hostClientSecret,
                    "fcdev-" + ProcessHandle.current().pid(),
                    Signatures.resolve(SignaturesMode.OFF, true), 200, cacheDir,
                    port, 512, 10, metricsPort, false, 16, publicPort,
                    io.flowcatalyst.fnhost.route.TrustedProxies.DEFAULT);
        }

        /// The child process's environment: the host's OWN variable names
        /// ([HostEnv#load]) — `FC_FN_POOL`, `FC_FN_PLATFORM_URL`,
        /// `FC_FN_CLIENT_ID`, `FC_FN_CLIENT_SECRET`, `FC_FN_PORT`,
        /// `FC_FN_PUBLIC_PORT`, `FC_FN_CACHE_DIR`, `FC_FN_SIGNATURES=off` —
        /// plus `FC_METRICS_PORT` (the host's own observability port;
        /// fcdev's OWN flag for this is `FC_FN_METRICS_PORT`, kept a
        /// distinct name so it can never be confused with the PLATFORM's
        /// `FC_METRICS_PORT` fcdev also sets) and `FLOWCATALYST_DEV_MODE=true`
        /// (`FC_FN_SIGNATURES=off` refuses to start without it, [Signatures#resolve]).
        Map<String, String> childProcessEnv() {
            var env = new LinkedHashMap<String, String>();
            env.put("FC_FN_POOL", pool);
            env.put("FC_FN_PLATFORM_URL", platformUrl);
            env.put("FC_FN_CLIENT_ID", hostClientId);
            env.put("FC_FN_CLIENT_SECRET", hostClientSecret);
            env.put("FC_FN_PORT", Integer.toString(port));
            env.put("FC_FN_PUBLIC_PORT", Integer.toString(publicPort));
            env.put("FC_FN_CACHE_DIR", cacheDir.toString());
            env.put("FC_FN_SIGNATURES", "off");
            env.put("FC_METRICS_PORT", Integer.toString(metricsPort));
            env.put("FLOWCATALYST_DEV_MODE", "true");
            return env;
        }
    }

    // ── result ───────────────────────────────────────────────────────────

    public sealed interface Result {
    }

    /// The JVM branch: [FnHost] running in this same process.
    public record InProcess(FnHost host) implements Result {
    }

    /// The native branch: a real child process, its output relayed
    /// line-by-line (a `[fn-host] ` prefix) on a daemon virtual thread.
    public static final class ChildProcess implements Result {
        private final Process process;

        ChildProcess(Process process) {
            this.process = process;
        }

        public Process process() {
            return process;
        }
    }

    /// Functions are on but the host could not be started — `fcdev start`
    /// still succeeds; `reason` is the same text already logged as the one
    /// WARN, so a caller (a test, the banner) can act on it without
    /// re-parsing logs.
    public record Disabled(String reason) implements Result {
    }

    // ── seams ────────────────────────────────────────────────────────────

    /// Whether THIS process is a GraalVM native image — `UpgradeCommand`'s
    /// own check (`org.graalvm.nativeimage.imagecode`).
    @FunctionalInterface
    public interface IsNative {
        boolean test();
    }

    /// The child-process branch's `java` lookup — production:
    /// `$JAVA_HOME/bin/java`, else `java` resolved from `PATH`.
    @FunctionalInterface
    public interface JavaResolver {
        Optional<Path> resolve();
    }

    /// The child-process branch's process start — production wraps
    /// [ProcessBuilder] directly; a test may substitute a seam that never
    /// forks a real process.
    @FunctionalInterface
    public interface ProcessStarter {
        Process start(List<String> command, Map<String, String> env) throws IOException;
    }

    /// The resolved `java`'s feature version (`java.specification.version` —
    /// `"25"`, or `"1.8"`-style for Java 8 and earlier, normalised to the
    /// int), so [#launchChildProcess] can refuse a `java` too old for the
    /// function host (release 25 + `--enable-preview`, `CONVENTIONS.md` §8)
    /// BEFORE spawning it — a Java 21 launched anyway dies in the child with
    /// `UnsupportedClassVersionError`, a confusing failure `fcdev start`
    /// itself never sees. Empty when the version could not be determined
    /// (spawn failure, unparseable output) — treated the same as "too old":
    /// refuse rather than risk it. Injectable so a test drives the "found
    /// but too old" branch with a fake, never a real second JVM.
    @FunctionalInterface
    public interface JavaVersionResolver {
        OptionalInt featureVersion(Path java);
    }

    public static final IsNative DEFAULT_IS_NATIVE =
            () -> System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    public static final JavaResolver DEFAULT_JAVA_RESOLVER = FnHostLauncher::defaultJavaResolver;

    public static final ProcessStarter DEFAULT_PROCESS_STARTER = FnHostLauncher::defaultProcessStarter;

    public static final JavaVersionResolver DEFAULT_JAVA_VERSION_RESOLVER = FnHostLauncher::defaultJavaVersionResolver;

    /// The function host jar is built for release 25 with `--enable-preview`
    /// (`CONVENTIONS.md` §8) — a preview class file refuses to load on any
    /// OTHER feature release, older or newer, so this is an exact minimum,
    /// not "25 or newer" in the usual sense; kept as `<` here since a NEWER
    /// major (26+) is a distinct, future problem (a recompiled host jar),
    /// not one `fcdev start` should silently paper over today.
    static final int MIN_JAVA_FEATURE_VERSION = 25;

    private static Optional<Path> defaultJavaResolver() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path candidate = Path.of(javaHome, "bin", executableName());
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path candidate = Path.of(dir, executableName());
                if (Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static Process defaultProcessStarter(List<String> command, Map<String, String> env) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static String executableName() {
        return isWindows() ? "java.exe" : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /// Runs `<java> -XshowSettings:properties -version` and reads
    /// `java.specification.version` off its output — chosen over parsing
    /// `-version`'s banner line because that line's SHAPE is vendor-specific
    /// (`openjdk version "25" …` vs `java version "25.0.4" …` vs old
    /// `java version "1.8.0_…"`), while every JDK prints the
    /// `-XshowSettings:properties` block in the same `key = value` shape and
    /// `java.specification.version` is the exact field the JLS defines for
    /// "which release this is" (`"25"`, or `"1.8"` pre-JEP-223). Both flags
    /// write to stderr, so the process is started with `redirectErrorStream`
    /// like the production [ProcessStarter] does. A 10s timeout / spawn
    /// failure / unparseable output all resolve to [OptionalInt#empty] —
    /// the same "refuse" outcome as a version that is simply too old.
    private static OptionalInt defaultJavaVersionResolver(Path java) {
        try {
            Process process = new ProcessBuilder(java.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return OptionalInt.empty();
            }
            return parseFeatureVersion(output);
        } catch (IOException e) {
            return OptionalInt.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalInt.empty();
        }
    }

    /// `java.specification.version` → its feature-version int (`"25"` → 25,
    /// legacy `"1.8"` → 8). Package-visible for direct testing against
    /// captured `-XshowSettings:properties` output without spawning a real
    /// JVM.
    static OptionalInt parseFeatureVersion(String propertiesOutput) {
        for (String line : propertiesOutput.lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("java.specification.version")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String value = trimmed.substring(eq + 1).strip();
            String feature = value.startsWith("1.") ? value.substring(2) : value;
            int dot = feature.indexOf('.');
            if (dot >= 0) {
                feature = feature.substring(0, dot);
            }
            try {
                return OptionalInt.of(Integer.parseInt(feature));
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    // ── launch ───────────────────────────────────────────────────────────

    public static Result launch(Settings settings, IsNative isNative, JavaResolver javaResolver,
            JavaVersionResolver javaVersionResolver, ProcessStarter starter) {
        if (!isNative.test()) {
            FnHost host = new FnHost(settings.toHostEnv());
            host.start();
            LOG.atInfo().setMessage("function host started in-process")
                    .addKeyValue("port", settings.port())
                    .addKeyValue("metrics_port", settings.metricsPort())
                    .log();
            return new InProcess(host);
        }
        return launchChildProcess(settings, javaResolver, javaVersionResolver, starter);
    }

    private static Result launchChildProcess(Settings settings, JavaResolver javaResolver,
            JavaVersionResolver javaVersionResolver, ProcessStarter starter) {
        Optional<Path> java = javaResolver.resolve();
        if (java.isEmpty()) {
            String reason = "no Java runtime found to run the function host: looked for $JAVA_HOME/bin/java and "
                    + "`java` on PATH — set JAVA_HOME, put `java` on PATH, or start with --no-functions to skip "
                    + "the function host";
            LOG.warn(reason);
            return new Disabled(reason);
        }
        OptionalInt version = javaVersionResolver.featureVersion(java.get());
        if (version.isEmpty() || version.getAsInt() < MIN_JAVA_FEATURE_VERSION) {
            String found = version.isEmpty() ? "its version could not be determined" : "reports Java " + version.getAsInt();
            String reason = "the function host needs Java " + MIN_JAVA_FEATURE_VERSION + "+ but " + java.get() + " "
                    + found + ": install Java " + MIN_JAVA_FEATURE_VERSION + "+, set JAVA_HOME to it, "
                    + "or start with --no-functions to skip the function host";
            LOG.warn(reason);
            return new Disabled(reason);
        }
        Path jar = settings.hostJar();
        if (jar == null || !Files.isRegularFile(jar)) {
            String reason = "function host jar not found at " + jar + ": pass --fn-host-jar / FC_FN_HOST_JAR "
                    + "naming its path, place fc-fnhost.jar beside the fcdev binary, or start with --no-functions "
                    + "to skip the function host";
            LOG.warn(reason);
            return new Disabled(reason);
        }
        // The host jar is compiled with --enable-preview (CONVENTIONS.md §8) and links Endive and
        // Netty natives — the same two flags its own image entrypoint passes
        // (function-host/docker/entrypoint.sh). Without the first, the child dies on the first
        // preview-compiled class with UnsupportedClassVersionError.
        List<String> command = List.of(java.get().toString(), "--enable-preview", "--enable-native-access=ALL-UNNAMED",
                "-jar", jar.toString());
        try {
            Process process = starter.start(command, settings.childProcessEnv());
            relayOutput(process);
            LOG.atInfo().setMessage("function host started as a child process")
                    .addKeyValue("pid", process.pid())
                    .addKeyValue("jar", jar)
                    .log();
            return new ChildProcess(process);
        } catch (IOException e) {
            String reason = "could not start the function host child process: " + e.getMessage();
            LOG.atWarn().setMessage("could not start the function host child process").setCause(e).log();
            return new Disabled(reason);
        }
    }

    /// Relays the child's stdout+stderr (merged, [ProcessBuilder#redirectErrorStream])
    /// line-by-line with a `[fn-host] ` prefix, on a virtual thread — always
    /// daemon by construction, so it never keeps the JVM alive on its own.
    private static void relayOutput(Process process) {
        Thread.ofVirtual().name("fn-host-relay").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[fn-host] " + line);
                }
            } catch (IOException ignored) {
                // the process ended (or its stream closed) — nothing more to relay
            }
        });
    }

    /// Stops whatever [#launch] started, in fcdev's shutdown order BEFORE
    /// the platform (the host heartbeats `DRAINING` to a platform that is
    /// still up): in-process — `FnHost#close()`; child process —
    /// `destroy()`, wait 10s, `destroyForcibly()`.
    public static void close(Result result) {
        switch (result) {
            case InProcess(FnHost host) -> host.close();
            case ChildProcess cp -> stopChildProcess(cp.process());
            case Disabled ignored -> {
            }
        }
    }

    private static void stopChildProcess(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
