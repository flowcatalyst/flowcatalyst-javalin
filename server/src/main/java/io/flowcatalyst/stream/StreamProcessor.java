package io.flowcatalyst.stream;

import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.server.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.BooleanSupplier;

/// The stream subsystem's composition root (stream spec §1, §9): builds
/// whichever of the four projectors are enabled and runs each on its own
/// virtual thread under one [StructuredTaskScope]. The scope is owned by a
/// dedicated platform thread ([#owner]) — [StructuredTaskScope] requires its
/// owner to `join` before `close` (an unjoined `close` throws
/// `IllegalStateException`, "Owner did not join after forking"), and every
/// projector's loop runs forever until interrupted, so nothing ever calls
/// `join` voluntarily. [#close] interrupts the owner thread instead: its
/// blocking `scope.join()` throws `InterruptedException`, which still counts
/// as having joined, so the scope's own `close()` (called from the owner
/// thread, on the way out of the try-with-resources) goes on to cancel and
/// await every still-running projector — satisfying spec §9's shutdown test:
/// interrupting stops all enabled loops within one sleep tier and `running`
/// goes false on each.
public final class StreamProcessor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StreamProcessor.class);

    static final int FAN_OUT_DEFAULT_BATCH_SIZE = 200;
    static final int EVENTS_DEFAULT_BATCH_SIZE = 100;
    static final int DISPATCH_JOBS_DEFAULT_BATCH_SIZE = 100;
    static final Duration DEFAULT_SUBSCRIPTION_TTL = Duration.ofSeconds(5);

    private final Thread owner;
    private final HealthService healthService;

    private StreamProcessor(Thread owner, HealthService healthService) {
        this.owner = owner;
        this.healthService = healthService;
    }

    public HealthService healthService() {
        return healthService;
    }

    /// Every env-derived stream knob (stream spec §1-§6), read once at the
    /// composition root (CONVENTIONS §8: "subsystem knobs reach the
    /// composition root through `Env`") and passed as values.
    /// [#fromEnv] is the convenience `Server` and tests both use to build
    /// one from an [Env] without repeating the field list.
    public record Settings(boolean fanOutEnabled, int fanOutBatchSizeOverride,
                            boolean eventsEnabled, int eventsBatchSizeOverride,
                            boolean dispatchJobsEnabled, int dispatchJobsBatchSizeOverride,
                            boolean partitionsEnabled,
                            int globalBatchSizeOverride,
                            int subsRefreshSecs,
                            int partitionMonthsForward,
                            int partitionRetentionDays,
                            int partitionScheduledJobRetentionDays,
                            int partitionTickHours) {

        public static Settings fromEnv(Env env) {
            return new Settings(
                    env.streamFanOutEnabled(), env.streamFanOutBatchSizeOverride(),
                    env.streamEventsEnabled(), env.streamEventsBatchSizeOverride(),
                    env.streamDispatchJobsEnabled(), env.streamDispatchJobsBatchSizeOverride(),
                    env.streamPartitionsEnabled(),
                    env.streamBatchSize(),
                    env.streamFanOutSubsRefreshSecs(),
                    env.streamPartitionMonthsForward(),
                    env.streamPartitionRetentionDays(),
                    env.streamPartitionScheduledJobRetentionDays(),
                    env.streamPartitionTickHours());
        }
    }

    /// `override` (`FC_STREAM_<NAME>_BATCH_SIZE`) beats `global`
    /// (`FC_STREAM_BATCH_SIZE`) beats `perProjectorDefault` (stream spec §2).
    /// `0` means "unset" for both env-sourced values.
    static int resolveBatchSize(int override, int global, int perProjectorDefault) {
        if (override > 0) return override;
        if (global > 0) return global;
        return perProjectorDefault;
    }

    /// Builds and starts every enabled projector against `pool`, gated by
    /// `leader` (stream spec §1: one election shared by all four). Never
    /// returns `null` — a subsystem with every sub-toggle off simply starts
    /// no projectors, mirroring an always-on [HealthService] with an empty
    /// registry.
    public static StreamProcessor start(DataSource pool, Settings settings, BooleanSupplier leader) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(leader, "leader");

        var healthService = new HealthService();
        List<Runnable> projectors = new ArrayList<>();

        if (settings.fanOutEnabled()) {
            var health = new Health("event_fan_out");
            healthService.register(health);
            int batchSize = resolveBatchSize(settings.fanOutBatchSizeOverride(), settings.globalBatchSizeOverride(),
                    FAN_OUT_DEFAULT_BATCH_SIZE);
            Duration ttl = settings.subsRefreshSecs() > 0
                    ? Duration.ofSeconds(settings.subsRefreshSecs()) : DEFAULT_SUBSCRIPTION_TTL;
            var subscriptionRepository = new SubscriptionRepository(pool);
            var fanOut = new FanOut(pool, subscriptionRepository::findActiveOrderedById, ttl, Clock.systemUTC());
            projectors.add(new Projector("event_fan_out", ProjectorConfig.of(true, batchSize), fanOut, leader, health));
        }
        if (settings.eventsEnabled()) {
            var health = new Health("event_projection");
            healthService.register(health);
            int batchSize = resolveBatchSize(settings.eventsBatchSizeOverride(), settings.globalBatchSizeOverride(),
                    EVENTS_DEFAULT_BATCH_SIZE);
            var step = new EventProjection(pool);
            projectors.add(new Projector("event_projection", ProjectorConfig.of(true, batchSize), step, leader, health));
        }
        if (settings.dispatchJobsEnabled()) {
            var health = new Health("dispatch_job_projection");
            healthService.register(health);
            int batchSize = resolveBatchSize(settings.dispatchJobsBatchSizeOverride(),
                    settings.globalBatchSizeOverride(), DISPATCH_JOBS_DEFAULT_BATCH_SIZE);
            var step = new DispatchJobProjection(pool);
            projectors.add(new Projector("dispatch_job_projection", ProjectorConfig.of(true, batchSize), step, leader,
                    health));
        }
        if (settings.partitionsEnabled()) {
            var health = new Health("partition_manager");
            healthService.register(health);
            var config = new PartitionManager.Config(true,
                    positiveOr(settings.partitionMonthsForward(), PartitionManager.Config.DEFAULT_MONTHS_FORWARD),
                    positiveOr(settings.partitionRetentionDays(), PartitionManager.Config.DEFAULT_RETENTION_DAYS),
                    positiveOr(settings.partitionScheduledJobRetentionDays(),
                            PartitionManager.Config.DEFAULT_SCHEDULED_JOB_RETENTION_DAYS),
                    settings.partitionTickHours() > 0 ? Duration.ofHours(settings.partitionTickHours())
                            : PartitionManager.Config.DEFAULT_TICK_INTERVAL);
            projectors.add(new PartitionManager(pool, config, leader, health, Clock.systemUTC()));
        }

        Thread owner = Thread.ofPlatform().name("stream-processor-owner").start(() -> runScope(projectors));
        return new StreamProcessor(owner, healthService);
    }

    /// The owner thread's whole body: fork every projector, then block in
    /// `join()` until either every projector returns on its own (never, in
    /// practice — they loop until interrupted) or this thread is
    /// interrupted, at which point the scope's `close()` (via
    /// try-with-resources) cancels and awaits every still-running projector.
    private static void runScope(List<Runnable> projectors) {
        try (StructuredTaskScope<Object, Void> scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
            for (Runnable projector : projectors) {
                scope.fork(projector);
            }
            scope.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.warn("stream processor scope ended abnormally", e);
        }
    }

    private static int positiveOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    /// Interrupts the owner thread and blocks until it (and, through the
    /// scope it owns, every projector) has actually stopped.
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
