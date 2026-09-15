package io.flowcatalyst.parity;

/// One running instance the [Runner] talks to over HTTP (parity-harness
/// spec §1): a `fc-server` subprocess ([SubprocessSide]), or the in-process
/// Java [io.flowcatalyst.server.Server] ([JavaSide]).
public interface Side {

    /// `http://127.0.0.1:<port>` — also this side's own `FC_JWT_ISSUER` /
    /// `FC_EXTERNAL_BASE_URL`, so [Normaliser] rule 2 masks it.
    String baseUrl();

    /// Stops this side. Idempotent.
    void stop();
}
