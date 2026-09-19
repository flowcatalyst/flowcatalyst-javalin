package io.flowcatalyst.fnhost.load;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.flowcatalyst.fnhost.load.TestSupport.ADDRESS;
import static io.flowcatalyst.fnhost.load.TestSupport.context;
import static io.flowcatalyst.fnhost.load.TestSupport.isAck;
import static io.flowcatalyst.fnhost.load.TestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/// L2 (`docs/spec/function-host-core.md` §3): a function bundling its own
/// `com.example.lib.Version` (v2, with a method v1 lacks) is not shadowed
/// by the **host's** v1 copy of the same class name, on
/// `function-host`'s own test class path ([com.example.lib.Version]).
/// `Class.getClassLoader()` for the type the function sees is the
/// function's own loader, and the v2-only method is callable.
///
/// Mutant L1 (replace [ApiOnlyParentLoader] with the host application
/// loader as the function's parent) kills this: the host's v1 wins
/// parent-first delegation, `v2Only()` does not exist on it, and the
/// invocation ends with a `Result.fail` naming a `NoSuchMethodError`
/// instead of an ack.
class ShadowedClassTest {

    @Test
    void functionSeesItsOwnBundledVersionNotTheHosts(@TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("com.example.lib.Version", """
                        package com.example.lib;
                        public final class Version {
                            public String v2Only() { return "v2"; }
                        }
                        """)
                .source("fixture.l2.ShadowProbe", """
                        package fixture.l2;
                        import com.example.lib.Version;
                        import io.flowcatalyst.function.*;
                        public final class ShadowProbe implements Function {
                            public Result handle(Request in, FunctionContext ctx) {
                                try {
                                    String v = new Version().v2Only();
                                    return "v2".equals(v) ? Result.ack() : Result.fail("unexpected:" + v);
                                } catch (Throwable t) {
                                    return Result.fail("v2Only not callable: " + t);
                                }
                            }
                        }
                        """)
                .build(TestSupport.tempJar(dir, "shadow"));

        JvmFunctionLoader loader = new JvmFunctionLoader();
        LoadOutcome outcome = loader.load(jar, "fixture.l2.ShadowProbe", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Loaded.class);
        try (LoadedFunction function = ((Loaded) outcome).function()) {
            var result = function.invoke(request(), context());
            assertThat(isAck(result)).as("the function must see its own bundled v2, not the host's v1").isTrue();
        }
    }
}
