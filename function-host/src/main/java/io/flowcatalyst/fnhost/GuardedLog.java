package io.flowcatalyst.fnhost;

import org.slf4j.Logger;

/// A log call this deep into host trouble (metaspace exhaustion, an `Error`
/// escaping start-up) can itself throw — Logback's own not-yet-loaded
/// classes hitting the SAME exhausted metaspace is observed directly at a
/// real fence (see [io.flowcatalyst.fnhost.reconcile.Reconciler]'s own
/// `logMetaspaceFailureSafely`, which found this first). [#logThrowableSafely]
/// tries the real logger and, ONLY if that call itself throws, falls back to
/// a preallocated `System.err.write(byte[])` line built ahead of time by the
/// caller — never string concatenation at the point of failure (`+` on a
/// non-constant String is `invokedynamic`, itself capable of throwing
/// `OutOfMemoryError: Metaspace`, per the same finding).
///
/// `docs/spec/function-host-process.md` §3 item 2's two guarded call sites
/// ([io.flowcatalyst.fnhost.FnHost#start], [io.flowcatalyst.fnhost.reconcile.ReconcileLoop])
/// share this one implementation — unlike `Reconciler`'s own bespoke
/// version (which additionally has to decide whether a logging failure
/// itself is metaspace-related and worth continuing past, since it sits
/// inside a per-function recovery path that must never abort the rest of
/// the document), this utility's two callers always continue regardless, so
/// it never rethrows.
public final class GuardedLog {

    private GuardedLog() {
    }

    /// @param fallbackLine a precomputed `byte[]` (built once, ahead of
    ///                      time, with no per-call string concatenation) —
    ///                      written verbatim via `System.err.write` if the
    ///                      logger call itself throws
    public static void logThrowableSafely(Logger log, String message, Throwable cause, byte[] fallbackLine) {
        try {
            log.atError().setMessage(message).setCause(cause).log();
        } catch (Throwable loggingFailure) {
            // Deliberately catching Throwable, not just Error: the entire point of this
            // method is that NOTHING from the logging attempt may ever propagate past it.
            writeFallback(fallbackLine);
        }
    }

    private static void writeFallback(byte[] line) {
        try {
            System.err.write(line);
            System.err.flush();
        } catch (Throwable ignored) {
            // Truly nothing left to do.
        }
    }
}
