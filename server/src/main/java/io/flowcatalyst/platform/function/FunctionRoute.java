package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// One materialised route entry for a function (spec `function-registry.md`
/// §6.6). Not an aggregate with transitions — a function's published
/// manifest is the source of truth, and
/// [FunctionRouteRepository#replaceForFunction] keeps this table in sync
/// with it wholesale.
///
/// @param id         `fnr_…` TSID
/// @param functionId the function this route resolves to
/// @param hostname   `null` = a private-only route (design §4a)
/// @param method     the accepted HTTP method
/// @param pattern    the route's path pattern (spec §5.2)
/// @param createdAt  creation time
public record FunctionRoute(
        String id,
        String functionId,
        Hostname hostname,
        HttpMethod method,
        RoutePattern pattern,
        Instant createdAt) implements HasId {

    public FunctionRoute {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static FunctionRoute of(String functionId, Hostname hostname, HttpMethod method, RoutePattern pattern,
            Instant now) {
        Objects.requireNonNull(functionId, "functionId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(now, "now");
        return new FunctionRoute(EntityType.FUNCTION_ROUTE.generate(), functionId, hostname, method, pattern, now);
    }
}
