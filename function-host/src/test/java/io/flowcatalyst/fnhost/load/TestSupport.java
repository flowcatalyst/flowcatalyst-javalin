package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Event;
import io.flowcatalyst.function.EventInvocation;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Invocation;
import io.flowcatalyst.platform.function.FunctionAddress;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/// Shared fixtures for the D1 isolation tests (`docs/spec/function-host-core.md`
/// §3): a stock host-side [FunctionAddress], a throwaway [Invocation], and
/// the D1 [FunctionContext] stub — none of it specific to any one L-numbered
/// test.
final class TestSupport {

    static final FunctionAddress ADDRESS = FunctionAddress.parse("fixture.host.probe");
    // FunctionAddress collides in name with the platform's above — this is the API jar's
    // own deliberate second copy (docs/spec/function-host-core.md §1), so it stays qualified.
    static final io.flowcatalyst.function.FunctionAddress API_ADDRESS =
            io.flowcatalyst.function.FunctionAddress.parse(ADDRESS.render());

    private TestSupport() {
    }

    static Invocation invocation() {
        return new EventInvocation(API_ADDRESS, UUID.randomUUID().toString(),
                new Event("evt-1", "test.event", "fixture", "subject",
                        Instant.EPOCH, "application/json", new byte[0], null, null, null, null));
    }

    static FunctionContext context() {
        return new UnimplementedFunctionContext(API_ADDRESS, 1);
    }

    static Path tempJar(Path dir, String name) {
        return dir.resolve(name + ".jar");
    }
}
