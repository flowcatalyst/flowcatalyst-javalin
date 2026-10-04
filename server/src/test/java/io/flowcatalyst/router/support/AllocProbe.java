package io.flowcatalyst.router.support;

import java.lang.management.ManagementFactory;

/// Measures bytes allocated by the calling (platform) thread per call of a task.
/// Warm up first so the JIT has compiled the path; ceilings in tests stay loose.
public final class AllocProbe {

    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private AllocProbe() {
    }

    /// Average bytes allocated per call over `iterations`, after `warmup` calls.
    /// Runs on a fresh platform thread so the measurement is not on a virtual thread.
    public static double bytesPerCall(int warmup, int iterations, Runnable task) {
        double[] out = new double[1];
        Throwable[] failure = new Throwable[1];
        var t = new Thread(() -> {
            try {
                for (int i = 0; i < warmup; i++) {
                    task.run();
                }
                long id = Thread.currentThread().threadId();
                long before = MX.getThreadAllocatedBytes(id);
                for (int i = 0; i < iterations; i++) {
                    task.run();
                }
                long after = MX.getThreadAllocatedBytes(id);
                out[0] = (after - before) / (double) iterations;
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "alloc-probe");
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
        return out[0];
    }
}
