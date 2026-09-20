package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The `fn` command tree's wiring: every subcommand registered, `--output`
/// parsing, and a credential-missing runtime failure staying to ONE line
/// (never a stack trace) — `docs/spec/function-developer-surface.md` §2:
/// "runtime failures print ONE line and exit 1, never a usage dump or stack
/// trace".
class FnCliHelpTest {

    @Test
    void helpListsEverySubcommand() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "--help");
        assertThat(r.exit()).isZero();
        for (var name : new String[]{"publish", "promote", "deploy", "status", "versions", "retire",
                "config", "secret", "invoke", "watch"}) {
            assertThat(r.out()).as(name).contains(name);
        }
    }

    @Test
    void fnRegisteredUnderFcDev() {
        var r = FnCliTestSupport.run(Map.of(), "--help");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("fn");
    }

    @Test
    void bareFnPrintsHelpNotAnError() {
        var r = FnCliTestSupport.run(Map.of(), "fn");
        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("publish");
    }

    @Test
    void invalidOutputModeIsAUsageError() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "--output", "xml", "status", "a.b.c");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("text").contains("json");
    }

    /// Runtime failure (missing credentials) is ONE line on stderr, exit 1 —
    /// never a stack trace / "fcdev exited with error" dump (which would mean
    /// the exception escaped [FnCommand#runSafely] into [io.flowcatalyst.fcdev.FcDev]'s
    /// own handler).
    @Test
    void missingCredentialsIsOneLineNeverAStackTrace() {
        var r = FnCliTestSupport.run(Map.of(), "fn", "status", "a.b.c");
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err().lines().count()).as(r.err()).isEqualTo(1);
        assertThat(r.err()).doesNotContain("\tat ").doesNotContain("Exception");
    }

    /// A platform error (not a network/credentials failure) is likewise one
    /// line, `code: message` — never a stack trace.
    @Test
    void platformErrorIsOneLineCodeAndMessage() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/a.b.c/status", ex ->
                    FakePlatform.writeError(ex, 404, "Function_NOT_FOUND", "function not found: a.b.c"));
            var env = new HashMap<String, String>();
            env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
            env.put("FLOWCATALYST_CLIENT_ID", "id");
            env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
            var r = FnCliTestSupport.run(env, "fn", "status", "a.b.c");
            assertThat(r.exit()).isEqualTo(1);
            assertThat(r.err().strip()).isEqualTo("Function_NOT_FOUND: function not found: a.b.c");
        }
    }

    @Test
    void jsonOutputModeAcceptsAnyCase() throws Exception {
        try (var platform = FakePlatform.start()) {
            platform.on("GET", "/api/functions/a.b.c/status", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "address", "a.b.c", "status", "ACTIVE", "versions", java.util.List.of(),
                    "hosts", java.util.List.of(), "wiring", java.util.List.of())));
            var env = new HashMap<String, String>();
            env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
            env.put("FLOWCATALYST_CLIENT_ID", "id");
            env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
            var r = FnCliTestSupport.run(env, "fn", "--output", "JSON", "status", "a.b.c");
            assertThat(r.exit()).as(r.err()).isZero();
            io.flowcatalyst.platform.shared.json.Json.MAPPER.readTree(r.out().strip()); // must parse
        }
    }
}
