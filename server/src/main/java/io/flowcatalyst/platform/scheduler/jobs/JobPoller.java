package io.flowcatalyst.platform.scheduler.jobs;

import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstance;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobInstanceRepository;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/// The poller (`docs/spec/scheduled-job-scheduler.md` §2): every tick, for
/// every `ACTIVE` job, fires the latest cron slot that fell in the window
/// since it last fired — skip-missed, so a job paused for a day fires once
/// on resume.
///
/// **No `SKIP LOCKED` claim** (spec §1, D3): correctness rests entirely on
/// [#leader] gating every tick to a single instance. A failed instance
/// insert is logged and the job is skipped this tick — the slot is retried
/// next tick, since [ScheduledJobRepository#markFired] is only called after
/// a successful insert. A failed `markFired` is logged and NOT retried
/// (spec §6 D1: the same slot would insert a duplicate instance next tick,
/// a known, rare defect the two writes not being one transaction).
public final class JobPoller {

    private static final Logger LOG = LoggerFactory.getLogger(JobPoller.class);

    private final ScheduledJobRepository jobs;
    private final ScheduledJobInstanceRepository instances;
    private final BooleanSupplier leader;

    public JobPoller(ScheduledJobRepository jobs, ScheduledJobInstanceRepository instances, BooleanSupplier leader) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.leader = Objects.requireNonNull(leader, "leader");
    }

    /// Runs one tick. A non-leader tick is a no-op, not an error (spec §1).
    public void pollOnce() {
        if (!leader.getAsBoolean()) {
            return;
        }
        Instant now = Instant.now();
        for (ScheduledJob job : jobs.findActive()) {
            pollOne(job, now);
        }
    }

    private void pollOne(ScheduledJob job, Instant now) {
        Instant after = job.lastFiredAt() != null ? job.lastFiredAt() : job.createdAt();
        Optional<Instant> slot = job.latestSlotInWindow(after, now);
        if (slot.isEmpty()) {
            return;
        }
        ScheduledJobInstance instance = ScheduledJobInstance.cron(job, slot.get(), now);
        try {
            instances.insert(instance);
        } catch (RuntimeException e) {
            LOG.warn("failed to insert CRON instance for scheduled job {}; slot {} will be retried next tick",
                    job.id(), slot.get(), e);
            return;
        }
        try {
            jobs.markFired(job.id(), slot.get());
        } catch (RuntimeException e) {
            LOG.warn("markFired failed for scheduled job {} slot {}; last_fired_at is now behind — "
                    + "the same slot will fire again next tick (spec D1)", job.id(), slot.get(), e);
        }
    }
}
