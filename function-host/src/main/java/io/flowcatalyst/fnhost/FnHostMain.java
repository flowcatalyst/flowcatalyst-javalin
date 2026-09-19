package io.flowcatalyst.fnhost;

import io.flowcatalyst.fnhost.reconcile.HostEnv;
import io.flowcatalyst.server.EnvReader;
import io.flowcatalyst.server.Logging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

/// `fc-fnhost`'s process entry point (spec `function-host-process.md` §1).
/// No picocli, no sub-commands — this is a daemon. `main` is the ONLY caller
/// of [System#exit] in this class; [#run] is the testable seam that returns
/// the exit code instead.
public final class FnHostMain {

    private static final Logger LOG = LoggerFactory.getLogger(FnHostMain.class);

    private FnHostMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        System.exit(run(EnvReader.system(), System.err, null));
    }

    /// @param closeCounter test-only seam (P8): incremented exactly once,
    ///                     the moment [FnHost#close] is actually called —
    ///                     `null` in production
    /// @return the process exit code: `2` on a startup environment error
    ///         (one line to `err` naming every missing/invalid variable),
    ///         `0` after `FC_EXIT_AFTER_START` or a clean shutdown
    static int run(EnvReader envReader, PrintStream err, AtomicInteger closeCounter) throws InterruptedException {
        Logging.init(envReader);
        HostEnv env;
        try {
            env = HostEnv.load(envReader);
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 2;
        }

        FnHost host = new FnHost(env);
        host.start();
        LOG.atInfo().setMessage("function host started")
                .addKeyValue("pool", env.pool().value())
                .addKeyValue("host_id", env.hostId())
                .addKeyValue("port", host.port())
                .addKeyValue("metrics_port", host.metricsPort())
                .log();

        // FC_EXIT_AFTER_START (the server's AOT-training convention, spec §1): log
        // once the host is up (proof the training run reached a real start), then
        // tear everything back down synchronously so main can return with nothing
        // left running — JEP 514 writes the AOT cache at JVM exit.
        if (env.exitAfterStart()) {
            close(host, closeCounter);
            return 0;
        }

        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("fn-host-shutdown").unstarted(() -> {
            LOG.info("shutdown signal received");
            close(host, closeCounter);
        }));
        try {
            host.awaitStop();
        } catch (InterruptedException e) {
            // Not the SIGTERM path (that resolves awaitStop() normally, via the hook's own
            // close() counting the latch down) — this thread was interrupted directly, e.g. a
            // test driving run() on its own thread. Close here too, or the host (and its bound
            // listeners) would otherwise leak forever with nothing left to ever call close().
            Thread.currentThread().interrupt();
            close(host, closeCounter);
        }
        return 0;
    }

    private static void close(FnHost host, AtomicInteger closeCounter) {
        if (closeCounter != null) {
            closeCounter.incrementAndGet();
        }
        host.close();
    }
}
