package io.flowcatalyst.http.javalin;

/// The Javalin adapter's implementation of `Exchange.skipRemainingHandlers()`.
///
/// Javalin's own `Context.skipRemainingHandlers()` stops the `before`/route
/// chain but **also** skips every `after` — verified empirically against
/// javalin 7.2.3: a `before` that calls it never reaches a plain
/// `cfg.routes.after(...)`, while a `before` that *throws* does still run
/// `after` afterward. That contradicts `docs/spec/http-seam.md` §1's stated
/// contract for `skipRemainingHandlers()` ("`after` filters still run") and
/// §2 rule 3 (a thrown exception's `after`-still-runs guarantee, which the
/// spec evidently means to extend to this method too).
///
/// So `JavalinExchange.skipRemainingHandlers()` does not delegate to
/// Javalin's method at all: it throws this marker instead, and
/// [JavalinAdapter#install] registers a no-op mapper for it — the `before`
/// has already written the response it wants, so nothing further needs
/// writing, and Javalin's normal "after still runs when the handler chain
/// throws" behaviour delivers exactly the spec's contract.
final class SkipRemainingHandlersSignal extends RuntimeException {

    SkipRemainingHandlersSignal() {
        super(null, null, false, false);
    }
}
