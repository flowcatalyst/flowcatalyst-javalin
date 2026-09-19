package io.flowcatalyst.fnhost.load;

/// The result of one [JvmFunctionLoader#load] attempt. A refusal is a
/// routine outcome, not an exception (`docs/spec/function-host-core.md`
/// §2.2) — the caller switches exhaustively rather than catching.
public sealed interface LoadOutcome permits Loaded, Refused {
}
