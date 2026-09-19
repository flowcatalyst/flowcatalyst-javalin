package io.flowcatalyst.function;

import java.util.Objects;
import java.util.Set;

/// The caller of an [HttpInvocation], when the route requires
/// authentication. `permissions` is deep-copied and unmodifiable.
///
/// @param id          the principal's id
/// @param type        the principal's kind (e.g. `user`, `service-account`)
/// @param clientId    the owning client, or `null` when the principal is not
///                    client-scoped
/// @param permissions the permissions the caller was granted
public record Principal(String id, String type, String clientId, Set<String> permissions) {

    public Principal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        permissions = Copies.set(permissions);
    }
}
