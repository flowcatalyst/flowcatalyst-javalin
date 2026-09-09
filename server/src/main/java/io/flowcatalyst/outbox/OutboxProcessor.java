package io.flowcatalyst.outbox;

import io.flowcatalyst.outbox.jfr.OutboxItemSettledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/// The outbox loop (spec §4): every `pollInterval`, leader-gated and
/// back-pressured on `inFlight`, claims a batch, routes grouped items to the
/// [GroupDistributor] and ungrouped items as one batch per
/// [OutboxItemType] on its own virtual thread, then applies the outcome —
/// success deletes the row, failure requeues or writes the terminal status,
/// and a grouped item's permanent failure blocks the group (spec §5) when
/// `blockOnError` is on. A second, independent tick recovers rows stuck
/// `IN_PROGRESS` past the recovery threshold.
public final class OutboxProcessor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxProcessor.class);

    /// The tunables spec §4's `Config` names. `batchSize`/`maxInFlight`/
    /// `pollInterval`/`maxConcurrentGroups`/`blockOnError` are env-driven
    /// (`FC_OUTBOX_*`, resolved by the composition root); `maxRetries`,
    /// `recoveryInterval`, `recoveryThreshold` and `httpTimeout` are not
    /// listed among the spec's env vars and stay hardcoded defaults, exactly
    /// as [io.flowcatalyst.platform.scheduler.DispatchScheduler] keeps its
    /// own un-enumerated cadence constants fixed.
    public record Config(int batchSize, int maxInFlight, Duration pollInterval, int maxConcurrentGroups,
                          boolean blockOnError, int maxRetries, Duration recoveryInterval, Duration recoveryThreshold,
                          Duration httpTimeout) {

        public static final int DEFAULT_BATCH_SIZE = 100;
        public static final int DEFAULT_MAX_IN_FLIGHT = 1000;
        public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
        public static final int DEFAULT_MAX_CONCURRENT_GROUPS = 10;
        public static final boolean DEFAULT_BLOCK_ON_ERROR = true;
        public static final int DEFAULT_MAX_RETRIES = 3;
        public static final Duration DEFAULT_RECOVERY_INTERVAL = Duration.ofSeconds(60);
        public static final Duration DEFAULT_RECOVERY_THRESHOLD = Duration.ofMinutes(5);
        public static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(30);

        /// The spec §4 `Config{}` defaults verbatim.
        public static Config defaults() {
            return new Config(DEFAULT_BATCH_SIZE, DEFAULT_MAX_IN_FLIGHT, DEFAULT_POLL_INTERVAL,
                    DEFAULT_MAX_CONCURRENT_GROUPS, DEFAULT_BLOCK_ON_ERROR, DEFAULT_MAX_RETRIES,
                    DEFAULT_RECOVERY_INTERVAL, DEFAULT_RECOVERY_THRESHOLD, DEFAULT_HTTP_TIMEOUT);
        }
    }

    public record Totals(long succeeded, long failed) {
    }

    private final OutboxRepository repository;
    private final HttpDispatcher dispatcher;
    private final Config config;
    private final BooleanSupplier leader;

    private final GroupStateManager groupStates = new GroupStateManager();
    private final GroupDistributor distributor;

    /// Items claimed but not yet resolved (grouped: submitted to the
    /// distributor; ungrouped: in flight on a batch thread) — the
    /// back-pressure gate `pollOnce` checks before claiming (spec §4).
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong totalSucceeded = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    /// Ungrouped per-type batches, each fire-and-forget on its own thread.
    private final ExecutorService dispatchWorkers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ticker = Executors.newScheduledThreadPool(2, OutboxProcessor::daemonThread);

    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    private static Thread daemonThread(Runnable r) {
        Thread t = new Thread(r, "outbox-processor-" + THREAD_COUNT.getAndIncrement());
        t.setDaemon(true);
        return t;
    }

    public OutboxProcessor(OutboxRepository repository, HttpDispatcher dispatcher, Config config,
                            BooleanSupplier leader) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.config = Objects.requireNonNull(config, "config");
        this.leader = Objects.requireNonNull(leader, "leader");
        this.distributor = new GroupDistributor(config.maxConcurrentGroups(), config.blockOnError());
    }

    /// Starts both ticking loops (spec §4). Idempotent to call once; calling
    /// twice would schedule the ticks twice — callers own the single call.
    public void start() {
        ticker.scheduleWithFixedDelay(this::pollSafely, 0, config.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        ticker.scheduleWithFixedDelay(this::recoverSafely, 0, config.recoveryInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            LOG.warn("outbox poll failed; will retry next tick", e);
        }
    }

    private void recoverSafely() {
        try {
            recoverOnce();
        } catch (RuntimeException e) {
            LOG.warn("outbox recovery failed; will retry next tick", e);
        }
    }

    // ── the poll tick (spec §4) ──────────────────────────────────────────────

    /// One poll tick, synchronous — exposed for tests. Leader-gated and
    /// back-pressured: a non-leader tick, or one at `inFlight >=
    /// maxInFlight`, claims nothing.
    void pollOnce() {
        if (!leader.getAsBoolean()) {
            return;
        }
        if (inFlight.get() >= config.maxInFlight()) {
            return;
        }
        List<OutboxItem> claimed = repository.claimPending(config.batchSize());
        if (claimed.isEmpty()) {
            return;
        }
        Map<OutboxItemType, List<OutboxItem>> ungrouped = new EnumMap<>(OutboxItemType.class);
        for (OutboxItem item : claimed) {
            if (item.grouped()) {
                routeGrouped(item);
            } else {
                ungrouped.computeIfAbsent(item.type(), t -> new ArrayList<>()).add(item);
            }
        }
        ungrouped.forEach(this::dispatchBatchAsync);
    }

    /// A group found inactive (paused or blocked) releases the item
    /// immediately, untouched; otherwise it is handed to the distributor
    /// with `dispatch = send one item`, `onAbort = release` (spec §4).
    private void routeGrouped(OutboxItem item) {
        if (!groupStates.isActive(item.messageGroup())) {
            repository.release(List.of(item.id()));
            return;
        }
        inFlight.incrementAndGet();
        distributor.submit(item.messageGroup(), new GroupDistributor.GroupTask() {
            @Override
            public boolean dispatch() {
                try {
                    return dispatchSingle(item);
                } finally {
                    inFlight.decrementAndGet();
                }
            }

            @Override
            public void onAbort() {
                try {
                    repository.release(List.of(item.id()));
                } finally {
                    inFlight.decrementAndGet();
                }
            }
        });
    }

    /// @return whether the group's drainer may continue — `false` only when
    /// this item's failure was permanent (spec §5).
    private boolean dispatchSingle(OutboxItem item) {
        List<HttpDispatcher.ItemOutcome> outcomes = dispatcher.send(item.type(), List.of(item));
        HttpDispatcher.ItemOutcome outcome = outcomes.isEmpty()
                ? new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.INTERNAL_ERROR, "no outcome for item")
                : outcomes.getFirst();
        return applyOutcome(item, outcome, true);
    }

    private void dispatchBatchAsync(OutboxItemType type, List<OutboxItem> items) {
        inFlight.addAndGet(items.size());
        dispatchWorkers.execute(() -> {
            try {
                List<HttpDispatcher.ItemOutcome> outcomes = dispatcher.send(type, items);
                for (int i = 0; i < items.size(); i++) {
                    HttpDispatcher.ItemOutcome outcome = i < outcomes.size() ? outcomes.get(i)
                            : new HttpDispatcher.ItemOutcome.Failure(OutboxStatus.INTERNAL_ERROR, "no per-item result");
                    applyOutcome(items.get(i), outcome, false);
                }
            } finally {
                inFlight.addAndGet(-items.size());
            }
        });
    }

    /// Applies one item's outcome (spec §4): success deletes the row;
    /// failure requeues (retryable, budget remains) or writes the terminal
    /// status, bumping `retry_count` either way. A grouped item whose
    /// failure is NOT requeued blocks the group under `blockOnError`.
    /// `markSuccess`/`markFailed` failures are logged, never thrown (spec
    /// §4).
    ///
    /// @return whether a grouped drainer may continue (ignored for ungrouped)
    private boolean applyOutcome(OutboxItem item, HttpDispatcher.ItemOutcome outcome, boolean grouped) {
        return switch (outcome) {
            case HttpDispatcher.ItemOutcome.Success ignored -> {
                boolean persisted = safely(() -> repository.markSuccess(List.of(item.id())));
                totalSucceeded.incrementAndGet();
                recordSettled(item, "SUCCESS", "DELIVERED", false, grouped, persisted);
                yield true;
            }
            case HttpDispatcher.ItemOutcome.Failure(var status, var message) -> {
                boolean requeue = status.retryable() && item.retryCount() + 1 < config.maxRetries();
                boolean persisted = safely(() -> repository.markFailed(List.of(item.id()), status, message, requeue));
                totalFailed.incrementAndGet();
                recordSettled(item, "FAILURE", status.name(), requeue, grouped, persisted);
                if (grouped && !requeue && config.blockOnError()) {
                    groupStates.block(item.messageGroup(), item.id(), message);
                    yield false;
                }
                yield true;
            }
        };
    }

    /// @return whether `action` completed without throwing — `false` is
    /// what makes [#recordSettled]'s `persisted` field the thing an operator
    /// needs: "the repository update failed and the item will be re-claimed".
    private boolean safely(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            LOG.warn("outbox repository update failed", e);
            return false;
        }
    }

    /// Records the item's outcome, if anyone is recording
    /// (`docs/spec/jfr-events.md` §2). `shouldCommit()` first so a disabled
    /// recording costs one virtual call and no field writes. Committed after
    /// the repository mark call has returned or thrown — never before.
    private static void recordSettled(OutboxItem item, String outcome, String status, boolean requeued,
                                       boolean grouped, boolean persisted) {
        var event = new OutboxItemSettledEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.itemId = item.id();
        event.type = item.type().name();
        event.group = item.messageGroup();
        event.outcome = outcome;
        event.status = status;
        event.requeued = requeued;
        event.grouped = grouped;
        event.persisted = persisted;
        event.commit();
    }

    // ── the recovery tick (spec §4) ──────────────────────────────────────────

    /// One recovery sweep, synchronous — exposed for tests.
    void recoverOnce() {
        if (!leader.getAsBoolean()) {
            return;
        }
        int recovered = repository.recoverStuck(config.recoveryThreshold());
        if (recovered > 0) {
            LOG.atInfo().setMessage("outbox: recovered stuck item(s)")
                    .addKeyValue("count", recovered)
                    .log();
        }
    }

    // ── admin verbs (spec §5, §7) ────────────────────────────────────────────

    public boolean pauseGroup(String group) {
        return groupStates.pause(group);
    }

    public boolean resumeGroup(String group) {
        return groupStates.resume(group);
    }

    /// `clearBlock` + requeue the poison item with `retry_count` reset
    /// (spec §5).
    ///
    /// @return whether the group was actually blocked
    public boolean unblockGroup(String group) {
        return groupStates.clearBlock(group)
                .map(blocked -> {
                    repository.requeue(List.of(blocked.itemId()));
                    return true;
                })
                .orElse(false);
    }

    /// `clearBlock` WITHOUT requeuing — the poison row keeps its terminal
    /// status (spec §5).
    ///
    /// @return whether the group was actually blocked
    public boolean skipGroup(String group) {
        return groupStates.clearBlock(group).isPresent();
    }

    public List<GroupStateManager.GroupInfo> groupStates() {
        return groupStates.groupStates();
    }

    public List<GroupStateManager.GroupInfo> blockedGroups() {
        return groupStates.blockedGroups();
    }

    public long inFlight() {
        return inFlight.get();
    }

    public Totals totals() {
        return new Totals(totalSucceeded.get(), totalFailed.get());
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    /// Stops both ticks and the distributor. `ScheduledExecutorService
    /// #shutdownNow` interrupts whatever tick is running, so the loop stops
    /// within one poll interval (spec §9).
    @Override
    public void close() {
        ticker.shutdownNow();
        dispatchWorkers.shutdownNow();
        distributor.close();
    }
}
