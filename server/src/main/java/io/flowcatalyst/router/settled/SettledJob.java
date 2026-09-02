package io.flowcatalyst.router.settled;

/// One dispatch job the router ACKed off the broker as an untried
/// `BLOCK_ON_ERROR` sibling — paired with the scheduler-signed HMAC token the
/// router already held for it ([io.flowcatalyst.router.wire.Message#authToken],
/// the same token forwarded as `Authorization: Bearer` when delivering to
/// `/api/dispatch/process`). `settled.Handler` verifies each pair
/// independently, exactly like the processing endpoint's own verifier, so the
/// platform never has to trust the router with a separate credential
/// (`docs/spec/dispatch-seam.md` §6).
///
/// @param id    the dispatch job id (== the message id)
/// @param token the scheduler-signed auth token, never null or blank — a
///              sibling that carries neither is not a platform job and is
///              skipped before a [SettledJob] is ever built for it
public record SettledJob(String id, String token) {
}
