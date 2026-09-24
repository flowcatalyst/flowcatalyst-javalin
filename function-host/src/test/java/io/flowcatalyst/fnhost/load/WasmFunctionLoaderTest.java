package io.flowcatalyst.fnhost.load;

import com.sun.net.httpserver.HttpServer;
import io.flowcatalyst.fnhost.context.ContextFactory;
import io.flowcatalyst.fnhost.context.InvocationDeadline;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.reconcile.FakeControlPlane;
import io.flowcatalyst.fnhost.wasm.WasmFixtures;
import io.flowcatalyst.fnhost.wasm.WasmFunction;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [WasmFunctionLoader] (`docs/spec/function-wasm-runtime.md` §2, §6 tests 5, 7
/// and 8): every Wasm refusal [Reason] from a WAT fixture assembled at test time
/// (one mutant per check), unload closing the instance pool, and the outbound
/// HTTP call's invocation-deadline cap — each on the real loader, with a real
/// [io.flowcatalyst.fnhost.context.HostFunctionContext].
class WasmFunctionLoaderTest {

    private static final FunctionAddress ADDRESS = FunctionAddress.parse("wasm.svc.fn");
    private static final String HANDLE = "(func (export \"handle\"))";

    // ── test 5: each refusal Reason from its WAT fixture ──────────────────

    @Test
    void garbageBytesAreWasmInvalid(@TempDir Path dir) throws Exception {
        Path notWasm = dir.resolve("garbage.wasm");
        Files.write(notWasm, "this is not a wasm module".getBytes(StandardCharsets.UTF_8));

        LoadOutcome outcome = load(notWasm, "handle", 64);

        assertThat(outcome).isInstanceOfSatisfying(Refused.class,
                r -> assertThat(r.reason()).as("mutant: skip the parse check").isEqualTo(Reason.WASM_INVALID));
    }

    @Test
    void anUnreadableArtifactIsWasmInvalidNotAnException(@TempDir Path dir) {
        LoadOutcome outcome = load(dir.resolve("missing.wasm"), "handle", 64);

        assertThat(outcome).isInstanceOfSatisfying(Refused.class,
                r -> assertThat(r.reason()).isEqualTo(Reason.WASM_INVALID));
    }

    /// A pinned table (`CONVENTIONS.md` §8): which imports a module may have.
    /// Only functions, only from the three namespaces the host provides, and in
    /// `extism:host/user` only the host's own names.
    @ParameterizedTest(name = "{0}: {1}::{2} ({3})")
    @CsvSource({
            // rule,                   module,                   name,           kind,   allowed
            "wasi,                     wasi_snapshot_preview1,   fd_write,       func,   true",
            "extism kernel,            extism:host/env,          alloc,          func,   true",
            "host function,            extism:host/user,         fc_secret_get,  func,   true",
            "host function,            extism:host/user,         fc_emit_event,  func,   true",
            "host function (W4),       extism:host/user,         fc_db_query,    func,   true",
            "host function (W4),       extism:host/user,         fc_db_execute,  func,   true",
            "host function (W4),       extism:host/user,         fc_db_begin,    func,   true",
            "host function (W4),       extism:host/user,         fc_db_commit,   func,   true",
            "host function (W4),       extism:host/user,         fc_db_rollback, func,   true",
            "foreign namespace,        env,                      evil,           func,   false",
            "foreign namespace,        wasi_snapshot_preview2,   fd_write,       func,   false",
            "near-miss namespace,      extism:host/users,        fc_secret_get,  func,   false",
            "unknown host function,    extism:host/user,         fc_db_drop,     func,   false",
            "non-function import,      extism:host/env,          memory,         memory, false",
            "non-function import,      wasi_snapshot_preview1,   g,              global, false",
    })
    void importsOutsideTheHostsNamespacesAreRefusedAndTheDetailNamesTheImport(
            String rule, String module, String name, String kind, boolean allowed, @TempDir Path dir) {
        String importDecl = switch (kind) {
            case "func" -> "(func)";
            case "memory" -> "(memory 1)";
            case "global" -> "(global i32)";
            default -> throw new IllegalArgumentException(kind);
        };
        Path wasm = WasmFixtures.wat(dir, "imports", "(module (import \"" + module + "\" \"" + name + "\" "
                + importDecl + ") " + HANDLE + ")");

        LoadOutcome outcome = load(wasm, "handle", 64);

        if (allowed) {
            assertThat(outcome).as(rule).isInstanceOf(Loaded.class);
            ((Loaded) outcome).function().close();
        } else {
            assertThat(outcome).as("mutant: skip the import check (" + rule + ")")
                    .isInstanceOfSatisfying(Refused.class, r -> {
                        assertThat(r.reason()).isEqualTo(Reason.WASM_IMPORT_NOT_ALLOWED);
                        assertThat(r.detail()).startsWith(module + "::" + name);
                    });
        }
    }

    @Test
    void aModuleWithoutTheEntrypointExportIsRefused(@TempDir Path dir) {
        Path wasm = WasmFixtures.wat(dir, "no-entry", "(module (func (export \"other\")) (memory (export \"handle\") 1))");

        LoadOutcome outcome = load(wasm, "handle", 64);

        assertThat(outcome).as("mutant: skip the entrypoint check — a memory named 'handle' is not a function")
                .isInstanceOfSatisfying(Refused.class, r -> {
                    assertThat(r.reason()).isEqualTo(Reason.WASM_ENTRYPOINT_NOT_EXPORTED);
                    assertThat(r.detail()).isEqualTo("handle");
                });
    }

    @Test
    void aDeclaredMinimumMemoryOverTheCapIsRefusedAndOneExactlyAtTheCapLoads(@TempDir Path dir) {
        // 64 MiB = 1024 pages of 64 KiB.
        Path over = WasmFixtures.wat(dir, "over", "(module (memory 1025) " + HANDLE + ")");
        Path atCap = WasmFixtures.wat(dir, "at-cap", "(module (memory 1024) " + HANDLE + ")");

        assertThat(load(over, "handle", 64)).as("mutant: skip the memory check")
                .isInstanceOfSatisfying(Refused.class, r -> {
                    assertThat(r.reason()).isEqualTo(Reason.WASM_MEMORY_OVER_CAP);
                    assertThat(r.detail()).contains("1025 pages").contains("1024 pages");
                });
        LoadOutcome ok = load(atCap, "handle", 64);
        assertThat(ok).as("mutant: >= instead of > — a module exactly at its cap must load").isInstanceOf(Loaded.class);
        ((Loaded) ok).function().close();
    }

    @Test
    void theCommittedGuestLoadsWithoutCreatingAnInstance(@TempDir Path dir) {
        LoadOutcome outcome = load(WasmFixtures.guest(dir), "echo", 16);

        assertThat(outcome).isInstanceOf(Loaded.class);
        LoadedFunction fn = ((Loaded) outcome).function();
        assertThat(fn.loaderForTest()).as("a Wasm version has no class loader of its own to set").isNull();
        assertThat(wasm(fn).liveInstances()).as("instances are created lazily, by calls").isZero();
        fn.close();
    }

    // ── spec §3: compiled once per version, shared by every instance ──────

    @Test
    void everyInstanceOfAVersionSharesItsCompiledCodeInsteadOfCompilingItsOwn(@TempDir Path dir) throws Exception {
        Path guest = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("busy", 4, 5000, 16);
        // A first version pays every one-time cost of this path (JDK, runtime and test-harness
        // classes, the concurrent calls themselves), so the count below sees only what the
        // second version's extra instances define.
        LoadedFunction warmUp = initialised(guest, manifest);
        fourOverlappingCalls(warmUp);
        warmUp.close();

        LoadedFunction fn = initialised(guest, manifest); // compiled here, at load
        WasmFunction wasm = wasm(fn);
        assertThat(fn.invoke(request(Map.of("ms", List.of("1"))), fn.context()).status()).isEqualTo(200);

        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        long before = classes.getTotalLoadedClassCount();
        fourOverlappingCalls(fn); // the pool must create three more instances
        long defined = classes.getTotalLoadedClassCount() - before;

        assertThat(wasm.liveInstances()).as("three more instances were made").isEqualTo(4);
        assertThat(defined)
                .as("mutant: let each instance compile the module (and the kernel) again — every one "
                        + "defines its own machine classes")
                .isLessThan(5);
        fn.close();
    }

    private static LoadedFunction initialised(Path guest, Manifest manifest) throws Exception {
        LoadedFunction fn = loaded(guest, manifest);
        FunctionContext ctx = context(guest, manifest, fn);
        fn.attachContext(ctx);
        fn.init(ctx);
        return fn;
    }

    private static void fourOverlappingCalls(LoadedFunction fn) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(executor.submit(() -> fn.invoke(request(Map.of("ms", List.of("300"))), fn.context())));
            }
            for (Future<Result> call : calls) {
                assertThat(call.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
            }
        }
    }

    // ── test 8: unload closes the pool ────────────────────────────────────

    @Test
    void unloadReleasesEveryInstanceAndALaterCallIsNotServed(@TempDir Path dir) throws Exception {
        Path guest = WasmFixtures.guest(dir);
        Manifest manifest = WasmFixtures.manifest("echo", 4, 5000, 16);
        LoadedFunction fn = loaded(guest, manifest);
        FunctionContext ctx = context(guest, manifest, fn);
        fn.attachContext(ctx);
        fn.init(ctx);
        WasmFunction wasm = wasm(fn);

        assertThat(fn.invoke(request(), ctx).status()).isEqualTo(200);
        assertThat(wasm.liveInstances()).isEqualTo(1);
        assertThat(wasm.idleInstances()).isEqualTo(1);

        fn.close();

        assertThat(wasm.liveInstances()).as("mutant: stop() leaves the pool open").isZero();
        assertThat(wasm.idleInstances()).isZero();
        assertThatThrownBy(() -> wasm.handle(request(), ctx))
                .as("the pool itself refuses, not only LoadedFunction's own closed check")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fn.invoke(request(), ctx)).isInstanceOf(IllegalStateException.class);
    }

    // ── test 7 (HTTP): the outbound call respects the invocation deadline ──

    @Test
    void anOutboundCallIsCappedAtTheInvocationDeadlineAndTheGuestSeesTheTimeout(@TempDir Path dir)
            throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        slow.start();
        try {
            Path guest = WasmFixtures.guest(dir);
            Manifest manifest = WasmFixtures.manifest("http", 2, 5000, 16,
                    "[{\"path\":\"/*\",\"auth\":\"none\"}]", List.of(), List.of(), List.of("127.0.0.1"));
            LoadedFunction fn = loaded(guest, manifest);
            FunctionContext ctx = context(guest, manifest, fn);
            fn.attachContext(ctx);
            fn.init(ctx);
            String url = "http://127.0.0.1:" + slow.getAddress().getPort() + "/slow";
            Request request = request(Map.of("url", List.of(url)));

            // No listener here, so nothing interrupts: only the deadline cap can end the call early.
            long start = System.nanoTime();
            Result result = ScopedValue.where(InvocationDeadline.CURRENT, Instant.now().plusMillis(300))
                    .call(() -> fn.invoke(request, ctx));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(result.status()).isEqualTo(200);
            JsonNode seen = Json.MAPPER.readTree(result.body());
            assertThat(seen.path("status").asInt())
                    .as("mutant: call out without the host's deadline-capped caller — the guest would wait "
                            + "the full 3 s and see 200: " + seen)
                    .isEqualTo(0);
            assertThat(seen.path("body").asString()).contains("timed out");
            assertThat(elapsed).as("capped at the 300 ms left, not the server's 3 s").isLessThan(Duration.ofMillis(1500));
            fn.close();
        } finally {
            slow.stop(0);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static LoadOutcome load(Path wasm, String entrypoint, int wasmMemoryMb) {
        return new WasmFunctionLoader().load(wasm, WasmFixtures.manifest(entrypoint, 2, 5000, wasmMemoryMb),
                ADDRESS, 1);
    }

    private static LoadedFunction loaded(Path wasm, Manifest manifest) {
        LoadOutcome outcome = new WasmFunctionLoader().load(wasm, manifest, ADDRESS, 1);
        assertThat(outcome).isInstanceOf(Loaded.class);
        return ((Loaded) outcome).function();
    }

    private static WasmFunction wasm(LoadedFunction fn) {
        return (WasmFunction) fn.functionForTest();
    }

    /// The real [io.flowcatalyst.fnhost.context.HostFunctionContext] a version
    /// gets at load, from a desired-state entry for `wasm`.
    private static FunctionContext context(Path wasm, Manifest manifest, LoadedFunction token) throws Exception {
        DesiredDocument.Entry entry = new DesiredDocument.Entry(ADDRESS, "fnc_w", "w1", 1,
                DesiredDocument.Role.LIVE, DesiredDocument.Mode.LAZY, digest(wasm), wasm.toUri().toString(), null,
                null, manifest, null, null, null, Map.of(), Map.of(), List.of(), List.of());
        return ContextFactory.production(4, new FakeControlPlane(), "host-1").build(entry, token);
    }

    private static Digest digest(Path file) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return Digest.parse("sha256:" + HexFormat.of().formatHex(md.digest(Files.readAllBytes(file))));
    }

    private static Request request() {
        return request(Map.of());
    }

    private static Request request(Map<String, List<String>> query) {
        return new Request(new io.flowcatalyst.function.FunctionAddress("wasm", "svc", "fn"), 1, "inv-1", "GET",
                "/x", null, null, Map.of(), query, Map.of(), new byte[0], "127.0.0.1", Caller.Anonymous.INSTANCE);
    }
}
