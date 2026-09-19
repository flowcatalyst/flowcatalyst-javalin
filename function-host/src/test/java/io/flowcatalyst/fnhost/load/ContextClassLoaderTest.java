package io.flowcatalyst.fnhost.load;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Invocation;
import io.flowcatalyst.function.Result;

import static io.flowcatalyst.fnhost.load.TestSupport.ADDRESS;
import static io.flowcatalyst.fnhost.load.TestSupport.context;
import static io.flowcatalyst.fnhost.load.TestSupport.invocation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// L6 (`docs/spec/function-host-core.md` §3): [LoadedFunction] sets the
/// calling thread's context class loader to the function's own loader for
/// `init`, `handle` (normal return and throw) and `stop`, and restores the
/// previous one in every case. Mutant: restore only on the normal-return
/// path — killed by [#restoredAfterHandleThrows] and
/// [#loaderIsSetDuringStopAndRestoredAfter].
class ContextClassLoaderTest {

    private URLClassLoader baseline;
    private URLClassLoader functionLoader;

    @BeforeEach
    void setUp() {
        baseline = new URLClassLoader("baseline", new URL[0], null);
        Thread.currentThread().setContextClassLoader(baseline);
        functionLoader = new URLClassLoader("function", new URL[0], null);
    }

    @AfterEach
    void tearDown() throws Exception {
        functionLoader.close();
        baseline.close();
    }

    @Test
    void loaderIsSetDuringHandleAndRestoredAfterNormalReturn() throws Exception {
        RecordingFunction fn = new RecordingFunction();
        LoadedFunction loaded = new LoadedFunction(fn, functionLoader, ADDRESS, 1);

        Result result = loaded.invoke(invocation(), context());

        assertThat(result).isInstanceOf(io.flowcatalyst.function.Ack.class);
        assertThat(fn.duringHandle).isSameAs(functionLoader);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(baseline);
    }

    @Test
    void restoredAfterHandleThrows() {
        RecordingFunction fn = new RecordingFunction();
        fn.throwOnHandle = true;
        LoadedFunction loaded = new LoadedFunction(fn, functionLoader, ADDRESS, 1);

        assertThatThrownBy(() -> loaded.invoke(invocation(), context())).isInstanceOf(RuntimeException.class);

        assertThat(fn.duringHandle).isSameAs(functionLoader);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(baseline);
    }

    @Test
    void loaderIsSetDuringInitAndRestoredAfter() throws Exception {
        RecordingFunction fn = new RecordingFunction();
        LoadedFunction loaded = new LoadedFunction(fn, functionLoader, ADDRESS, 1);

        loaded.init(context());

        assertThat(fn.duringInit).isSameAs(functionLoader);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(baseline);
    }

    @Test
    void loaderIsSetDuringStopAndRestoredAfter() {
        RecordingFunction fn = new RecordingFunction();
        LoadedFunction loaded = new LoadedFunction(fn, functionLoader, ADDRESS, 1);

        loaded.close();

        assertThat(fn.duringStop).isSameAs(functionLoader);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(baseline);
    }

    private static final class RecordingFunction implements Function {
        volatile ClassLoader duringInit;
        volatile ClassLoader duringHandle;
        volatile ClassLoader duringStop;
        volatile boolean throwOnHandle;

        @Override
        public void init(FunctionContext ctx) {
            duringInit = Thread.currentThread().getContextClassLoader();
        }

        @Override
        public Result handle(Invocation in, FunctionContext ctx) {
            duringHandle = Thread.currentThread().getContextClassLoader();
            if (throwOnHandle) {
                throw new RuntimeException("boom");
            }
            return Result.ack();
        }

        @Override
        public void stop() {
            duringStop = Thread.currentThread().getContextClassLoader();
        }
    }
}
