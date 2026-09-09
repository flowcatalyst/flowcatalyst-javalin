package io.flowcatalyst.platform.mail;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// Delivers what [OutboxMailService] queued (`docs/spec/mail-outbox.md` §2),
/// off the request path entirely — the [DispatchJobReaper] shape (start/close,
/// a background loop with its own stop signal, CONVENTIONS §5). Every
/// [#interval] (2 s in production) it claims up to [#BATCH_SIZE] due rows and
/// hands each to `transport` on the sender's own virtual thread — never on a
/// request — so one slow SMTP conversation cannot delay another message's
/// delivery within the same tick. Timed waits in this loop (the tick sleep,
/// waiting for a batch's deliveries) are fine: this is a background platform
/// thread, not a request path (`docs/spec/admission.md` §0 does not apply
/// here).
public final class MailSender implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MailSender.class);

    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);
    static final int BATCH_SIZE = 10;

    /// After this many attempts a row is terminal `FAILED` (spec §2, §4 row 4).
    static final int MAX_ATTEMPTS = 6;

    /// The dispatch-job ladder ([io.flowcatalyst.platform.dispatchjob.processing.ProcessingApi]'s
    /// own `BACKOFF_LADDER_SECONDS`, private there — duplicated here rather
    /// than exposed, spec §2's explicit callout of the shared design).
    /// Index `attempts - 1` (1-based attempt number), clamped to the last rung.
    static final int[] BACKOFF_LADDER_SECONDS = {5, 15, 30, 60, 120};

    /// How long a claimed row's `next_attempt_at` lease holds it out of a
    /// second claimer's `WHERE next_attempt_at <= now` (spec §4 row 5).
    /// Comfortably above [SmtpMailService]'s own 30 s connect/read timeouts
    /// so a genuinely in-flight delivery is never re-claimed out from under
    /// itself; a sender that crashes mid-delivery leaves the row reclaimable
    /// after this window, not forever.
    static final Duration CLAIM_LEASE = Duration.ofSeconds(60);

    private final MailOutboxRepository repository;
    private final MailService transport;
    private final Clock clock;
    private final Duration interval;
    private final Thread loopThread;
    private final ExecutorService deliveryExecutor;

    private final AtomicLong pendingGauge = new AtomicLong();
    private final LongAdder sentTotal = new LongAdder();
    private final LongAdder failedTotal = new LongAdder();

    private MailSender(MailOutboxRepository repository, MailService transport, Clock clock, Duration interval) {
        this.repository = repository;
        this.transport = transport;
        this.clock = clock;
        this.interval = interval;
        this.deliveryExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.loopThread = Thread.ofVirtual().name("mail-sender").unstarted(this::loop);
    }

    public static MailSender start(DataSource pool, MailService transport, Clock clock, Duration interval) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(interval, "interval");
        var sender = new MailSender(new MailOutboxRepository(pool), transport, clock, interval);
        sender.loopThread.start();
        return sender;
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            tickSafely();
            if (!sleep(interval)) return;
        }
    }

    /// One claim + deliver pass, exposed directly for tests. Never throws:
    /// a claim failure (DB down) is logged and retried next tick, same as
    /// [DispatchJobReaper#sweepSafely].
    void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.error("mail sender tick failed; will retry next tick", e);
        }
    }

    private void tick() {
        Instant now = clock.instant();
        List<MailOutboxRepository.ClaimedMail> claimed;
        try {
            claimed = repository.claimDue(BATCH_SIZE, now, CLAIM_LEASE);
        } finally {
            // Sampled once per tick (spec §2's metric contract), not per scrape:
            // this is a background loop, so "how big is the backlog right now"
            // is naturally a per-tick observation.
            pendingGauge.set(repository.countPending());
        }
        if (claimed.isEmpty()) {
            return;
        }
        List<Future<?>> deliveries = new ArrayList<>(claimed.size());
        for (MailOutboxRepository.ClaimedMail c : claimed) {
            deliveries.add(deliveryExecutor.submit(() -> deliver(c)));
        }
        for (Future<?> f : deliveries) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // deliver() catches everything itself; a task should never
                // actually complete exceptionally, but a tick must never die
                // on one bad row either way.
                LOG.error("mail delivery task failed unexpectedly", e.getCause());
            }
        }
    }

    private void deliver(MailOutboxRepository.ClaimedMail c) {
        try {
            transport.send(new Mail(c.to(), c.subject(), c.html()));
            repository.markSent(c.id(), clock.instant());
            sentTotal.increment();
        } catch (RuntimeException e) {
            failedTotal.increment();
            recordFailure(c, e);
        }
    }

    private void recordFailure(MailOutboxRepository.ClaimedMail c, RuntimeException cause) {
        int attempts = c.attempts() + 1;
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        try {
            if (attempts >= MAX_ATTEMPTS) {
                repository.markDead(c.id(), attempts, error);
            } else {
                int index = Math.min(attempts - 1, BACKOFF_LADDER_SECONDS.length - 1);
                Instant nextAttemptAt = clock.instant().plusSeconds(BACKOFF_LADDER_SECONDS[index]);
                repository.markFailed(c.id(), attempts, nextAttemptAt, error);
            }
        } catch (RuntimeException markFailure) {
            LOG.atError().setMessage("mail sender: failed to record delivery failure; the claim lease expiring "
                            + "is what re-queues it")
                    .addKeyValue("id", c.id())
                    .setCause(markFailure)
                    .log();
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

    /// `fc_mail_outbox_pending` (gauge), `fc_mail_sent_total` /
    /// `fc_mail_failed_total` (counters) — style of
    /// [io.flowcatalyst.platform.auth.login.AuthAlarms#collector()].
    public MultiCollector collector() {
        return () -> MetricSnapshots.builder()
                .metricSnapshot(GaugeSnapshot.builder()
                        .name("fc_mail_outbox_pending")
                        .help("mail_outbox rows still PENDING, sampled once per sender tick.")
                        .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().value(pendingGauge.get()).build())
                        .build())
                .metricSnapshot(CounterSnapshot.builder()
                        .name("fc_mail_sent_total")
                        .help("Outbound messages delivered by the mail sender.")
                        .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder().value(sentTotal.sum()).build())
                        .build())
                .metricSnapshot(CounterSnapshot.builder()
                        .name("fc_mail_failed_total")
                        .help("Outbound message delivery attempts that failed (retried or, past the ladder, dead).")
                        .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder().value(failedTotal.sum()).build())
                        .build())
                .build();
    }

    /// Stops the periodic sweep: interrupts the loop (wakes its sleep or its
    /// in-flight batch wait) and the delivery pool (in-flight virtual thread
    /// deliveries), then joins the loop thread. Spec §2: nothing is drained —
    /// a delivery interrupted mid-flight simply waits out its claim lease and
    /// is re-claimed on the next start.
    @Override
    public void close() {
        loopThread.interrupt();
        deliveryExecutor.shutdownNow();
        try {
            loopThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Whether [#close()] has run — the loop thread has terminated.
    public boolean isClosed() {
        return !loopThread.isAlive();
    }
}
