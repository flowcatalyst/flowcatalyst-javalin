package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.DnsLabel;

import java.util.Objects;

/// The platform surface a function host talks to (`docs/spec/function-api.md`
/// §6): the desired-state document and the heartbeat. [HttpControlPlane] is
/// the real implementation; tests script a fake one
/// (`docs/spec/function-host-reconciler.md` §3).
public interface ControlPlane {

    /// `GET /control/functions/desired-state?pool=<pool>`, `If-None-Match:
    /// <knownEtag>` when `knownEtag` is not `null`.
    ///
    /// @throws ControlPlaneException the request could not be completed —
    ///                                the caller keeps serving what is
    ///                                already loaded (spec §1.2 step 1)
    Fetched desiredState(DnsLabel pool, String knownEtag) throws ControlPlaneException;

    /// `POST /control/functions/heartbeat`.
    ///
    /// @throws ControlPlaneException the request could not be completed — a
    ///                                failed heartbeat is a WARN at the call
    ///                                site, never an exception out of the
    ///                                reconcile loop (spec §1.2 step 5)
    void heartbeat(HeartbeatReport report) throws ControlPlaneException;

    /// The outcome of one [#desiredState] call (spec §1.1).
    sealed interface Fetched permits Fetched.NotModified, Fetched.Changed {

        /// `304`: the caller's `knownEtag` is still current — nothing to
        /// re-prepare, re-load or unload this cycle (spec §1.2 step 1).
        record NotModified() implements Fetched {
        }

        /// `200`, with a fresh `ETag` and the parsed document.
        record Changed(String etag, DesiredDocument document) implements Fetched {
            public Changed {
                Objects.requireNonNull(etag, "etag");
                Objects.requireNonNull(document, "document");
            }
        }
    }
}
