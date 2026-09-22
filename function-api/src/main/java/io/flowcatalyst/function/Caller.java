package io.flowcatalyst.function;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    /// this call ever reached [Function#handle]. Every field comes straight
    /// off the verified token (`docs/spec/function-caller-claims.md` §1);
    /// `email`/`name` are deliberately NOT carried — a function has no
    /// business with them, and they are PII the token happens to hold. The
    /// methods below restate the platform's own authorisation rules exactly
    /// (`function-caller-claims.md` §2 pins the two copies equal) — a
    /// function's own check is one line and answers exactly as the
    /// platform's own would.
    ///
    /// @param id             the principal's id (JWT `sub`)
    /// @param type            the principal's kind (e.g. `user`, `service-account`)
    /// @param tier            the tenancy tier (`ANCHOR` / `PARTNER` / `CLIENT`), or `null` when absent
    /// @param clients         tenant ids this principal can access, verbatim from the token
    /// @param roles           assigned role codes
    /// @param applications    explicit application ids; ignored when [#allApplications]
    /// @param allApplications access to every application, present and future
    /// @param permissions     flattened permission codes (4-segment, `*` wildcards allowed)
    record Principal(String id, String type, String tier, List<String> clients, List<String> roles,
                      List<String> applications, boolean allApplications, Set<String> permissions)
            implements Caller {
        public Principal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            clients = Copies.list(clients);
            roles = Copies.list(roles);
            applications = Copies.list(applications);
            permissions = Copies.set(permissions);
        }

        /// Whether a held permission satisfies `required` — a copy of the
        /// platform's own [io.flowcatalyst.platform.shared.auth.Permission#matches]:
        /// exact match, or a held code with the same segment count whose
        /// non-wildcard (`*`) segments equal `required`'s. `null` is never
        /// satisfied.
        public boolean hasPermission(String required) {
            if (required == null) return false;
            for (String held : permissions) {
                if (matches(held, required)) return true;
            }
            return false;
        }

        /// Any of `required` is held.
        public boolean hasAnyPermission(String... required) {
            for (String r : required) {
                if (hasPermission(r)) return true;
            }
            return false;
        }

        /// Every one of `required` is held.
        public boolean hasAllPermissions(String... required) {
            for (String r : required) {
                if (!hasPermission(r)) return false;
            }
            return true;
        }

        /// `roles` carries role codes verbatim (no matching rule needed).
        public boolean hasRole(String code) {
            return roles.contains(code);
        }

        /// A copy of [io.flowcatalyst.platform.shared.auth.AuthContext#isAnchor].
        public boolean isAnchor() {
            return "ANCHOR".equals(tier);
        }

        /// A copy of [io.flowcatalyst.platform.shared.auth.AuthContext#canAccessClient]:
        /// an anchor always; otherwise the id must be in [#clients].
        public boolean canAccessClient(String clientId) {
            return isAnchor() || clients.contains(clientId);
        }

        /// A copy of [io.flowcatalyst.platform.shared.auth.AuthContext#canAccessApplication],
        /// by application **id** — [#allApplications], or the id is in [#applications].
        public boolean canAccessApplication(String applicationId) {
            return allApplications || applications.contains(applicationId);
        }

        /// The one client this principal is scoped to, when unambiguous: exactly
        /// one entry in [#clients] that is not the anchor wildcard `*`. Empty for
        /// zero, two-or-more, or a lone `*` entry.
        public Optional<String> clientId() {
            if (clients.size() == 1 && !"*".equals(clients.get(0))) {
                return Optional.of(clients.get(0));
            }
            return Optional.empty();
        }

        /// `io.flowcatalyst.platform.shared.auth.Permission#matches`, copied verbatim —
        /// equal strings, or same segment count with every held segment either `*`
        /// or equal to the required one. The agreement test in the `server` module
        /// (`function-caller-claims.md` §2) proves this copy never drifts from the
        /// platform's own.
        private static boolean matches(String held, String required) {
            if (held.equals(required)) return true;
            var h = held.split(":", -1);
            var r = required.split(":", -1);
            if (h.length != r.length) return false;
            for (var i = 0; i < h.length; i++) {
                if (!h[i].equals("*") && !h[i].equals(r[i])) return false;
            }
            return true;
        }
    }

    /// The host performed no authentication (`auth: "none"`) — inbound
    /// third-party webhooks and a function's own sessions land here. The
    /// one instance is shared, same reasoning as [Platform].
    record Anonymous() implements Caller {
        public static final Anonymous INSTANCE = new Anonymous();
    }
}
