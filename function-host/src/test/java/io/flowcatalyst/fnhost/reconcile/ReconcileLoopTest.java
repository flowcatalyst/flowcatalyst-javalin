package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/// [ReconcileLoop] (`docs/spec/function-host-reconciler.md` §1.3, R10). Uses
/// the package-private short-interval constructor so these tests run in
/// milliseconds, never the production 15s.
class ReconcileLoopTest {

    @Test
    void nTriggersDuringARunCoalesceToExactlyOneMoreRun(@TempDir Path dir) {
        FakeControlPlane fake = new FakeControlPlane();
        CountDownLatch blockFirstRun = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        fake.desiredStateReturns((pool, etag) -> {
            if (firstCall.getAndSet(false)) {
                blockFirstRun.await();
            }
            return new ControlPlane.Fetched.NotModified();
        });

        ReconcileLoop loop = new ReconcileLoop(reconciler(fake, dir), Clock.systemUTC(), Duration.ofSeconds(2));
        loop.start();
        try {
            assertThat(pollUntil(() -> fake.desiredStateCallCount() >= 1, Duration.ofSeconds(2)))
                    .as("the first run must have started (and be blocked) before triggering").isTrue();

            for (int i = 0; i < 5; i++) {
                loop.trigger();
            }
            blockFirstRun.countDown(); // let run 1 finish

            // Run 2 must start right away (the coalesced trigger), well before the 2s interval.
            assertThat(pollUntil(() -> fake.desiredStateCallCount() >= 2, Duration.ofSeconds(1)))
                    .as("mutant: drop the trigger entirely instead of running once more").isTrue();

            // ...but must NOT keep climbing: 5 triggers must yield exactly one extra run, not five.
            sleepWellUnderTheInterval();
            assertThat(fake.desiredStateCallCount()).as("mutant: run once per trigger instead of coalescing")
                    .isEqualTo(2);
        } finally {
            loop.close();
        }
    }

    @Test
    void closeInterruptsARunBlockedInTheControlPlaneAndJoinsPromptly(@TempDir Path dir) {
        FakeControlPlane fake = new FakeControlPlane();
        CountDownLatch blockForever = new CountDownLatch(1); // never counted down by the test
        fake.desiredStateReturns((pool, etag) -> {
            blockForever.await();
            return new ControlPlane.Fetched.NotModified();
        });

        ReconcileLoop loop = new ReconcileLoop(reconciler(fake, dir), Clock.systemUTC(), Duration.ofSeconds(30));
        loop.start();
        assertThat(pollUntil(() -> fake.desiredStateCallCount() >= 1, Duration.ofSeconds(2)))
                .as("the run must actually be blocked in the control plane before close() is tested").isTrue();

        Instant before = Instant.now();
        loop.close();
        Duration elapsed = Duration.between(before, Instant.now());

        // close()'s own join bound is 5s; a genuinely prompt interrupt finishes in well under that —
        // this is what distinguishes "interrupted" from "close() gave up after its own timeout".
        assertThat(elapsed).as("mutant: swallow the interrupt — close() would hang, bounded only by its own 5s join")
                .isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void anUnexpectedExceptionInARunIsLoggedAndTheLoopContinues(@TempDir Path dir) {
        FakeControlPlane fake = new FakeControlPlane();
        AtomicInteger calls = new AtomicInteger();
        fake.desiredStateReturns((pool, etag) -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("boom");
            }
            return new ControlPlane.Fetched.NotModified();
        });

        ReconcileLoop loop = new ReconcileLoop(reconciler(fake, dir), Clock.systemUTC(), Duration.ofMillis(50));
        loop.start();
        try {
            assertThat(pollUntil(() -> calls.get() >= 2, Duration.ofSeconds(3)))
                    .as("mutant: an unexpected exception must not end the loop").isTrue();
        } finally {
            loop.close();
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private static Reconciler reconciler(FakeControlPlane fake, Path dir) {
        return new Reconciler(new DnsLabel("pool"), "host-1", fake, new FileArtifactStore(dir.resolve("cache")),
                new Signatures.Off(), new JvmFunctionLoader(), new FunctionRegistry(10));
    }

    private static void sleepWellUnderTheInterval() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean pollUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
