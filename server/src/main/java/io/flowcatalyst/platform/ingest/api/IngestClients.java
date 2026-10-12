package io.flowcatalyst.platform.ingest.api;

import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.httperror.HttpError;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/// The ingest client rules (`docs/spec/sdk-ingest.md` §3.4; Go
/// `internal/platform/shared/sdk/client_code.go`, owner rulings 2026-10-12,
/// F14): which client an ingested row is written under, or the refusal.
/// Throughout, a `clientId` or `clientCode` that is absent, `null`, empty or
/// only whitespace counts as not given.
///
/// An instance memoises `clientCode` → client id for the life of one request
/// (spec §3.1, §4.3): a 1000-item batch costs one lookup per distinct code.
/// Not thread-safe; build one per request.
final class IngestClients {

    /// The refusal of a platform-scoped write by a caller that is not an
    /// anchor and does not reach exactly one client.
    static final String PLATFORM_SCOPED_REFUSED =
            "clientId is required: a client-scoped caller cannot write platform-scoped rows";

    private final Function<String, Optional<Client>> lookup;
    private final Map<String, Optional<String>> cache = new HashMap<>();

    IngestClients(Function<String, Optional<Client>> lookup) {
        this.lookup = lookup;
    }

    /// The id of the client whose identifier is `code`, or empty when there is none.
    Optional<String> resolve(String code) {
        return cache.computeIfAbsent(code, c -> lookup.apply(c).map(Client::id));
    }

    /// `s`, or `null` when it is absent or only whitespace.
    static String nonBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /// The ingest client guard (security-fixes S3.2; Go `RequireWritableClient`):
    /// the client a row is written under, or a 403 `FORBIDDEN`.
    ///
    ///   - A `clientId` the caller cannot reach: "No access to client: <clientId>".
    ///   - No `clientId`: an anchor writes a platform-scoped row (`null`); a
    ///     caller confined to exactly one client writes under that client
    ///     (unambiguous, and what an SDK outbox, which never sends a
    ///     `clientId` on a dispatch job, needs under a client-scoped
    ///     credential); anyone else is refused, since a platform-scoped event
    ///     matches platform-wide subscriptions and a platform-scoped job is
    ///     outside every tenant's view.
    static String requireWritableClient(AuthContext ac, String clientId) {
        String id = nonBlank(clientId);
        if (id == null) {
            if (ac.isAnchor()) return null;
            if (ac.clients().size() == 1) return ac.clients().getFirst();
            throw HttpError.forbidden(PLATFORM_SCOPED_REFUSED);
        }
        if (!ac.canAccessClient(id)) {
            throw HttpError.forbidden("No access to client: " + id);
        }
        return id;
    }

    /// The events-batch client rule (Go `ResolveEventClient`): an explicit
    /// `clientId` wins and goes through [#requireWritableClient]; otherwise a
    /// `clientCode` naming a client the caller reaches resolves to it; one
    /// naming another client, or none, from a non-anchor is 403 "No access to
    /// client: <clientCode>" (one refusal for both: it neither confirms that
    /// the code exists nor reveals the other tenant's client id); an anchor's
    /// unknown code leaves the event platform-scoped (the event is a fact;
    /// spec §5 D2). Neither: [#requireWritableClient] with no client.
    String resolveEventClient(AuthContext ac, String clientId, String clientCode) {
        if (nonBlank(clientId) != null) return requireWritableClient(ac, clientId);
        String code = nonBlank(clientCode);
        if (code == null) return requireWritableClient(ac, null);
        String id = resolve(code).orElse(null);
        if (id == null && ac.isAnchor()) return null;
        if (id == null || !ac.canAccessClient(id)) {
            throw HttpError.forbidden("No access to client: " + code);
        }
        return id;
    }
}
