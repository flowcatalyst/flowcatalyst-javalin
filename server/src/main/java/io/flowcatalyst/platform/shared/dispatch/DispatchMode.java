package io.flowcatalyst.platform.shared.dispatch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// How a message is sequenced relative to its message group — the router's
/// wire field, the dispatch-job's stored column, and the subscription's
/// dispatch setting are all this one type (ledger `X-01`, merged
/// 2026-09-02: the router's `router.wire.DispatchMode` and the platform's
/// `platform.subscription.DispatchMode` used to be two separate enums with
/// **opposite defaults** — the router already defaulted absent/unrecognised
/// to [#NEXT_ON_ERROR], the subscription silently defaulted to
/// [#IMMEDIATE]. One enum, one default, everywhere.
///
/// The wire form is the enum name. Parsing is **lenient by contract**: any
/// unrecognised value, including an absent field or column, takes
/// [#DEFAULT] rather than throwing (`docs/spec/router.md` §2.6). That
/// leniency is load-bearing on every side of the port: the poller's
/// per-mode filter relies on it, a message published before `dispatchMode`
/// was carried at all must still route, and a `msg_subscriptions` /
/// `msg_dispatch_jobs` row written before this merge must still read.
public enum DispatchMode {

    /// No ordering. Dispatched concurrently, one worker per message, bounded
    /// only by the pool's concurrency.
    IMMEDIATE,

    /// Per-group FIFO, but a failed head does **not** hold the group: the
    /// group continues with the next message, and the failed one waits for
    /// human review (ignore / completed / resend) before the platform
    /// re-queues it.
    ///
    /// Recorded here for years as a deviation from Go, which used to block
    /// the group for both ordered modes. **Go adopted this in `8804827`
    /// (2026-08-25); the two now agree.** Owner ruling, `docs/spec/router.md`
    /// §2.6 / §13 Q1.
    NEXT_ON_ERROR,

    /// Strict per-group FIFO. A failed head blocks the group; the router
    /// ACKs the queued siblings off the broker because the platform re-sends
    /// the whole group once the failure is resolved.
    BLOCK_ON_ERROR;

    private static final Logger log = LoggerFactory.getLogger(DispatchMode.class);

    /// What an unspecified `dispatchMode` means, at every layer that reads
    /// one: the wire parse fallback, the fan-out mode string, a subscription
    /// create with no `mode`, and a stored row whose column is `NULL` or
    /// holds something this enum no longer recognises.
    ///
    /// **[#NEXT_ON_ERROR], not [#IMMEDIATE]** (ledger `X-01`/`A-09`) — the
    /// two failure modes are not symmetric. A producer that wanted
    /// concurrency and got ordering sees lower throughput, notices, and sets
    /// `IMMEDIATE`: cheap, and obvious. A producer that needed ordering and
    /// silently got none sees nothing at all — the messages are delivered
    /// out of order, the damage is in the target's data rather than in the
    /// router, and it is discovered long afterwards, if ever. Defaulting to
    /// the safe side costs throughput; defaulting to the fast side costs
    /// correctness, invisibly. `IMMEDIATE` is the only mode with no
    /// ordering at all, so it must never be where an absent or malformed
    /// value silently lands.
    ///
    /// A message with no `messageGroupId` is still dispatched concurrently
    /// regardless — see `Message#ordered()` — so this default does not
    /// serialise traffic that has no group to be ordered within.
    public static final DispatchMode DEFAULT = NEXT_ON_ERROR;

    /// The lenient parser. `null`, blank and unrecognised all yield
    /// [#DEFAULT] — never an exception, and never `null`.
    ///
    /// An unrecognised value is **logged**, unlike an absent one. A typo is
    /// a producer bug, and silently folding it into the default is how a
    /// wrong default hides: the old `IMMEDIATE` subscription default meant a
    /// misspelled mode quietly turned ordering off, which is exactly the
    /// failure that leaves no trace anywhere. A non-blank, non-`null` value
    /// is trimmed before matching, but is otherwise matched exactly
    /// (case-sensitive) against the three constant names — `"immediate"` and
    /// `"NEXT-ON-ERROR"` are both unrecognised, not accepted spellings.
    @JsonCreator
    public static DispatchMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        return switch (raw.trim()) {
            case "IMMEDIATE" -> IMMEDIATE;
            case "NEXT_ON_ERROR" -> NEXT_ON_ERROR;
            case "BLOCK_ON_ERROR" -> BLOCK_ON_ERROR;
            default -> {
                log.warn("unrecognised dispatch mode \"{}\"; using {}", raw, DEFAULT);
                yield DEFAULT;
            }
        };
    }

    @JsonValue
    public String wireValue() {
        return name();
    }

    /// Whether a message/job in this mode must be sequenced within its
    /// message group.
    ///
    /// This is the predicate the pool branches on, so it — not the field
    /// being populated — is what a conformance test must assert.
    public boolean requiresOrdering() {
        return this != IMMEDIATE;
    }
}
