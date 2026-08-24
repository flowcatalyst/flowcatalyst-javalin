package io.flowcatalyst.router.inflight;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/// Which messages this process currently owns, and which copy of each owns it.
///
/// A broker may hand the same application message to us more than once — a
/// visibility timeout lapsing mid-delivery, a consumer restarting, another
/// instance releasing it. Without a tracker each copy would be delivered
/// separately. The tracker's job is to decide, for every arriving copy,
/// whether it is **new**, a **redelivery** of something we already hold, or a
/// **duplicate from outside** — and to keep the freshest receipt handle, so
/// the eventual acknowledgement lands on a delivery the broker still knows
/// about (`docs/spec/router.md` §2.3, §4.2).
///
/// Two indexes: application id, and broker id when the backend supplies one.
/// The pair is what makes the three-way distinction possible — see
/// [#register].
public final class InFlightTracker {

    /// The mutable half of an entry. Kept private and only ever touched under
    /// [#lock]; [InFlightMessage] is the immutable view handed out.
    private static final class Entry {
        final String messageId;
        final String brokerMessageId;
        final String poolCode;
        final String queueIdentifier;
        final Instant startedAt;
        final String messageGroupId;
        final String batchId;
        Instant lastSeenAt;
        String receiptHandle;
        int attempts;

        Entry(InFlightMessage message) {
            this.messageId = message.messageId();
            this.brokerMessageId = message.brokerMessageId();
            this.poolCode = message.poolCode();
            this.queueIdentifier = message.queueIdentifier();
            this.startedAt = message.startedAt();
            this.messageGroupId = message.messageGroupId();
            this.batchId = message.batchId();
            this.lastSeenAt = message.lastSeenAt();
            this.receiptHandle = message.receiptHandle();
            this.attempts = message.attempts();
        }

        InFlightMessage snapshot() {
            return new InFlightMessage(messageId, brokerMessageId, poolCode, queueIdentifier,
                    startedAt, lastSeenAt, messageGroupId, batchId, receiptHandle, attempts);
        }
    }

    /// What a copy turned out to be.
    public sealed interface Registration {

        /// Not seen before; this process now owns it. Deliver it.
        record New() implements Registration {
        }

        /// The same delivery arriving again. The owner's receipt handle has
        /// been swapped to this fresher one, so **this copy must simply be
        /// dropped** — not acked, not nacked. Acking it would delete a
        /// delivery the owner is still working on.
        record Redelivery(InFlightMessage owner) implements Registration {
        }

        /// A *different* broker delivery of a message we already own —
        /// another instance released it, or the broker duplicated it. This
        /// copy must be **ACKed on its own receipt handle** so the duplicate
        /// leaves the broker, and must not be delivered.
        record ExternalRequeue(InFlightMessage owner) implements Registration {
        }

        Registration NEW = new New();
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Entry> byMessageId = new HashMap<>();
    private final Map<String, Entry> byBrokerId = new HashMap<>();
    private final Clock clock;

    public InFlightTracker(Clock clock) {
        this.clock = clock;
    }

    /// Claims ownership of an arriving copy, or reports what it really is.
    ///
    /// The three-way decision, in order:
    ///
    /// 1. **Known broker id** → the very same delivery again. Swap the handle
    ///    and refresh liveness; it is a redelivery.
    /// 2. **Known application id.** Now the broker ids decide: two non-empty
    ///    ids that *differ* mean two distinct deliveries of one message —
    ///    an external requeue. Anything else (either id blank, or equal) is
    ///    treated as a redelivery, because we cannot prove otherwise and
    ///    dropping a copy is safe where acking one is not.
    /// 3. Otherwise it is new.
    ///
    /// Step 2's fallback is deliberately the cautious one: mis-classifying a
    /// redelivery as an external requeue would ACK a live delivery.
    public Registration register(InFlightMessage message) {
        lock.lock();
        try {
            var now = clock.instant();
            if (!message.brokerMessageId().isEmpty()) {
                var byBroker = byBrokerId.get(message.brokerMessageId());
                if (byBroker != null) {
                    refresh(byBroker, message.receiptHandle(), now);
                    return new Registration.Redelivery(byBroker.snapshot());
                }
            }
            var owner = byMessageId.get(message.messageId());
            if (owner != null) {
                boolean distinctDeliveries = !owner.brokerMessageId.isEmpty()
                        && !message.brokerMessageId().isEmpty()
                        && !owner.brokerMessageId.equals(message.brokerMessageId());
                if (distinctDeliveries) {
                    return new Registration.ExternalRequeue(owner.snapshot());
                }
                refresh(owner, message.receiptHandle(), now);
                return new Registration.Redelivery(owner.snapshot());
            }
            insert(new Entry(message));
            return Registration.NEW;
        } finally {
            lock.unlock();
        }
    }

