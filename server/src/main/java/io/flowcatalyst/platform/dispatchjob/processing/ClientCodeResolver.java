package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Resolves a dispatch job's `clientId` to the client's `identifier` slug —
/// the `clientCode` [DeliveryPayload]'s envelope and the
/// `X-FlowCatalyst-Client` header ([SubscriberDelivery]) both carry
/// (`docs/spec/webhook-client-code.md` R1/R2) — without paying a database
/// round trip per delivery (R3).
///
/// **Cache policy (R3):** a client's `identifier` is immutable after create,
/// so a **hit is cached for the process's life** in [#hits] — one lookup per
/// resolvable client id, ever. A **miss is never cached**, deliberately, not
/// as an oversight: misses are the rare, anomalous case (a dangling
/// `clientId`, or a delivery racing the client's own creation), so paying
/// the query again each time costs nothing in the common (hit) path, and it
/// is the simplest way to guarantee a client created after an earlier miss
/// resolves on the very next delivery — no bounded-window timer to get
/// wrong, no invalidation to forget.
///
/// [io.flowcatalyst.platform.scheduler.PoolCodeResolver] was considered as a
/// base and rejected: it lives on the scheduler (not the processing
/// endpoint), and it refreshes a *whole-table* snapshot on a fixed TTL,
/// which would make even a hit here stale for up to that TTL — exactly the
/// staleness R3 says a resolved identifier must never have.
///
/// A repository failure resolves to "unknown" (`null`) — the delivery goes
/// ahead without the code and header (R3) — and is logged at most once per
/// client id via [#loggedFailures], never once per job, so a downed
/// database does not turn into a log flood.
public final class ClientCodeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ClientCodeResolver.class);

    /// The one [io.flowcatalyst.platform.client.ClientRepository] method this
    /// needs, extracted as a seam ([ProcessingRepository] is the model) so a
    /// test can count lookups, or force a miss/failure, without a real
    /// database. [io.flowcatalyst.platform.client.ClientRepository#findById]
    /// implements this directly via a method reference.
    @FunctionalInterface
    public interface Lookup {
        Optional<Client> findById(String id);
    }

    private final Lookup clients;

    /// `clientId -> identifier`. Never evicted — see the class doc.
    private final ConcurrentHashMap<String, String> hits = new ConcurrentHashMap<>();

    /// Client ids a repository failure has already been logged for — caps
    /// the WARN to once per client, not once per job.
    private final Set<String> loggedFailures = ConcurrentHashMap.newKeySet();

    public ClientCodeResolver(Lookup clients) {
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    /// The only implementation with nothing to resolve against — every job
    /// delivers with no `clientCode` and no header, matching how a
    /// [DeliveryCredentials#none] delivery goes out bare.
    public static ClientCodeResolver none() {
        return new ClientCodeResolver(id -> Optional.empty());
    }

    /// `clientId`'s `identifier` (the `clientCode`), or `null` when
    /// `clientId` is `null`, unresolvable, or the lookup fails.
    public String identifierFor(String clientId) {
        if (clientId == null) {
            return null;
        }
        String cached = hits.get(clientId);
        if (cached != null) {
            return cached;
        }
        Optional<Client> found;
        try {
            found = clients.findById(clientId);
        } catch (RuntimeException e) {
            if (loggedFailures.add(clientId)) {
                LOG.atWarn().setMessage("client code lookup failed; delivering without clientCode")
                        .addKeyValue("id", clientId)
                        .setCause(e)
                        .log();
            }
            return null;
        }
        String identifier = found.map(Client::identifier).orElse(null);
        if (identifier != null) {
            hits.put(clientId, identifier);
        }
        return identifier;
    }
}
