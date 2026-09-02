package io.flowcatalyst.router.settled;

/// What a pool does with a `BLOCK_ON_ERROR` group's untried siblings once
/// [io.flowcatalyst.router.pool.OrderedGroups.HeadFailure.BlockGroup] has
/// already taken them out of the buffer — the A-01 gate
/// (`docs/spec/router-completion.md` §2 ruling 3; router-specification.md
/// §0, §3.2, §5.4).
///
/// The router-specification's §0 is explicit that ACKing these siblings is a
/// **MUST NOT** until the platform half exists: an ACKed sibling with
/// nothing marking it recoverable platform-side is silent data loss with no
/// recovery path. The gate is the presence of a platform base URL — nothing
/// more nuanced — so this is a two-member sealed choice, not a boolean, to
/// keep every construction site honest about which behaviour it is asking
/// for.
public sealed interface BlockedSiblings {

    /// **The default.** The untried siblings are NACKed back to the broker
    /// with delay [io.flowcatalyst.router.pool.Pool#REJECTED_NACK_DELAY] and
    /// reason `"rejected-group-released"`, exactly as the pre-ruling
    /// behaviour did — the broker's own redelivery becomes the de-facto new
    /// head, which is not the ordering `BLOCK_ON_ERROR` promises, but it is
    /// the only choice that does not risk losing the messages outright while
    /// no platform exists to mark them recoverable.
    record Release() implements BlockedSiblings {
    }

    /// The untried siblings are ACKed off the broker (reason
    /// `"rejected-group-blocked"`, alongside the failed head) and handed to
    /// [reporter] — fire-and-forget, and only after every ACK has already
    /// happened. Selecting this without the platform's re-queue and reaper
    /// existing recreates exactly the data-loss risk §0 forbids; the
    /// composition root ([io.flowcatalyst.server.Router]) is the only place
    /// that decides, from whether a platform URL is configured.
    record Settle(SettledReporter reporter) implements BlockedSiblings {
    }
}
