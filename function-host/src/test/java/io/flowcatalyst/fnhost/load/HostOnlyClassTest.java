package io.flowcatalyst.fnhost.load;

import java.nio.file.Path;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.flowcatalyst.fnhost.load.TestSupport.ADDRESS;
import static io.flowcatalyst.fnhost.load.TestSupport.context;
import static io.flowcatalyst.fnhost.load.TestSupport.isAck;
import static io.flowcatalyst.fnhost.load.TestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/// L3 (`docs/spec/function-host-core.md` §3): a function cannot load a
/// host-only class. The check runs **inside** the function (its own
/// `getClassLoader()`, not the test's) — the test's own loader can see
/// `io.vertx.core.Vertx`, `io.flowcatalyst.server.Platform` and
/// `tools.jackson.databind.ObjectMapper` transitively through the `server`
/// dependency, so only a check made from inside the isolated function
/// loader proves anything.
///
/// `io.flowcatalyst.server.Platform` is the target that also pins the
/// exact-package boundary on rule 2: widening `ApiOnlyParentLoader`'s API
/// package match from `io.flowcatalyst.function` to a prefix
/// `io.flowcatalyst.` would let this one class through (it shares the
/// prefix) while leaving Vert.x and Jackson refused, so this row alone
/// distinguishes "parent replaced entirely" (mutant L1) from "package match
/// widened" (a second, narrower mutant).
class HostOnlyClassTest {

    @ParameterizedTest(name = "[{index}] cannot load {0}")
    @ValueSource(strings = {"io.vertx.core.Vertx", "io.flowcatalyst.server.Platform", "tools.jackson.databind.ObjectMapper"})
    void functionCannotLoadAHostOnlyClass(String target, @TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("fixture.l3.HostOnlyProbe", """
                        package fixture.l3;
                        import io.flowcatalyst.function.*;
                        public final class HostOnlyProbe implements Function {
                            public Result handle(Request in, FunctionContext ctx) {
                                try {
                                    Class.forName("%s", false, this.getClass().getClassLoader());
                                    return Result.fail("host-only class loaded unexpectedly");
                                } catch (ClassNotFoundException e) {
                                    return Result.ack();
                                }
                            }
                        }
                        """.formatted(target))
                .build(TestSupport.tempJar(dir, "hostonly-" + target.hashCode()));

        JvmFunctionLoader loader = new JvmFunctionLoader();
        LoadOutcome outcome = loader.load(jar, "fixture.l3.HostOnlyProbe", ADDRESS, 1);

        assertThat(outcome).isInstanceOf(Loaded.class);
        try (LoadedFunction function = ((Loaded) outcome).function()) {
            var result = function.invoke(request(), context());
            assertThat(isAck(result)).as(target + " must not be loadable from inside the function").isTrue();
        }
    }
}
