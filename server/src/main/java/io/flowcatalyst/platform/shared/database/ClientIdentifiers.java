package io.flowcatalyst.platform.shared.database;

import org.jooq.DSLContext;

import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.TNT_CLIENTS;

/// The identifier of the client with an id — the one lookup the
/// client-scoped dispatch pools, connections and subscriptions store beside
/// their client id when they are created (`docs/go-mirror/2026-10-10-platform-bug-fixes.md`,
/// "client identifier"). The SPA shows it as their scope, and the router
/// names a client pool's processing pool `{clientIdentifier}-{code}` from it.
public final class ClientIdentifiers {

    private ClientIdentifiers() {
    }

    /// Empty for no client (a platform-wide resource) or an id that names no
    /// client — no error, as Go.
    public static Optional<String> of(DSLContext dsl, String clientId) {
        if (clientId == null || clientId.isBlank()) return Optional.empty();
        return dsl.select(TNT_CLIENTS.IDENTIFIER).from(TNT_CLIENTS).where(TNT_CLIENTS.ID.eq(clientId))
                .fetchOptional(TNT_CLIENTS.IDENTIFIER);
    }
}
