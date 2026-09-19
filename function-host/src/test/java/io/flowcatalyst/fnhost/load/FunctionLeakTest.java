package io.flowcatalyst.fnhost.load;

import java.lang.ref.WeakReference;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.flowcatalyst.fnhost.load.TestSupport.ADDRESS;
import static io.flowcatalyst.fnhost.load.TestSupport.context;
import static io.flowcatalyst.fnhost.load.TestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

/// L7 (`docs/spec/function-host-core.md` §3): after load → invoke →
/// `close()` → drop every reference, the function's class loader becomes
/// collectible. The bounded `System.gc()` loop is necessary but not
/// sufficient evidence on its own — [#controlFixtureThatLeaksNeverClears]
/// is the required control: a fixture that deliberately parks its instance
/// in a JDK-owned static (`java.util.logging`'s root logger handler list)
/// must **not** clear, or [#loaderClearsAfterClose] could not fail either
/// way.
class FunctionLeakTest {

    @Test
    void loaderClearsAfterClose(@TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("fixture.l7.CleanProbe", """
                        package fixture.l7;
                        import io.flowcatalyst.function.*;
                        public final class CleanProbe implements Function {
                            public Result handle(Request in, FunctionContext ctx) { return Result.ack(); }
                        }
                        """)
                .build(TestSupport.tempJar(dir, "clean"));

        WeakReference<ClassLoader> ref = loadInvokeCloseAndReturnWeakRef(jar, "fixture.l7.CleanProbe");

        assertThat(awaitClear(ref)).as("the function's loader should become collectible after close()").isTrue();
    }

    @Test
    void controlFixtureThatLeaksNeverClears(@TempDir Path dir) throws Exception {
        Path jar = FixtureJars.builder()
                .source("fixture.l7.LeakyProbe", """
                        package fixture.l7;
                        import io.flowcatalyst.function.*;
                        import java.util.logging.Handler;
                        import java.util.logging.LogRecord;
                        import java.util.logging.Logger;
                        public final class LeakyProbe implements Function {
                            public void init(FunctionContext ctx) {
                                // Deliberately never removed: this is the control case, proving
                                // the leak test above is able to fail. A JDK-owned static (the
                                // root logger's handler list) outlives close() and this jar.
                                Logger.getLogger("").addHandler(new Handler() {
                                    public void publish(LogRecord record) {}
                                    public void flush() {}
                                    public void close() {}
                                });
                            }
                            public Result handle(Request in, FunctionContext ctx) { return Result.ack(); }
                        }
                        """)
                .build(TestSupport.tempJar(dir, "leaky"));

        WeakReference<ClassLoader> ref = loadInvokeCloseAndReturnWeakRef(jar, "fixture.l7.LeakyProbe");

        assertThat(awaitClear(ref))
                .as("the control fixture deliberately leaks via java.util.logging; it must NOT clear")
                .isFalse();
    }

    /// Method-local: every strong reference (`loader`, `outcome`, `function`)
    /// lives only in this call frame and is gone once it returns — the
    /// caller receives nothing but the `WeakReference` itself.
    private static WeakReference<ClassLoader> loadInvokeCloseAndReturnWeakRef(Path jar, String entrypoint)
            throws Exception {
        JvmFunctionLoader fnLoader = new JvmFunctionLoader();
        LoadOutcome outcome = fnLoader.load(jar, entrypoint, ADDRESS, 1);
        assertThat(outcome).isInstanceOf(Loaded.class);
        LoadedFunction function = ((Loaded) outcome).function();
        function.init(context());
        function.invoke(request(), context());
        WeakReference<ClassLoader> ref = new WeakReference<>(function.loaderForTest());
        function.close();
        return ref;
    }

    private static boolean awaitClear(WeakReference<?> ref) {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ref.get() == null;
    }
}
