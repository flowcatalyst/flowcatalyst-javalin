package io.flowcatalyst.platform.function;

import java.util.Objects;

/// Who a function, a domain or a client policy belongs to (spec
/// `function-registry.md` §6.1, ruling R2): the platform itself, or a
/// specific client. `fn_functions.client_id` and `fn_domains.client_id` are
/// nullable — `NULL` means [Platform]. `fn_client_policies.client_id` is a
/// primary key and so cannot be `NULL`; `ClientPolicyRepository` alone maps
/// [Platform] to and from its reserved `PLATFORM` key (spec §6.4).
///
/// Nothing else in the JVM carries a `null` or a `"PLATFORM"` string to mean
/// the platform — every owner-shaped decision switches on this sealed type.
public sealed interface FunctionOwner {

    /// The platform itself — no client. A singleton in effect (every
    /// instance is equal; a record with no components has one value).
    record Platform() implements FunctionOwner {
    }

    /// A specific client.
    ///
    /// @param clientId the owning client's id; never blank
    record Client(String clientId) implements FunctionOwner {
        public Client {
            Objects.requireNonNull(clientId, "clientId");
            if (clientId.isBlank()) {
                throw new IllegalArgumentException("clientId must not be blank");
            }
        }
    }

    /// The inverse of [#clientIdOrNull]: `null` ⇒ [Platform]; a blank string
    /// is rejected, never coerced to [Platform].
    ///
    /// @throws IllegalArgumentException if `clientId` is blank (but not null)
    static FunctionOwner ofClientId(String clientId) {
        return clientId == null ? new Platform() : new Client(clientId);
    }

    /// [Platform] ⇒ `null`; [Client] ⇒ its id — the shape of the two
    /// nullable `client_id` columns (`fn_functions`, `fn_domains`).
    default String clientIdOrNull() {
        return switch (this) {
            case Platform ignored -> null;
            case Client(String clientId) -> clientId;
        };
    }
}
