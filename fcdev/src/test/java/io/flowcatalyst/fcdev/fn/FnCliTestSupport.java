package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.DevEnv;
import io.flowcatalyst.fcdev.FcDev;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/// Drives the real `fcdev fn …` command tree (through [FcDev], exactly as a
/// developer invokes it) capturing picocli's out/err writers — the same
/// convention `FcDevCliTest` uses for the rest of the command tree.
///
/// **Sandboxing**: [io.flowcatalyst.fcdev.DevPaths#resolve] falls back to the
/// REAL `user.home` when nothing overrides it, which on macOS is NOT gated
/// by `XDG_CACHE_HOME`/`XDG_DATA_HOME` the way Linux is for the cache dir —
/// only `XDG_DATA_HOME` (the state dir `fn-cli.json` lives under) is honoured
/// on every OS. Every call here injects a fresh-per-call `XDG_DATA_HOME`
/// unless the test already set one, so a run on a real developer machine
/// that has an actual `fn-cli.json` from `fcdev start` never leaks into
/// these tests (and these tests never touch it).
final class FnCliTestSupport {

    private FnCliTestSupport() {
    }

    record Run(int exit, String out, String err) {
    }

    static Run run(Map<String, String> env, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        CommandLine cl = commandLine(env, out, err);
        int exit = cl.execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    /// Runs `fn secret set …` with `pipedValue` fed through
    /// [SecretCommand.Set]'s stdin seam (never a CLI argument — spec §4 E6),
    /// as a real piped invocation would (`console = null`).
    static Run runWithStdin(Map<String, String> env, String pipedValue, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        CommandLine cl = commandLine(env, out, err);
        var setCli = cl.getSubcommands().get("fn").getSubcommands().get("secret").getSubcommands().get("set");
        var set = (SecretCommand.Set) setCli.getCommand();
        set.console = null;
        set.stdin = new java.io.ByteArrayInputStream(pipedValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int exit = cl.execute(args);
        return new Run(exit, out.toString(), err.toString());
    }

    /// Same as {@link #run}, but returns the built [CommandLine] before
    /// executing — for tests that need to reach into a specific subcommand
    /// instance (e.g. to inject a test clock/sleeper) before calling
    /// {@code execute}.
    static CommandLine commandLine(Map<String, String> env, StringWriter out, StringWriter err) {
        CommandLine cl = FcDev.commandLine(DevEnv.of(sandboxed(env)));
        cl.setOut(new PrintWriter(out, true));
        cl.setErr(new PrintWriter(err, true));
        return cl;
    }

    private static Map<String, String> sandboxed(Map<String, String> env) {
        if (env.containsKey("XDG_DATA_HOME")) {
            return env;
        }
        var merged = new HashMap<>(env);
        merged.put("XDG_DATA_HOME", freshTempDir().toString());
        return merged;
    }

    private static java.nio.file.Path freshTempDir() {
        try {
            return Files.createTempDirectory("fcdev-fn-cli-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
