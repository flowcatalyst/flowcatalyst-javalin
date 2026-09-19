package io.flowcatalyst.fnhost.load;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.FunctionAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// L11 (`docs/spec/function-host-core.md` §2.4, §3): swap returns the
/// displaced version for the same address; LRU evicts the
/// least-recently-*accessed* lazy entry, never a warm one, to make room for
/// a new address; `close()` waits for `release()` and gives up after its
/// timeout.
class FunctionRegistryTest {

    private static final FunctionAddress A = FunctionAddress.parse("t.svc.a");
    private static final FunctionAddress B = FunctionAddress.parse("t.svc.b");
    private static final FunctionAddress C = FunctionAddress.parse("t.svc.c");

    @Test
    void putReturnsTheDisplacedVersionForTheSameAddress() {
        FunctionRegistry registry = new FunctionRegistry(4);
        LoadedFunction v1 = noop(A, 1);
        LoadedFunction v2 = noop(A, 2);

        assertThat(registry.put(v1)).isNull();
        assertThat(registry.put(v2)).isSameAs(v1);
        assertThat(registry.get(A)).isSameAs(v2);
    }

    @Test
    void getReturnsNullForAnUnregisteredAddress() {
        FunctionRegistry registry = new FunctionRegistry(4);
        assertThat(registry.get(A)).isNull();
    }

    @Test
    void lruEvictsTheLeastRecentlyAccessedLazyEntry() {
        FunctionRegistry registry = new FunctionRegistry(2);
        LoadedFunction a = noop(A, 1);
        LoadedFunction b = noop(B, 1);
        LoadedFunction c = noop(C, 1);

        registry.put(a); // lazy, least recently accessed once b is touched
        registry.put(b); // lazy
        registry.get(A); // touch a — b is now the LRU candidate

        registry.put(c); // at capacity (2): evicts b, not a

        assertThat(registry.get(A)).isSameAs(a);
        assertThat(registry.get(B)).isNull();
        assertThat(registry.get(C)).isSameAs(c);
    }

    @Test
    void warmEntriesAreNeverEvicted() {
        FunctionRegistry registry = new FunctionRegistry(2);
        LoadedFunction a = noop(A, 1);
        LoadedFunction b = noop(B, 1);
        LoadedFunction c = noop(C, 1);

        registry.put(a, true); // warm
        registry.put(b, false); // lazy

        registry.put(c); // evicts b (the only lazy entry), never a

        assertThat(registry.get(A)).isSameAs(a);
        assertThat(registry.get(B)).isNull();
        assertThat(registry.get(C)).isSameAs(c);
    }

    @Test
    void exceedingCapacityWithOnlyWarmEntriesThrows() {
        FunctionRegistry registry = new FunctionRegistry(1);
        registry.put(noop(A, 1), true);

        assertThatThrownBy(() -> registry.put(noop(B, 1), true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new FunctionRegistry(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeWaitsForReleaseBeforeStoppingTheFunction() throws Exception {
        LoadedFunction function = noop(A, 1);
        function.retain();

        Thread closer = new Thread(function::close);
        closer.start();
        Thread.sleep(200); // close() is parked in awaitDrain — this is timing-sensitive but generous
        assertThat(closer.isAlive()).as("close() must wait while an invocation is retained").isTrue();

        function.release();
        closer.join(Duration.ofSeconds(5).toMillis());
        assertThat(closer.isAlive()).as("close() must proceed once release() reaches zero").isFalse();
    }

    @Test
    void closeGivesUpAfterItsTimeoutAndClosesAnyway() {
        LoadedFunction function = noop(A, 1);
        function.retain(); // never released

        function.close(Duration.ofMillis(200));

        assertThatThrownBy(() -> function.invoke(null, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releaseWithoutRetainThrows() {
        LoadedFunction function = noop(A, 1);
        assertThatThrownBy(function::release).isInstanceOf(IllegalStateException.class);
    }

    private static LoadedFunction noop(FunctionAddress address, int version) {
        URLClassLoader loader = new URLClassLoader(new URL[0], null);
        return new LoadedFunction(new NoopFunction(), loader, address, version);
    }

    private static final class NoopFunction implements Function {
        @Override
        public Result handle(Request in, FunctionContext ctx) {
            return Result.ack();
        }
    }
}
