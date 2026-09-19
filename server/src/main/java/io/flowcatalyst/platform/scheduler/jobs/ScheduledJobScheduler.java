package io.flowcatalyst.platform.scheduler.jobs;

import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.server.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.BooleanSupplier;

/// The scheduled-job scheduler's composition root (`docs/spec/scheduled-job-scheduler.md`
/// §1): the poller and the dispatcher, each on its own virtual thread under
/// one [StructuredTaskScope], owned by a dedicated platform thread —
/// [io.flowcatalyst.stream.StreamProcessor]'s lifecycle exactly, for the same
/// reason: [StructuredTaskScope] requires its owner to `join` before `close`,
/// and both loops run forever until interrupted, so [#close] interrupts the
/// owner thread instead — its blocking `scope.join()` throws
/// `InterruptedException`, which counts as having joined, so the scope's own
/// `close()` goes on to cancel and await both loops.
public final class ScheduledJobScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledJobScheduler.class);

    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(30);
    static final Duration DEFAULT_DISPATCH_INTERVAL = Duration.ofSeconds(5);
    static final int DEFAULT_DISPATCH_BATCH = 32;
    static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final Thread owner;

    private ScheduledJobScheduler(Thread owner) {
        this.owner = owner;
    }

    /// Every env-derived knob (spec §1), read once at the composition root
    /// (CONVENTIONS §8) and passed as values — the shape
    /// [io.flowcatalyst.stream.StreamProcessor.Settings] established.
    public record Settings(Duration pollInterval, Duration dispatchInterval, int dispatchBatchSize,
                           Duration httpTimeout, Optional<Encryption> encryption) {
        public Settings {
            Objects.requireNonNull(pollInterval, "pollInterval");
            Objects.requireNonNull(dispatchInterval, "dispatchInterval");
            Objects.requireNonNull(httpTimeout, "httpTimeout");
            Objects.requireNonNull(encryption, "encryption");
        }

        public static Settings fromEnv(Env env) {
            return new Settings(
                    seconds(env.scheduledJobPollSeconds(), DEFAULT_POLL_INTERVAL),
                    seconds(env.scheduledJobDispatchSeconds(), DEFAULT_DISPATCH_INTERVAL),
                    env.scheduledJobDispatchBatch() > 0 ? env.scheduledJobDispatchBatch() : DEFAULT_DISPATCH_BATCH,
                    seconds(env.scheduledJobHttpTimeoutSeconds(), DEFAULT_HTTP_TIMEOUT),
                    Encryption.fromKeys(env.appKey(), env.appKeyPrevious()));
        }

        private static Duration seconds(int value, Duration fallback) {
            return value > 0 ? Duration.ofSeconds(value) : fallback;
        }
    }

    /// Builds and starts both loops against `pool`, gated by `leader` (spec
    /// §1: one election shared by both). `leader` returning `false` — whether
    /// because standby elected someone else, or because the election itself
    /// failed to start (fail-closed, spec §1) — makes every tick a no-op.
    public static ScheduledJobScheduler start(DataSource pool, Settings settings, BooleanSupplier leader) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(leader, "leader");

        var jobs = new ScheduledJobRepository(pool);
        var instances = new ScheduledJobInstanceRepository(pool);
        var serviceAccounts = new ServiceAccountRepository(pool, settings.encryption());
        var poller = new JobPoller(jobs, instances, leader);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(settings.httpTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var credentials = OutboundCredentials.cached(
                applicationId -> OutboundCredentials.resolve(serviceAccounts, applicationId), Clock.systemUTC());
        var dispatcher = new JobDispatcher(jobs, instances, httpClient, settings.httpTimeout(), credentials, leader,
                settings.dispatchBatchSize(), Clock.systemUTC());

        Thread owner = Thread.ofPlatform().name("scheduled-job-scheduler-owner")
                .start(() -> runScope(poller, dispatcher, settings));
        return new ScheduledJobScheduler(owner);
    }

    private static void runScope(JobPoller poller, JobDispatcher dispatcher, Settings settings) {
        try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            scope.fork(() -> loop("scheduled-job-poller", settings.pollInterval(), poller::pollOnce));
            scope.fork(() -> loop("scheduled-job-dispatcher", settings.dispatchInterval(), dispatcher::dispatchOnce));
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.warn("scheduled-job scheduler scope ended abnormally", e);
        }
    }

    /// One loop = one timer: run the tick, log and continue on error (spec
    /// §1), sleep the interval regardless of outcome, exit on interrupt.
    private static void loop(String name, Duration interval, Runnable tick) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                tick.run();
            } catch (RuntimeException e) {
                LOG.atWarn().setMessage("tick failed; will retry next tick")
                        .addKeyValue("name", name)
                        .setCause(e)
                        .log();
            }
            if (!sleep(interval)) return;
        }
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /// Interrupts the owner thread and blocks until it (and, through the
    /// scope it owns, both loops) has actually stopped.
    @Override
    public void close() {
        owner.interrupt();
        try {
            owner.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
