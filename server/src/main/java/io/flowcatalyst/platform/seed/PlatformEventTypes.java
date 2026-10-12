package io.flowcatalyst.platform.seed;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/// The platform's built-in event-type catalogue —
/// `internal/platform/seed/event_types.go`. Codes are
/// `{application}:{subdomain}:{aggregate}:{event}`; names are derived
/// (`titleCase(aggregate) + " " + titleCase(event)`) unless grouped under an
/// explicit label or pushed with an explicit name.
public final class PlatformEventTypes {

    /// One catalogue entry. `code` must be four non-empty `:`-separated
    /// segments (`application:subdomain:aggregate:event`) — the invariant the
    /// `msg_event_types` columns rely on, checked once here so the seeder
    /// never re-parses. `schema` is null when the catalogue supplies none
    /// ("no schema attached yet").
    public record Definition(String code, String name, JsonNode schema) {
        public Definition {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
            if (segments(code).length != 4) {
                throw new IllegalArgumentException("event type code must follow format: "
                        + "application:subdomain:aggregate:event: " + code);
            }
            for (String s : segments(code)) {
                if (s.isBlank()) {
                    throw new IllegalArgumentException("event type code segments cannot be empty: " + code);
                }
            }
        }

        public String application() {
            return segments(code)[0];
        }

        public String subdomain() {
            return segments(code)[1];
        }

        public String aggregate() {
            return segments(code)[2];
        }

        public String event() {
            return segments(code)[3];
        }

        private static String[] segments(String code) {
            return code.split(":", -1);
        }
    }

    private static final List<Definition> ALL = build();

    private PlatformEventTypes() {
    }

    /// The full catalogue, in Go's declaration order.
    public static List<Definition> all() {
        return ALL;
    }

