package io.flowcatalyst.router.manager;

import io.flowcatalyst.router.observability.Warnings;

import io.flowcatalyst.router.concurrent.Concurrently;

import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import io.flowcatalyst.router.inflight.InFlightTracker;
import io.flowcatalyst.router.queue.Consumer;
import io.flowcatalyst.router.standby.LeaderElection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /// How often the initial-apply loop (R-B) retries a configuration source
    /// that has never yet returned a present result. Only used before the
    /// first successful [#applyConfiguration]; once that succeeds the loop
    /// exits and [#CONFIG_POLL_INTERVAL_DEFAULT] takes over for the ordinary
    /// periodic re-check.
    static final Duration DEFAULT_INITIAL_APPLY_RETRY = Duration.ofSeconds(5);

    /// How often [#applyConfiguration] is re-run while leader, so a
    /// configuration change on the source side reaches a router that never
    /// lost and regained leadership (A-10). The default [#parseConfigPollInterval]
    /// falls back to — `FC_ROUTER_CONFIG_INTERVAL_SECONDS` / `FLOWCATALYST_CONFIG_INTERVAL`
    /// (`docs/spec/router-env.md` §2) makes it env-tunable, resolving R-31.
    public static final Duration CONFIG_POLL_INTERVAL_DEFAULT = Duration.ofMinutes(5);

    /// Parses `FC_ROUTER_CONFIG_INTERVAL_SECONDS` (alias `FLOWCATALYST_CONFIG_INTERVAL`,
    /// [io.flowcatalyst.server.Env#routerConfigIntervalRaw]) into the interval
    /// [io.flowcatalyst.server.Router] schedules the config-poll housekeeping
    /// task at. `raw` blank/unset is the ordinary case and silently resolves
    /// to [#CONFIG_POLL_INTERVAL_DEFAULT] (300s); a value that is *set* but
    /// not a positive integer is an operator typo worth a WARN naming it,
    /// degraded to the same default rather than refused (Rust silently
    /// defaults instead — a typo here deserves a line).
    public static Duration parseConfigPollInterval(String raw) {
        if (raw == null || raw.isBlank()) {
            return CONFIG_POLL_INTERVAL_DEFAULT;
        }
        try {
            var seconds = Integer.parseInt(raw.trim());
            if (seconds > 0) {
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning below
        }
        log.atWarn().setMessage("FC_ROUTER_CONFIG_INTERVAL_SECONDS is not a positive integer; using the 300s default")
                .addKeyValue("value", raw)
                .log();
        return CONFIG_POLL_INTERVAL_DEFAULT;
    }

    private final RouterManager manager;
    private final InFlightTracker tracker;
    private final LeaderElection election;
    private final RouterManager.ConsumerFactory consumerFactory;
    private final ConfigSource configSource;
    private final Warnings warnings;
    private final Clock clock;
    private final Duration drainTimeout;
    private final Duration initialApplyRetry;

    /// The stall watchdog (R-26, `docs/spec/router-completion.md` §2 ruling
    /// 5) — [#restartStalledLoops] is the housekeeping task that drives it.
    /// Injectable so a test can use a negligible restart delay rather than
    /// waiting out the real one (`ConsumerSupervisor`'s own doc explains why
    /// that constant is real elapsed time).
    private final ConsumerSupervisor supervisor;

    /// The poll loop running for each queue, so a leadership loss can stop
    /// exactly what a gain started, and readiness can ask each loop whether
    /// it is still making progress (R-36).
    private final Map<String, Loop> loops = new ConcurrentHashMap<>();

    /// A running poll loop's thread (the stop handle) alongside the
    /// [ConsumerLoop] itself (the liveness handle) — kept together because
    /// [#stalledConsumers] needs both a queue's heartbeat and when its loop
    /// started, and a `Map<String, Thread>` alone cannot answer either.
    private record Loop(Thread thread, ConsumerLoop consumerLoop) {
    }

    private volatile boolean running;

    /// The thread running the initial-apply loop (R-B) between leadership
    /// gain and the first successful [#applyConfiguration] — `null` before
    /// the first gain, a finished thread after that first success (callers
    /// test [Thread#isAlive], not nullness). Held so [#loseLeadership]
    /// and [#close] can interrupt it: a source that never answers must not
    /// keep looping on a thread nobody is watching after this instance has
    /// stopped leading or stopped altogether.
    private volatile Thread initialApply;

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
        this(manager, tracker, election, consumerFactory, configSource, warnings, clock, drainTimeout,
                DEFAULT_INITIAL_APPLY_RETRY, new ConsumerSupervisor(warnings, clock));
    }

    /// `initialApplyRetry` is how often the initial-apply loop (R-B) retries
    /// a configuration source that has never succeeded; `supervisor` is
    /// injectable so a test can drive the stall-restart path on a negligible
    /// delay rather than the production [ConsumerSupervisor#RESTART_DELAY].
    public RouterServer(RouterManager manager, InFlightTracker tracker, LeaderElection election,
                        RouterManager.ConsumerFactory consumerFactory, ConfigSource configSource,
                        Warnings warnings, Clock clock, Duration drainTimeout, Duration initialApplyRetry,
                        ConsumerSupervisor supervisor) {
        this.manager = manager;
        this.tracker = tracker;
        this.election = election;
        this.consumerFactory = consumerFactory;
        this.configSource = configSource;
        this.warnings = warnings;
        this.clock = clock;
        this.drainTimeout = drainTimeout;
        this.initialApplyRetry = initialApplyRetry;
        this.supervisor = supervisor;
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

    /// Each running loop's last successful poll, for the readiness probe and
    /// any operator surface that wants per-queue liveness rather than just a
    /// count (R-36).
    public Map<String, Optional<Instant>> consumerHeartbeats() {
        Map<String, Optional<Instant>> out = new LinkedHashMap<>();
        loops.forEach((queueId, loop) -> out.put(queueId, loop.consumerLoop().lastPoll()));
        return Map.copyOf(out);
    }

    /// Queues whose poll loop has gone quiet: running for longer than
    /// [ConsumerSupervisor#STALL_THRESHOLD] with no successful poll in that
    /// same window (R-36). Unlike [ConsumerSupervisor#stalled], a loop that
    /// has *never* polled counts here once it has been running long enough —
    /// readiness is asking "is this router serving traffic", and a queue
    /// that has been up for ten minutes without a single successful poll is
    /// not, regardless of whether the restart watchdog would still call it
    /// too young to judge.
    public List<String> stalledConsumers() {
        var now = clock.instant();
        return loops.entrySet().stream()
                // A loop whose consumer is no longer the manager's active one
                // for this queue is not silently stuck, it is already on its
                // way out — a reconfigure mid-flight, or (owner ruling
                // 2026-09-11) a loop that just detached itself after its
                // queue answered QueueMissing and is waiting for the next
                // config sync to be swept from #loops by syncLoops. Judging
                // it here would fail readiness over a queue that is not a
                // health problem at all (`docs/spec/router.md` §7.2).
                .filter(entry -> manager.activeConsumer(entry.getKey()).isPresent())
                .filter(entry -> Duration.between(entry.getValue().consumerLoop().startedAt(), now)
                        .compareTo(ConsumerSupervisor.STALL_THRESHOLD) > 0)
                .filter(entry -> entry.getValue().consumerLoop().lastAlive()
                        .map(last -> Duration.between(last, now).compareTo(ConsumerSupervisor.STALL_THRESHOLD) > 0)
                        .orElse(true))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /// Starts contending for leadership and reacting to it.
    ///
    /// Returns once the first leadership decision has been **taken** — not
    /// once it has been fully acted on. R-A (2026-09-12): if that decision is
    /// "leader", the first [#applyConfiguration] runs on its own virtual
    /// thread ([#gainLeadership]) rather than on this calling thread, so a
    /// caller such as `Server.start()` never waits on a config fetch — the
    /// production [ConfigSource] (`HttpConfigSource`) can spend on the order
    /// of a minute retrying an unreachable config service, and a listener
    /// bind must not queue behind that.
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

    /// R-A/R-B (2026-09-12): the first [#applyConfiguration] after gaining
    /// leadership runs on its own virtual thread, `router-initial-apply`, so
    /// this method — and therefore [#start] — never blocks on a config fetch.
    /// That thread keeps calling [#applyConfiguration] until a result comes
    /// back present, spacing attempts by [#initialApplyRetry], and stops the
    /// moment leadership is lost or the server closes ([#loseLeadership],
    /// [#close] interrupt it) — a source that never answers is retried
    /// forever rather than given up on, but only for as long as this instance
    /// is still trying to lead.
    ///
    /// The loop lives here rather than inside `HttpConfigSource`'s per-URL
    /// retry deliberately: that retry runs each URL in parallel inside a
    /// `StructuredTaskScope`, and an unbounded retry down there would hold
    /// every healthy URL's configuration hostage to one dead one. Here, one
    /// full [#applyConfiguration] attempt (across all URLs) either succeeds
    /// or it doesn't, and only then does this loop wait and try again.
    private synchronized void gainLeadership() {
        if (running) {
            return;
        }
        log.info("leadership gained; starting consumers");
        running = true;
        initialApply = Thread.ofVirtual().name("router-initial-apply").start(this::runInitialApply);
    }

    private void runInitialApply() {
        boolean failedOnce = false;
        while (running && !Thread.currentThread().isInterrupted()) {
            var result = applyConfiguration();
            if (result.isPresent()) {
                if (failedOnce) {
                    log.info("initial configuration apply succeeded after a retry");
                }
                return;
            }
            if (!running) {
                return;
            }
            if (!failedOnce) {
                log.warn("initial configuration apply failed; retrying until it succeeds");
                failedOnce = true;
            }
            try {
                Thread.sleep(initialApplyRetry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void loseLeadership() {
        if (!running) {
            return;
        }
        log.info("leadership lost; stopping consumers and handing work back");
        running = false;
        var inFlight = initialApply;
        if (inFlight != null) {
            inFlight.interrupt();
        }
        stopSources();
    }

    /// Fetches configuration and applies it, starting a poll loop for every
    /// queue that is now running and stopping any whose consumer went away.
    ///
    /// Safe to call repeatedly: [RouterManager#reconfigure] leaves unchanged
    /// queues alone, so a poll that finds nothing new costs nothing — which
    /// is what lets this run both on leadership gain and on a periodic
    /// schedule ([#parseConfigPollInterval], A-10) without special-casing
    /// either caller. A follower or a not-yet-running instance is a no-op,
    /// answering empty rather than a zeroed result so a caller (the reload
    /// route, R-33) can tell "nothing to do" from "nothing changed".
    ///
    /// **Deliberately NOT synchronized.** [ConfigSource#fetch] can be slow —
    /// `HttpConfigSource` retries an unreachable config service for minutes
    /// — and this method is also the periodic config-poll task and the
    /// `/config/reload` handler, neither of which is called from inside
    /// [#gainLeadership]/[#loseLeadership]'s own synchronized block. Holding
    /// the monitor across a slow fetch would make a leadership loss racing
    /// it block [#loseLeadership] for however long the fetch takes — `running`
    /// stays true and the poll loops keep delivering well past §5.5's
    /// "losing leadership MUST pause polling" the whole time. [#apply] is the
    /// synchronized remainder, re-checking [#running] itself: leadership may
    /// have been lost while this call was waiting on the fetch, and applying
    /// a configuration fetched before that loss would start consumers as a
    /// follower.
    ///
    /// Called directly by [#gainLeadership]'s `router-initial-apply` thread
    /// (R-B) and by `/config/reload` (R-33), and — via [#pollConfiguration] —
    /// by the periodic config-poll housekeeping task (A-10). Deliberately not
    /// gated on [#initialApply] itself: the reload route and the
    /// initial-apply loop both need an unconditional fetch, so that guard
    /// lives one level up, in [#pollConfiguration], rather than here.
    ///
    /// @return what changed, or empty when this instance is not currently
    ///         running (not leader) or the configuration source is
    ///         momentarily unavailable
    public Optional<RouterManager.ReconfigureResult> applyConfiguration() {
        if (!running) {
            return Optional.empty();
        }
        var config = configSource.fetch();
        if (config.isEmpty()) {
            return Optional.empty();
        }
        return apply(config.get());
    }

    /// The periodic config-poll housekeeping task's entry point (A-10),
    /// wired in `io.flowcatalyst.server.Router` in place of
    /// [#applyConfiguration] directly. R-B: while the `router-initial-apply`
    /// thread is still looping — this instance has gained leadership but has
    /// never yet had a configuration source answer — this is a no-op, so the
    /// housekeeping thread never runs a second, concurrent fetch alongside
    /// it. Once that thread has succeeded (or was never started, or has
    /// since finished one way or the other), this simply delegates to
    /// [#applyConfiguration] as before.
    public Optional<RouterManager.ReconfigureResult> pollConfiguration() {
        var inFlight = initialApply;
        if (inFlight != null && inFlight.isAlive()) {
            return Optional.empty();
        }
        return applyConfiguration();
    }

    /// The part of [#applyConfiguration] that actually touches [#manager]
    /// and [#loops] — see that method's doc for why the fetch itself is not
    /// inside this lock.
    private synchronized Optional<RouterManager.ReconfigureResult> apply(RouterConfig config) {
        if (!running) {
            return Optional.empty();
        }
        var result = manager.reconfigure(config, consumerFactory);
        // Logged on every apply — the first one included — so a router that is
        // running reads differently in the logs from one still waiting on its
        // sources (a successful apply used to log nothing at all).
        log.atInfo().setMessage("router configuration applied")
                .addKeyValue("pools", config.processingPools().size())
                .addKeyValue("queues", config.queues().size())
                .addKeyValue("consumers_started", result.consumersStarted())
                .addKeyValue("consumers_stopped", result.consumersStopped())
                .addKeyValue("failed_queues", result.failedQueues().size())
                .log();
        if (!result.complete()) {
            // Running with less than the configuration asks for is an
            // operator-visible condition, not a log line: some queues are
            // simply not being consumed.
            warnings.raise(Warnings.Severity.ERROR, "CONFIGURATION",
                    "router is running without " + result.failedQueues().size()
                            + " configured queue(s): " + String.join(", ", result.failedQueues()));
        }
        syncLoops(Set.copyOf(result.replacedQueues()));
        return Optional.of(result);
    }

    /// Starts a loop for every consumer that has one missing, stops any whose
    /// consumer has gone, and — the X-11/R-26 case a pre-ruling implementation
    /// missed — **restarts** the loop for a `replacedQueues` name even though
    /// the name itself never left [RouterManager#consumerNames]: the
    /// consumer *identity* behind it changed, and leaving the loop alone
    /// would keep polling the old, now-detached consumer forever.
    ///
    /// [RouterManager#activeConsumer] is the check here, deliberately not
    /// [RouterManager#consumer]: the latter also resolves a lingering
    /// consumer, which would make a removed queue's loop look like it should
    /// keep running.
    private void syncLoops(Set<String> replacedQueues) {
        var current = manager.pools(); // touch, so a misconfigured manager fails here rather than later
        assert current != null;

        loops.entrySet().removeIf(entry -> {
            if (manager.activeConsumer(entry.getKey()).isPresent() && !replacedQueues.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().thread().interrupt();
            return true;
        });

        var toStart = manager.consumerNames().stream()
                .filter(name -> !loops.containsKey(name))
                .toList();
        // Concurrent: each start may touch its broker, and a slow one must
        // not delay the queues behind it.
        Concurrently.forEach(toStart, name -> manager.activeConsumer(name).ifPresent(this::startLoop),
                TRANSITION_TIMEOUT, "consumer loop start");
    }

    /// The stall-restart housekeeping tick (R-26,
    /// `docs/spec/router-completion.md` §2 ruling 5): rebuilds the poll loop
    /// for every queue [ConsumerSupervisor#stalled] judges silent, without
    /// aborting whatever its old consumer is still holding.
    ///
    /// The swap is exactly [#syncLoops]'s replaced-queue case, done for one
    /// queue at a time as the supervisor finds it: [RouterManager#replaceConsumer]
    /// detaches the stalled consumer to the manager's lingering set instead
    /// of closing it, this loop's own thread is interrupted (poll-only —
    /// nothing it is mid-delivery on is touched), and a fresh loop starts on
    /// the replacement. A follower runs this as a no-op: there is nothing to
    /// restart when nothing is running.
    public void restartStalledLoops() {
        if (!running) {
            return;
        }
        for (var entry : Map.copyOf(loops).entrySet()) {
            var queueName = entry.getKey();
            var loop = entry.getValue();
            if (!supervisor.stalled(loop.consumerLoop())) {
                // Recovery is a successful poll on the CURRENT loop, judged
                // here on the next tick rather than at restart time. Clearing
                // the count the moment a replacement is built would reset it
                // on every rebuild, so a consumer that re-stalls after each
                // restart could never escalate past WARNING (Q28).
                if (loop.consumerLoop().lastPoll().isPresent()) {
                    supervisor.recovered(queueName);
                }
                continue;
            }
            var config = manager.queueConfig(queueName);
            var stalledConsumer = manager.activeConsumer(queueName);
            if (config.isEmpty() || stalledConsumer.isEmpty()) {
                // A reconfigure already moved this queue on; leave it to
                // [#syncLoops] rather than restarting something no longer
                // wanted.
                continue;
            }
            Optional<Consumer> replacement;
            try {
                replacement = supervisor.restart(queueName, config.get(), stalledConsumer.get(), consumerFactory);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            replacement.ifPresent(consumer -> {
                manager.replaceConsumer(queueName, consumer);
                loop.thread().interrupt();
                startLoop(consumer);
            });
        }
    }

    private void startLoop(Consumer consumer) {
        var consumerLoop = new ConsumerLoop(consumer, manager, warnings, clock);
        var thread = Thread.ofVirtual().name("poll-" + consumer.identifier()).start(consumerLoop);
        loops.put(consumer.identifier(), new Loop(thread, consumerLoop));
    }

    /// Stops every source and hands back what they were holding, leaving the
    /// pools and tracker in place for a later leadership gain.
    private void stopSources() {
        var consumers = manager.consumerNames().stream()
                .map(manager::activeConsumer)
                .flatMap(Optional::stream)
                .toList();
        // standDown, not shutdown: the pools must survive so a later
        // leadership gain has somewhere to put messages. Closing them here
        // is a bug that only appears on failover BACK.
        new RouterShutdown(tracker, drainTimeout, TRANSITION_TIMEOUT)
                .standDown(loops.values().stream().map(Loop::thread).toList(), consumers, manager.pools().values());
        loops.clear();
        manager.forgetConsumers();
    }

    /// Stops the router for good (R-49): stop intake, let what is in the air
    /// finish within the drain budget, hand the rest back to the broker, and
    /// then — unlike a leadership loss — close the pools and consumers, since
    /// nothing will ever use them again.
    @Override
    public void close() {
        shutDownSources();
        election.close();
        manager.close();
    }

    /// The terminal counterpart of [#stopSources]: the same order, but the
    /// pools are closed rather than merely emptied, because this process is
    /// not coming back as leader.
    private synchronized void shutDownSources() {
        if (!running) {
            return;
        }
        log.info("router stopping; draining in-flight work and closing pools");
        running = false;
        var inFlight = initialApply;
        if (inFlight != null) {
            inFlight.interrupt();
        }
        var consumers = manager.consumerNames().stream()
                .map(manager::activeConsumer)
                .flatMap(Optional::stream)
                .toList();
        new RouterShutdown(tracker, drainTimeout, TRANSITION_TIMEOUT)
                .shutdown(loops.values().stream().map(Loop::thread).toList(), consumers, manager.pools().values());
        loops.clear();
        manager.forgetConsumers();
    }

    /// The queues a fixed configuration describes, for a caller assembling a
    /// single-tenant deployment without a config service.
    public static ConfigSource fixed(List<QueueConfig> queues, RouterConfig config) {
        return ConfigSource.fixed(new RouterConfig(config.processingPools(), queues));
    }
}
