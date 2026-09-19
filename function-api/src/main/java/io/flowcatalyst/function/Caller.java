package io.flowcatalyst.function;

import java.util.Objects;
import java.util.Set;

/// Who a [Request] came from (`docs/spec/function-invocation.md` §7). A
/// sealed type, not a nullable field, so a function author's `switch`
/// enumerates the three cases the host can ever hand it — one per `auth`
/// mode an endpoint declares (`function-invocation.md` §3):
///
/// | `auth` | `Caller` |
/// |---|---|
/// | `webhook` | [Platform] — a verified delivery: subscription, direct dispatch job or scheduled job |
/// | `platform` | [Principal] — a platform bearer token, verified locally against the platform's JWKS |
/// | `none` | [Anonymous] — the host checked nothing; the function authenticates itself, if at all |
public sealed interface Caller permits Caller.Platform, Caller.Principal, Caller.Anonymous {

    /// A verified webhook delivery. The one instance is shared — there is
    /// nothing to distinguish two platform deliveries by at this level; a
    /// function that needs to know *which* subscription or job fired reads
    /// [Webhook#event] / [Webhook#schedule].
    record Platform() implements Caller {
        public static final Platform INSTANCE = new Platform();
    }

    /// An authenticated platform principal — a user or service-account
    /// bearer token the host verified against the platform's JWKS before
    /// this call ever reached [Function#handle]. The function still decides
    /// what this principal may do; the host only proves who it is.
    ///
    /// @param id          the principal's id
    /// @param type        the principal's kind (e.g. `user`, `service-account`)
    /// @param clientId    the owning client, or `null` when the principal is not client-scoped
    /// @param permissions the permissions the token carries
    record Principal(String id, String type, String clientId, Set<String> permissions) implements Caller {
        public Principal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            permissions = Copies.set(permissions);
        }
    }

    /// The host performed no authentication (`auth: "none"`) — inbound
    /// third-party webhooks and a function's own sessions land here. The
    /// one instance is shared, same reasoning as [Platform].
    record Anonymous() implements Caller {
        public static final Anonymous INSTANCE = new Anonymous();
    }
}