    /// Re-asserts an entry at dispatch time, restoring one the reaper pruned
    /// during a long wait.
    ///
    /// @return whether the caller owns the pipeline for this message. False
    ///         means a *different* broker copy owns it, and the caller must
    ///         ACK its own copy rather than delivering it.
    ///
    /// Never swaps the receipt handle: the stored one may be fresher than the
    /// caller's, having been swapped by a redelivery while the caller waited.
    public boolean ensureTracked(InFlightMessage message) {
        lock.lock();
        try {
            var owner = byMessageId.get(message.messageId());
            if (owner == null) {
                insert(new Entry(message));
                return true;
            }
            return owner.brokerMessageId.isEmpty()
                    || message.brokerMessageId().isEmpty()
                    || owner.brokerMessageId.equals(message.brokerMessageId());
        } finally {
            lock.unlock();
        }
    }

    /// Marks an entry as legitimately retrying, so the reaper and the stall
    /// detector leave it alone.
    public void markRetrying(String messageId) {
        lock.lock();
        try {
            var entry = byMessageId.get(messageId);
            if (entry != null) {
                entry.attempts++;
                entry.lastSeenAt = clock.instant();
            }
        } finally {
            lock.unlock();
        }
    }

    /// The freshest receipt handle known for a message — what an
    /// acknowledgement must use.
    ///
    /// Empty when the entry is gone, in which case the caller should fall
    /// back to the handle it was dispatched with rather than skipping the
    /// acknowledgement.
    public Optional<String> freshestHandle(String messageId) {
        lock.lock();
        try {
            var entry = byMessageId.get(messageId);
            return entry == null ? Optional.empty() : Optional.of(entry.receiptHandle);
        } finally {
            lock.unlock();
        }
    }

    /// Releases ownership. Idempotent.
    public void remove(String messageId) {
        lock.lock();
        try {
            var entry = byMessageId.remove(messageId);
            if (entry != null && !entry.brokerMessageId.isEmpty()) {
                // Remove by identity: a later copy may already own the broker
                // index, and evicting that would un-track a live delivery.
                byBrokerId.remove(entry.brokerMessageId, entry);
            }
        } finally {
            lock.unlock();
        }
    }

    /// Drops entries nothing has touched for `maxAge`, as a backstop against
    /// a backend that loses a message without telling us.
    ///
    /// Ages on `lastSeenAt`, not `startedAt` — a message being redelivered is
    /// alive however long ago it started. **Retrying entries are skipped
    /// entirely**: they are slow on purpose, and reaping one would let a
    /// duplicate through while the original is still being worked.
    ///
    /// @return how many were released
    public int reapIdle(Duration maxAge) {
        if (maxAge.isNegative() || maxAge.isZero()) {
            return 0;
        }
        lock.lock();
        try {
            var cutoff = clock.instant().minus(maxAge);
            var stale = new ArrayList<String>();
            byMessageId.values().stream()
                    .filter(entry -> entry.attempts == 0 && entry.lastSeenAt.isBefore(cutoff))
                    .forEach(entry -> stale.add(entry.messageId));
            stale.forEach(this::removeLocked);
            return stale.size();
        } finally {
            lock.unlock();
        }
    }

    /// Everything currently owned, for the monitoring API.
    public List<InFlightMessage> snapshot() {
        lock.lock();
        try {
            return byMessageId.values().stream().map(Entry::snapshot).toList();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return byMessageId.size();
        } finally {
            lock.unlock();
        }
    }

    private void insert(Entry entry) {
        byMessageId.put(entry.messageId, entry);
        if (!entry.brokerMessageId.isEmpty()) {
            byBrokerId.put(entry.brokerMessageId, entry);
        }
    }

    private void removeLocked(String messageId) {
        var entry = byMessageId.remove(messageId);
        if (entry != null && !entry.brokerMessageId.isEmpty()) {
            byBrokerId.remove(entry.brokerMessageId, entry);
        }
    }

    private void refresh(Entry entry, String freshHandle, Instant now) {
        entry.receiptHandle = freshHandle;
        entry.lastSeenAt = now;
    }
}
