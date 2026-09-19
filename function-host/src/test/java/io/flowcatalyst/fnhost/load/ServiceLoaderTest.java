package io.flowcatalyst.fnhost.load;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.flowcatalyst.fnhost.load.TestSupport.ADDRESS;
import static io.flowcatalyst.fnhost.load.TestSupport.context;
import static io.flowcatalyst.fnhost.load.TestSupport.failReason;
import static io.flowcatalyst.fnhost.load.TestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/// L5 (`docs/spec/function-host-core.md` §3): `ServiceLoader.load(X.class)`
/// without a loader argument, called from inside a function, finds the
/// function's own provider and not [HostSupplier] — the host's provider
/// for the same interface, registered under
/// `META-INF/services/java.util.function.Supplier` on `function-host`'s
/// own test class path.
///
/// This is really two mechanisms working together: [LoadedFunction#invoke]
/// sets the thread's context class loader to the function's loader (so
/// `ServiceLoader.load` with no loader argument resolves through it), and
/// [ApiOnlyParentLoader#getResources] returns nothing for
/// `META-INF/services/*` (so the function's `URLClassLoader` only ever
/// finds its own jar's registrations, never the host's). Its own mutant:
/// dropping the context-class-loader switch in [LoadedFunction] makes
/// `ServiceLoader.load` fall back to whatever loader the JVM started the
/// test with, which — like the test's own direct `Class.forName` in L3 —
/// can see the host's registration.
class ServiceLoaderTest {

    @Test
    void unqualifiedServiceLoaderFindsOnlyTheFunctionsOwnProvider(@TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("fixture.l5.FunctionSupplier", """
                        package fixture.l5;
                        import java.util.function.Supplier;
                        public final class FunctionSupplier implements Supplier<String> {
                            public String get() { return "function"; }
                        }
                        """)
                .source("fixture.l5.ServiceLoaderProbe", """
                        package fixture.l5;
                        import io.flowcatalyst.function.*;
                        import java.util.ServiceLoader;
                        import java.util.function.Supplier;
                        public final class ServiceLoaderProbe implements Function {
                            public Result handle(Request in, FunctionContext ctx) {
                                StringBuilder found = new StringBuilder();
                                for (Supplier<?> s : ServiceLoader.load(Supplier.class)) {
                                    if (found.length() > 0) found.append(',');
                                    found.append(s.get());
                                }
                                return Result.fail(found.toString());
                            }
                        }
                        """)
                .entry("META-INF/services/java.util.function.Supplier", "fixture.l5.FunctionSupplier\n")
                .build(TestSupport.tempJar(dir, "serviceloader"));

        JvmFunctionLoader loader = new JvmFunctionLoader();
        LoadOutcome outcome = loader.load(jar, "fixture.l5.ServiceLoaderProbe", ADDRESS, 1);
        assertThat(outcome).isInstanceOf(Loaded.class);

        try (LoadedFunction function = ((Loaded) outcome).function()) {
            var result = function.invoke(request(), context());
            assertThat(failReason(result)).isEqualTo("function");
        }
    }
}