    /// Every code here is one an operation emits (`EmittedCatalogueTest` checks
    /// both directions). Older seeds also listed 16 codes nothing emits
    /// (`platform:iam:role:*`, `platform:iam:roles:synced`,
    /// `platform:iam:anchor-domain:*`, `platform:iam:auth-config:*`,
    /// `platform:admin:idp:*`, `platform:admin:edm:*`,
    /// `platform:iam:user:password-reset-requested`); V26 removed them from
    /// existing databases (Go 071).
    private static List<Definition> build() {
        Map<String, JsonNode> schemas = PlatformEventSchemas.all();
        var out = new ArrayList<Definition>(160);
        var b = new Builder(out, schemas);

        // ── platform:iam ────────────────────────────────────────────────────
        b.group("platform:iam:user",
                "created", "updated", "activated", "deactivated", "deleted",
                "roles-assigned", "application-access-assigned",
                "client-access-granted", "client-access-revoked",
                "logged-in", "password-reset-completed",
                "developer-credential-set", "developer-credential-revoked");
        b.push("platform:iam:principals:synced", "Principals Synced");

        b.group("platform:iam:serviceaccount",
                "created", "updated", "deactivated", "deleted", "roles-assigned",
                "token-regenerated", "secret-regenerated", "token-minted");

        // Client lifecycle events are emitted under platform:admin (the client
        // operations, client/operations/ClientEvents); enabling applications for
        // a client is emitted under platform:iam by the application operations.
        // V25 renamed the platform:iam:client:* rows an older seed wrote.
        b.group("platform:iam:client", "applications-updated");

        b.group("platform:iam:application",
                "created", "updated", "activated", "deactivated", "deleted",
                "service-account-provisioned", "enabled-for-client", "disabled-for-client");

        // The passkey and reset-approval operations emit under platform:iam
        // (Go's passkey events are platform:admin:passkey:*; Go has no
        // reset-approval events).
        b.group("platform:iam:passkey", "registered", "authenticated", "revoked");
        b.group("platform:iam:reset-approval", "queued", "decided");

        // ── platform:admin ──────────────────────────────────────────────────
        b.group("platform:admin:client",
                "created", "updated", "activated", "suspended", "deactivated", "deleted", "note-added");

        b.group("platform:admin:cors", "origin-added", "origin-deleted");

        b.group("platform:admin:eventtype",
                "created", "updated", "archived", "deleted",
                "schema-added", "schema-finalised", "schema-deprecated");
        b.push("platform:admin:eventtypes:synced", "Event Types Synced");

        b.group("platform:admin:connection", "created", "updated", "deleted", "synced");

        b.group("platform:admin:dispatch-pool",
                "created", "updated", "suspended", "activated", "archived", "deleted");
        b.push("platform:admin:dispatch-pools:synced", "Dispatch Pools Synced");

        b.group("platform:admin:subscription",
                "created", "updated", "paused", "resumed", "deleted", "synced");

        // Identity and access administration (the auth-admin, identity-provider,
        // email-domain-mapping, OAuth-client and role operations emit these
        // under platform:admin).
        b.group("platform:admin:anchor-domain", "created", "updated", "deleted");
        b.group("platform:admin:auth-config", "created", "updated", "deleted");
        b.groupAs("platform:admin:oauth-client", "OAuth Client",
                "created", "updated", "activated", "deactivated", "deleted",
                "secret-rotated", "previous-secret-revoked");
        b.group("platform:admin:identity-provider", "created", "updated", "deleted");
        b.group("platform:admin:email-domain-mapping",
                "created", "updated", "provider-changed", "deleted");
        b.groupAs("platform:admin:idp-role-mapping", "IdP Role Mapping", "created", "deleted");
        b.group("platform:admin:role",
                "created", "updated", "deleted", "permission-granted", "permission-revoked");
        b.push("platform:admin:roles:synced", "Roles Synced");

        b.group("platform:admin:platform-config", "property-set");

        b.group("platform:admin:process", "created", "updated", "archived", "deleted");
        b.push("platform:admin:processes:synced", "Processes Synced");

        b.group("platform:admin:scheduled-job",
                "created", "updated", "paused", "resumed", "archived", "deleted", "fired-manually");
        b.push("platform:admin:scheduledjobs:synced", "Scheduled Jobs Synced");

        // The requeue operations (Go's resend is platform:messaging:dispatch-jobs:resent).
        b.groupAs("platform:admin:dispatchjob", "Dispatch Job", "requeued");
        b.push("platform:admin:dispatchjobs:requeued", "Dispatch Jobs Requeued");

        b.group("platform:admin:audit-log", "redacted");

        // ── platform:messaging ──────────────────────────────────────────────
        b.group("platform:messaging:dispatch-job", "cancelled", "completed");

        // ── platform:developer ──────────────────────────────────────────────
        b.push("platform:developer:application-openapi:synced", "Application OpenAPI Synced");

        // ── platform:portal ─────────────────────────────────────────────────
        b.groupAs("platform:portal:app", "Portal App", "created", "updated", "deleted");
        b.groupAs("platform:portal:identity", "Portal Identity",
                "ensured", "status-set", "deleted", "app-granted", "app-revoked");

        // ── platform:function ───────────────────────────────────────────────
        // The function platform's own events (FunctionEvents; Go's function
        // runner emits a different set under platform:admin:function*).
        b.groupAs("platform:function:function", "Function", "created", "updated", "deleted");
        b.groupAs("platform:function:version", "Function Version", "published", "ready", "retired");
        b.groupAs("platform:function:alias", "Function Alias", "changed", "removed");
        b.groupAs("platform:function:config", "Function Config", "updated");
        b.groupAs("platform:function:secret", "Function Secret", "set", "deleted");
        b.groupAs("platform:function:policy", "Function Policy", "updated");
        b.groupAs("platform:function:domain", "Function Domain", "claimed", "released");

        return List.copyOf(out);
    }

    /// Appends definitions for one aggregate (`group`) or a single explicitly
    /// named code (`push`), looking each code's schema up as it goes.
    private record Builder(List<Definition> out, Map<String, JsonNode> schemas) {

        /// Names each event `"<label> <Event>"` (Go's `groupAs`).
        void groupAs(String prefix, String label, String... events) {
            for (String ev : events) {
                String code = prefix + ":" + ev;
                out.add(new Definition(code, label + " " + titleCase(ev), schemas.get(code)));
            }
        }

        /// [#groupAs] with the label derived from the aggregate segment.
        void group(String prefix, String... events) {
            groupAs(prefix, titleCase(lastSegment(prefix, ':')), events);
        }

        void push(String code, String name) {
            out.add(new Definition(code, name, schemas.get(code)));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    static String lastSegment(String s, char sep) {
        int i = s.lastIndexOf(sep);
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /// `roles-assigned` → `Roles Assigned`: split on `-`, upper-case the first
    /// byte of each non-empty part, join with a space.
    static String titleCase(String s) {
        String[] parts = s.split("-", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue;
            }
            parts[i] = p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1);
        }
        return String.join(" ", parts);
    }
}
