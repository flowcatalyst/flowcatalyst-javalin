package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.FunctionAddress;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/// Shared fixtures for the D1 isolation tests (`docs/spec/function-host-core.md`
/// §3): a stock host-side [FunctionAddress], a throwaway [Request], and the
/// D1 [FunctionContext] stub — none of it specific to any one L-numbered
/// test.
final class TestSupport {

    static final FunctionAddress ADDRESS = FunctionAddress.parse("fixture.host.probe");
    // FunctionAddress collides in name with the platform's above — this is the API jar's
    // own deliberate second copy (docs/spec/function-host-core.md §1), so it stays qualified.
    static final io.flowcatalyst.function.FunctionAddress API_ADDRESS =
            io.flowcatalyst.function.FunctionAddress.parse(ADDRESS.render());

    private TestSupport() {
    }

    static Request request() {
        return new Request(API_ADDRESS, 1, UUID.randomUUID().toString(), "POST", "/", null, null,
                Map.of(), Map.of(), Map.of(), new byte[0], "127.0.0.1", Caller.Platform.INSTANCE);
    }

    static FunctionContext context() {
        return new UnimplementedFunctionContext(API_ADDRESS, 1);
    }

    static Path tempJar(Path dir, String name) {
        return dir.resolve(name + ".jar");
    }

    /// Extracts the `reason` a fixture handed to `Result.fail(reason)` back
    /// out of the JSON body `Result.fail` produces (`{"error":"<reason>"}"`,
    /// `docs/spec/function-invocation.md` §7) — `Result` no longer has a
    /// `Fail` subtype a test can pattern-match on; the outcome is a plain
    /// HTTP response now, so tests that used to read `((Fail) result).reason()`
    /// read the body instead. Reverses only the two escapes the fixtures in
    /// this package's reasons can ever contain (`Result`/`JsonEscape`'s own
    /// test coverage pins the full escaping contract).
    static String failReason(Result result) {
        String json = new String(result.body(), StandardCharsets.UTF_8);
        int start = json.indexOf(":\"") + 2;
        int end = json.lastIndexOf('"');
        return json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /// Whether `result` is [Result#ack()] — an ack is exactly status 200
    /// with an empty body (`docs/spec/function-invocation.md` §7); nothing
    /// else in this fixture package ever builds a bare 200.
    static boolean isAck(Result result) {
        return result.status() == 200 && result.body().length == 0;
    }
}
