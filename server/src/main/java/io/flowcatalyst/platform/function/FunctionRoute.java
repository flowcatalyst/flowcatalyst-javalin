package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// One materialised **public** route entry for a function — a
/// `(hostname, pathPrefix)` pair the public listener resolves to this
/// function (spec `function-invocation.md` §3, §5, amending
/// `function-registry.md` §6.6). Not an aggregate with transitions — a
/// function's published manifest's `public[]` list is the source of truth,
/// and [FunctionRouteRepository#replaceForFunction] keeps this table in
/// sync with it wholesale. A private call
/// (`/functions/{address}/...`) needs no route row at all (spec §2), so
/// every row here is public: `hostname` is never `null`.
///
/// @param id          `fnr_…` TSID
/// @param functionId  the function this route resolves to
/// @param hostname    the public hostname (never `null`)
/// @param pathPrefix  the literal path prefix, stripped before endpoint matching
/// @param aliasPrefixes opt-in alias name prefixes copied from the manifest's
///                    own `public[].aliasPrefixes` (spec
///                    `function-zones-and-aliases.md` §3); `[]` when the
///                    route is exact-match only
/// @param createdAt   creation time
public record FunctionRoute(
        String id,
        String functionId,
        Hostname hostname,
        RoutePattern pathPrefix,
        List<String> aliasPrefixes,
        Instant createdAt) implements HasId {

    public FunctionRoute {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(createdAt, "createdAt");
        aliasPrefixes = List.copyOf(aliasPrefixes);
    }

    public static FunctionRoute of(String functionId, Hostname hostname, RoutePattern pathPrefix,
            List<String> aliasPrefixes, Instant now) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(aliasPrefixes, "aliasPrefixes");
        Objects.requireNonNull(now, "now");
        return new FunctionRoute(EntityType.FUNCTION_ROUTE.generate(), functionId, hostname, pathPrefix,
                aliasPrefixes, now);
    }
}
