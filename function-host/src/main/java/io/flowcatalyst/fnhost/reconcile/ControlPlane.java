package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.function.EventEmitException;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.List;
import java.util.Objects;

/// The platform surface a function host talks to (`docs/spec/function-api.md`
/// §6, `function-context.md` §3): the desired-state document, the
/// heartbeat, and emitting events on a loaded function's behalf.
/// [HttpControlPlane] is the real implementation; tests script a fake one
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

    /// `POST /control/functions/events` (spec §3): emits one function's
    /// batch of events, on its behalf, over this host's own credential.
    ///
    /// @throws EventEmitException a non-2xx from the platform (carrying its
    ///                             code and status verbatim) or a transport
    ///                             failure (`UNAVAILABLE`, 503)
    void emit(EmitRequest request);

    /// One `POST /control/functions/events` call (spec §3): `version` is
    /// the loaded version this host is speaking for.
    record EmitRequest(String hostId, FunctionAddress address, int version, List<EmitItem> events) {
        public EmitRequest {
            Objects.requireNonNull(hostId, "hostId");
            Objects.requireNonNull(address, "address");
            events = List.copyOf(events);
        }
    }

    /// One event of an [EmitRequest]'s batch (always a batch of one, spec
    /// §3: "one POST (batch of one)"). `data` is the event's `data` member,
    /// as raw JSON text.
    record EmitItem(String type, String subject, String dedupId, String data, String correlationId,
                     String causationId, String messageGroup) {
        public EmitItem {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(dedupId, "dedupId");
            if (dedupId.isBlank()) {
                throw new IllegalArgumentException("dedupId must not be blank");
            }
        }
    }

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
