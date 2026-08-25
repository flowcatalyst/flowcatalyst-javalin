package io.flowcatalyst.router.pool;

import java.time.Instant;

/// One message inside a pool worker **right now** — the live view an operator
/// needs to answer "what is this pool actually doing, and what is stuck?".
///
/// A count alone cannot answer either question. Eight busy workers and eight
/// workers wedged against one dead target look identical as a number; they
/// look nothing alike as rows with targets and elapsed times.
///
/// Never reaped and never persisted: an entry exists exactly as long as the
/// worker is inside the mediator call, so the set cannot drift from the count
/// the way two separately-maintained structures would.
///
/// @param startedAt when THIS attempt entered the worker, not when the message
///                  entered the pipeline — elapsed time here is the current
///                  call's, which is what tells an operator a target is
///                  hanging rather than merely being retried
public record Mediating(String messageId, String poolCode, String group,
                        String queue, String target, int attempts, Instant startedAt) {
}
