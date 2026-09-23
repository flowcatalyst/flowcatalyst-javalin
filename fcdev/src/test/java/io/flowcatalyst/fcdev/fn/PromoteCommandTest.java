package io.flowcatalyst.fcdev.fn;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// `fn promote` — spec §4 E5: "waits for READY; times out with the hosts'
/// errors". A fake status endpoint that flips state after N polls proves the
/// wait is real (mutant: promote immediately, before the version is READY).
class PromoteCommandTest {

    private Map<String, String> env(FakePlatform platform) {
        var env = new HashMap<String, String>();
        env.put("FLOWCATALYST_PLATFORM_URL", platform.baseUrl());
        env.put("FLOWCATALYST_CLIENT_ID", "id");
        env.put("FLOWCATALYST_CLIENT_SECRET", "secret");
        return env;
    }

    /// Injects a fake clock/sleeper into the REAL [PromoteCommand] instance
    /// inside the command tree, then executes — so "poll every second" never
    /// costs a real second, but the LOOP logic (poll, check, poll again)
    /// still runs for real.
    private FnCliTestSupport.Run runWithFakeTime(Map<String, String> env, java.util.function.LongSupplier clock,
                                                  java.util.function.LongConsumer sleeper, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        CommandLine cl = FnCliTestSupport.commandLine(env, out, err);
        var promoteCli = cl.getSubcommands().get("fn").getSubcommands().get("promote");
        var promote = (PromoteCommand) promoteCli.getCommand();
        promote.clockMillis = clock;
        promote.sleepMillis = sleeper;
        int exit = cl.execute(args);
        return new FnCliTestSupport.Run(exit, out.toString(), err.toString());
    }

    @Test
    void waitsForReadyThenPromotes() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger statusCalls = new AtomicInteger();
            AtomicInteger promoteCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn/status", ex -> {
                boolean ready = statusCalls.incrementAndGet() >= 3;
                FakePlatform.writeJson(ex, 200, Map.of(
                        "address", "app.svc.fn", "status", "ACTIVE",
                        "versions", java.util.List.of(Map.of("version", 1, "state", ready ? "READY" : "PUBLISHED")),
                        "hosts", java.util.List.of(), "wiring", java.util.List.of()));
            });
            platform.on("PUT", "/api/functions/app.svc.fn/aliases/live", ex -> {
                promoteCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("alias", "live", "version", 1, "versionId", "fnv_1"));
            });

            AtomicInteger fakeMillis = new AtomicInteger(0);
            var r = runWithFakeTime(env(platform), fakeMillis::get, ms -> fakeMillis.addAndGet((int) ms),
                    "fn", "promote", "app.svc.fn", "--version", "1", "--wait", "30s");

            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(statusCalls.get()).as("must actually poll more than once before READY").isGreaterThanOrEqualTo(3);
            assertThat(promoteCalls.get()).as("promote must be called exactly once, after READY").isEqualTo(1);
        }
    }

    /// Mutant: promote immediately (skip the wait). Pinned by never-READY +
    /// asserting the PUT was never called and a timeout was reported instead.
    @Test
    void timesOutAndNeverPromotesWhenNeverReady() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger promoteCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn/status", ex -> FakePlatform.writeJson(ex, 200, Map.of(
                    "address", "app.svc.fn", "status", "ACTIVE",
                    "versions", java.util.List.of(Map.of("version", 1, "state", "PUBLISHED")),
                    "hosts", java.util.List.of(Map.of("hostId", "host-1", "pool", "default", "state", "ACTIVE",
                            "lastHeartbeat", "2026-01-01T00:00:00.000000Z", "stale", false,
                            "loaded", java.util.List.of(Map.of("version", 1, "state", "FAILED", "error", "boom")))),
                    "wiring", java.util.List.of())));
            platform.on("PUT", "/api/functions/app.svc.fn/aliases/live", ex -> {
                promoteCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("alias", "live", "version", 1, "versionId", "fnv_1"));
            });

            AtomicInteger fakeMillis = new AtomicInteger(0);
            var r = runWithFakeTime(env(platform), fakeMillis::get, ms -> fakeMillis.addAndGet((int) ms),
                    "fn", "promote", "app.svc.fn", "--version", "1", "--wait", "5s");

            assertThat(r.exit()).isEqualTo(1);
            assertThat(promoteCalls.get()).as("must never promote after a timeout").isZero();
            assertThat(r.err()).contains("host-1").contains("FAILED").contains("boom");
        }
    }

    @Test
    void waitZeroSkipsPollingAndPromotesImmediately() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger statusCalls = new AtomicInteger();
            platform.on("GET", "/api/functions/app.svc.fn/status", ex -> {
                statusCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of());
            });
            platform.on("PUT", "/api/functions/app.svc.fn/aliases/live", ex ->
                    FakePlatform.writeJson(ex, 200, Map.of("alias", "live", "version", 1, "versionId", "fnv_1")));

            var r = FnCliTestSupport.run(env(platform), "fn", "promote", "app.svc.fn", "--version", "1", "--wait", "0");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(statusCalls.get()).as("--wait 0 must never poll status").isZero();
        }
    }

    /// `--alias <name>` (spec `function-zones-and-aliases.md` §6): the PUT
    /// goes to `.../aliases/qa`, never `.../aliases/live` — mutant: ignore
    /// the flag and always promote `live`.
    @Test
    void aliasFlagPromotesTheNamedAliasNotLive() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger liveCalls = new AtomicInteger();
            AtomicInteger qaCalls = new AtomicInteger();
            platform.on("PUT", "/api/functions/app.svc.fn/aliases/live", ex -> {
                liveCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("alias", "live", "version", 1, "versionId", "fnv_1"));
            });
            platform.on("PUT", "/api/functions/app.svc.fn/aliases/qa", ex -> {
                qaCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("alias", "qa", "version", 1, "versionId", "fnv_1"));
            });

            var r = FnCliTestSupport.run(env(platform), "fn", "promote", "app.svc.fn", "--version", "1",
                    "--alias", "qa", "--wait", "0");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(qaCalls.get()).as("mutant: --alias ignored, still promotes live").isEqualTo(1);
            assertThat(liveCalls.get()).as("mutant: promotes both live and the named alias").isZero();
        }
    }

    /// `--alias` omitted defaults to `live` — the pre-existing behaviour is
    /// unchanged (mutant: default to something other than `live`).
    @Test
    void aliasFlagDefaultsToLive() throws Exception {
        try (var platform = FakePlatform.start()) {
            AtomicInteger liveCalls = new AtomicInteger();
            platform.on("PUT", "/api/functions/app.svc.fn/aliases/live", ex -> {
                liveCalls.incrementAndGet();
                FakePlatform.writeJson(ex, 200, Map.of("alias", "live", "version", 1, "versionId", "fnv_1"));
            });

            var r = FnCliTestSupport.run(env(platform), "fn", "promote", "app.svc.fn", "--version", "1", "--wait", "0");
            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(liveCalls.get()).isEqualTo(1);
        }
    }
}
