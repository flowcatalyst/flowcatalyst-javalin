package io.flowcatalyst.fnhost.load;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.Result;

import static io.flowcatalyst.fnhost.load.TestSupport.ADDRESS;
import static io.flowcatalyst.fnhost.load.TestSupport.context;
import static io.flowcatalyst.fnhost.load.TestSupport.failReason;
import static io.flowcatalyst.fnhost.load.TestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/// L4 (`docs/spec/function-host-core.md` §3): two functions bundling one
/// library with a static counter each see their own count — their loaders
/// are siblings, so the JVM never treats their same-named classes as one
/// type with one set of statics.
///
/// Mutant: sharing one `URLClassLoader` between both fixtures instead of
/// giving each its own (a production mutant is not directly expressible —
/// `JvmFunctionLoader` always builds a fresh loader per `load()` call, so
/// there is no "share the loader" branch to flip). [#mutant_sharedLoaderCollapsesTheCounters]
/// demonstrates the failure mode directly by bypassing `JvmFunctionLoader`
/// and loading both fixtures' entrypoints through one shared loader,
/// showing the collision this test's own assertions would catch.
class IndependentStaticStateTest {

    private static final String COUNTER_SOURCE = """
            package fixture.l4;
            public final class Counter {
                private static int count;
                public static synchronized int increment() { return ++count; }
            }
            """;

    private static final String PROBE_SOURCE = """
            package fixture.l4;
            import io.flowcatalyst.function.*;
            public final class CounterProbe implements Function {
                public Result handle(Request in, FunctionContext ctx) {
                    return Result.fail("count=" + Counter.increment());
                }
            }
            """;

    @Test
    void twoFunctionsBundlingTheSameLibraryNameHaveIndependentStatics(@TempDir Path dir) throws Exception {
        Path jarA = FixtureJars.builder()
                .source("fixture.l4.Counter", COUNTER_SOURCE)
                .source("fixture.l4.CounterProbe", PROBE_SOURCE)
                .build(TestSupport.tempJar(dir, "counter-a"));
        Path jarB = FixtureJars.builder()
                .source("fixture.l4.Counter", COUNTER_SOURCE)
                .source("fixture.l4.CounterProbe", PROBE_SOURCE)
                .build(TestSupport.tempJar(dir, "counter-b"));

        JvmFunctionLoader loader = new JvmFunctionLoader();
        LoadOutcome outcomeA = loader.load(jarA, "fixture.l4.CounterProbe", ADDRESS, 1);
        LoadOutcome outcomeB = loader.load(jarB, "fixture.l4.CounterProbe", ADDRESS, 2);
        assertThat(outcomeA).isInstanceOf(Loaded.class);
        assertThat(outcomeB).isInstanceOf(Loaded.class);

        try (LoadedFunction functionA = ((Loaded) outcomeA).function();
                LoadedFunction functionB = ((Loaded) outcomeB).function()) {
            assertThat(countOf(functionA.invoke(request(), context()))).isEqualTo(1);
            assertThat(countOf(functionA.invoke(request(), context()))).isEqualTo(2);
            assertThat(countOf(functionB.invoke(request(), context()))).isEqualTo(1);
            assertThat(countOf(functionA.invoke(request(), context()))).isEqualTo(3);
        }
    }

    private static int countOf(Result result) {
        String reason = failReason(result);
        return Integer.parseInt(reason.substring(reason.indexOf('=') + 1));
    }

    // Demonstrates the "share one loader between them" mutant directly, since
    // JvmFunctionLoader has no such branch to flip in production code: builds both
    // fixtures' entrypoints under one shared URLClassLoader instead of JvmFunctionLoader's
    // one-per-load(), and shows the counters collide — proving the real test above can fail.
    @Test
    void mutant_sharedLoaderCollapsesTheCounters(@TempDir Path dir) throws Exception {
        Path jarA = FixtureJars.builder()
                .source("fixture.l4.Counter", COUNTER_SOURCE)
                .source("fixture.l4.CounterProbe", PROBE_SOURCE)
                .build(TestSupport.tempJar(dir, "counter-a"));
        Path jarB = FixtureJars.builder()
                .source("fixture.l4.Counter", COUNTER_SOURCE)
                .source("fixture.l4.CounterProbe", PROBE_SOURCE)
                .build(TestSupport.tempJar(dir, "counter-b"));

        java.net.URLClassLoader shared = new java.net.URLClassLoader(
                new java.net.URL[] {jarA.toUri().toURL(), jarB.toUri().toURL()}, new ApiOnlyParentLoader());
        Class<?> entrypoint = Class.forName("fixture.l4.CounterProbe", false, shared);
        Function fnA = (Function) entrypoint.getDeclaredConstructor().newInstance();
        Function fnB = (Function) entrypoint.getDeclaredConstructor().newInstance();

        int a1 = countOf(fnA.handle(request(), context()));
        int b1 = countOf(fnB.handle(request(), context()));

        assertThat(a1).isEqualTo(1);
        assertThat(b1).as("under a shared loader B's first call sees A's count, proving the real test can fail")
                .isEqualTo(2);
    }
}
