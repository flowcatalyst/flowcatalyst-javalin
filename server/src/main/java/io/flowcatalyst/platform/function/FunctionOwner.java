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

    /// The one spelling of the reserved primary-key value for the platform's
    /// own row in `fn_client_policies` (spec `function-api.md` §4.3 —
    /// supersedes `function-registry.md` §6.4's "only the repository spells
    /// it": [#key] is now the one place, and [ClientPolicyRepository] calls
    /// it rather than holding its own copy). No TSID is ever spelled this
    /// way, so a client policy row can never collide with it.
    String PLATFORM_KEY = "PLATFORM";

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

    /// The `fn_client_policies.client_id` primary-key spelling — never
    /// `null` (spec `function-api.md` §4.3): [Platform] ⇒ [#PLATFORM_KEY];
    /// [Client] ⇒ its id. Also what an audit row names as the entity id for
    /// a policy write, so the platform's own policy audits with a non-null
    /// entity id.
    default String key() {
        return switch (this) {
            case Platform ignored -> PLATFORM_KEY;
            case Client(String clientId) -> clientId;
        };
    }

    /// The inverse of [#key].
    ///
    /// @throws IllegalArgumentException `key` is blank (but not [#PLATFORM_KEY])
    static FunctionOwner fromKey(String key) {
        Objects.requireNonNull(key, "key");
        return PLATFORM_KEY.equals(key) ? new Platform() : ofClientId(key);
    }

    /// The wire spelling used by `FunctionPolicyApi`'s `{owner}` path segment
    /// and the function response's `clientId`-adjacent owner field: the
    /// reserved lower-case literal `"platform"`, or the client id verbatim
    /// (spec §4.3 — deliberately not [#PLATFORM_KEY], which is a storage
    /// detail this and [#fromWire] keep off the wire).
    default String toWire() {
        return switch (this) {
            case Platform ignored -> "platform";
            case Client(String clientId) -> clientId;
        };
    }

    /// The inverse of [#toWire].
    ///
    /// @throws IllegalArgumentException `wire` is blank (but not `"platform"`)
    static FunctionOwner fromWire(String wire) {
        Objects.requireNonNull(wire, "wire");
        return "platform".equals(wire) ? new Platform() : ofClientId(wire);
    }
}
