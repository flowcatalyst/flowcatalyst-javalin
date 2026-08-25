package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.standby.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Boots and stops the router as a whole.
///
/// ### Leadership gates everything that touches a queue
///
/// The router only consumes while it holds leadership. Gaining it starts the
/// consumers and their poll loops; losing it stops them and hands their work
/// back. Everything else — the tracker, the pools' shape, the metrics — is
/// built once at construction and simply idles in between, so a failover is
/// a matter of starting and stopping *sources*, not rebuilding the world.
///
/// ### Startup is concurrent for the same reason shutdown is
///
/// Building a consumer opens a broker connection. Built in turn, one slow or
/// unreachable broker delays every queue behind it, and a deployment with
/// eight queues waits for the sum rather than the maximum. Built together,
/// a slow broker costs its own latency and nothing else — and a broker that
/// never answers is bounded rather than blocking the boot indefinitely.
public final class RouterServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RouterServer.class);

    /// How long a leadership transition may spend starting or stopping
    /// sources before it gives up and carries on. Bounded because a
    /// transition that never completes is worse than one that completes
    /// partially: the former wedges failover entirely.
    static final Duration TRANSITION_TIMEOUT = Duration.ofSeconds(30);

    private final RouterManager manager;
    private final InFlightTracker tracker;
    private final LeaderElection election;
    private final RouterManager.ConsumerFactory consumerFactory;
    private final ConfigSource configSource;
    private final Warnings warnings;
    private final Clock clock;
    private final Duration drainTimeout;

    /// The poll loop running for each queue, so a leadership loss can stop
    /// exactly what a gain started.
    private final Map<String, Thread> loops = new ConcurrentHashMap<>();

    private volatile boolean running;

    /// Where the router's configuration comes from. An interface so a
    /// single-tenant deployment can supply a fixed config without a config
    /// service, which is the default-broker mode the spec describes (§8.4).
    @FunctionalInterface
    public interface ConfigSource {

        /// @return the configuration to apply, or empty when it is
        ///         **unavailable** — in which case the router keeps running
        ///         what it already has rather than tearing itself down.
        ///
        /// A source may return the same configuration every time. Applying it
        /// is idempotent: [RouterManager#reconfigure] leaves unchanged queues
        /// and pools alone. That matters more than it sounds — after a
        /// leadership loss the consumers have been forgotten, so a source
        /// that suppressed "unchanged" configurations would regain
        /// leadership and rebuild nothing.
        Optional<RouterConfig> fetch();

        /// A configuration that never changes — the single-tenant and
        /// default-broker cases, which have no config service.
        static ConfigSource fixed(RouterConfig config) {
            return () -> Optional.of(config);
        }
    }

    public RouterServer(RouterManager manager, InFlightTracker tracker, LeaderElection election,
                        RouterManager.ConsumerFactory consumerFactory, ConfigSource configSource,
                        Warnings warnings, Clock clock, Duration drainTimeout) {
        this.manager = manager;
        this.tracker = tracker;
        this.election = election;
        this.consumerFactory = consumerFactory;
        this.configSource = configSource;
        this.warnings = warnings;
        this.clock = clock;
        this.drainTimeout = drainTimeout;
    }

    public boolean running() {
        return running;
    }

    public boolean leader() {
        return election.isLeader();
    }

    /// Queues currently being polled.
    public int activeLoops() {
        return loops.size();
    }

    /// Starts contending for leadership and reacting to it.
    ///
    /// Returns once the first leadership decision has been acted on, so a
    /// caller can assert the router's state immediately instead of racing
    /// the election's own loop.
    public void start() {
        // Registered BEFORE start(), and that ordering is the whole of it:
        // an instance that comes up as leader transitions from follower to
        // leader like any other, so the listener catches the initial state
        // as a normal change. Registering afterwards would miss it and the
        // router would idle until the next heartbeat.
        election.onChange(change -> {
            if (change.leader()) {
                gainLeadership();
            } else {
                loseLeadership();
            }
        });
        election.start();
    }

    private synchronized void gainLeadership() {
        if (running) {
            return;
        }
        log.info("leadership gained; starting consumers");
        running = true;
        applyConfiguration();
    }

    private synchronized void loseLeadership() {
        if (!running) {
            return;
        }
        log.info("leadership lost; stopping consumers and handing work back");
        running = false;
        stopSources();
    }

    /// Fetches configuration and applies it, starting a poll loop for every
    /// queue that is now running and stopping any whose consumer went away.
    ///
    /// Safe to call repeatedly: [RouterManager#reconfigure] leaves unchanged
    /// queues alone, so a poll that finds nothing new costs nothing.
    public synchronized void applyConfiguration() {
        if (!running) {
            return;
        }
        var config = configSource.fetch();
        if (config.isEmpty()) {
            return;
        }
        var result = manager.reconfigure(config.get(), consumerFactory);
        if (!result.complete()) {
            // Running with less than the configuration asks for is an
            // operator-visible condition, not a log line: some queues are
            // simply not being consumed.
            warnings.raise(Warnings.Severity.ERROR, "CONFIGURATION",
                    "router is running without " + result.failedQueues().size()
                            + " configured queue(s): " + String.join(", ", result.failedQueues()));
        }
        syncLoops();
    }

    /// Starts a loop for every consumer that has one missing, and stops any
    /// whose consumer has gone.
    private void syncLoops() {
        var current = manager.pools(); // touch, so a misconfigured manager fails here rather than later
        assert current != null;

        loops.entrySet().removeIf(entry -> {
            if (manager.consumer(entry.getKey()).isPresent()) {
                return false;
            }
            entry.getValue().interrupt();
            return true;
        });

        var toStart = manager.consumerNames().stream()
                .filter(name -> !loops.containsKey(name))
                .toList();
        // Concurrent: each start may touch its broker, and a slow one must
        // not delay the queues behind it.
        Concurrently.forEach(toStart, name -> manager.consumer(name).ifPresent(this::startLoop),
                TRANSITION_TIMEOUT, "consumer loop start");
    }

    private void startLoop(Consumer consumer) {
        var loop = new ConsumerLoop(consumer, manager, warnings, clock);
        var thread = Thread.ofVirtual().name("poll-" + consumer.identifier()).start(loop);
        loops.put(consumer.identifier(), thread);
    }

    /// Stops every source and hands back what they were holding, leaving the
    /// pools and tracker in place for a later leadership gain.
    private void stopSources() {
        var consumers = manager.consumerNames().stream()
                .map(manager::consumer)
                .flatMap(Optional::stream)
                .toList();
        // standDown, not shutdown: the pools must survive so a later
        // leadership gain has somewhere to put messages. Closing them here
        // is a bug that only appears on failover BACK.
        new RouterShutdown(tracker, drainTimeout, TRANSITION_TIMEOUT)
                .standDown(List.copyOf(loops.values()), consumers, manager.pools().values());
        loops.clear();
        manager.forgetConsumers();
    }

    /// Stops the router for good.
    @Override
    public void close() {
        loseLeadership();
        election.close();
    }

    /// The queues a fixed configuration describes, for a caller assembling a
    /// single-tenant deployment without a config service.
    public static ConfigSource fixed(List<QueueConfig> queues, RouterConfig config) {
        return ConfigSource.fixed(new RouterConfig(config.processingPools(), queues));
    }
}
