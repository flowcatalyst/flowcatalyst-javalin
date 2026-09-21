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
/// (`titleCase(aggregate) + " " + titleCase(event)`) unless pushed with an
/// explicit name.
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

    private static List<Definition> build() {
        Map<String, JsonNode> schemas = PlatformEventSchemas.all();
        var out = new ArrayList<Definition>(64);
        var b = new Builder(out, schemas);

        // ── platform:iam ────────────────────────────────────────────────────
        b.group("platform:iam:user",
                "created", "updated", "activated", "deactivated", "deleted",
                "roles-assigned", "application-access-assigned",
                "client-access-granted", "client-access-revoked",
                "logged-in", "password-reset-requested", "password-reset-completed");
        b.push("platform:iam:principals:synced", "Principals Synced");

        b.group("platform:iam:serviceaccount",
                "created", "updated", "deleted", "roles-assigned",
                "token-regenerated", "secret-regenerated");

        b.group("platform:iam:client",
                "created", "updated", "activated", "suspended", "deleted", "note-added");

        b.group("platform:iam:role", "created", "updated", "deleted");
        b.push("platform:iam:roles:synced", "Roles Synced");

        b.group("platform:iam:application",
                "created", "updated", "activated", "deactivated", "deleted",
                "service-account-provisioned", "enabled-for-client", "disabled-for-client");

        b.group("platform:iam:anchor-domain", "created", "deleted");

        b.group("platform:iam:auth-config", "created", "updated", "deleted");

        // ── platform:admin ──────────────────────────────────────────────────
        b.group("platform:admin:cors", "origin-added", "origin-deleted");

        b.group("platform:admin:idp", "created", "updated", "deleted");

        b.group("platform:admin:edm", "created", "updated", "deleted");

        b.group("platform:admin:eventtype",
                "created", "updated", "archived", "deleted",
                "schema-added", "schema-finalised", "schema-deprecated");
        b.push("platform:admin:eventtypes:synced", "Event Types Synced");

        b.group("platform:admin:connection", "created", "updated", "deleted", "synced");

        b.group("platform:admin:dispatch-pool",
                "created", "updated", "archived", "deleted");
        b.push("platform:admin:dispatch-pools:synced", "Dispatch Pools Synced");

        b.group("platform:admin:subscription",
                "created", "updated", "paused", "resumed", "deleted", "synced");

        return List.copyOf(out);
    }

    /// Appends definitions for one aggregate (`group`) or a single explicitly
    /// named code (`push`), looking each code's schema up as it goes.
    private record Builder(List<Definition> out, Map<String, JsonNode> schemas) {

        void group(String prefix, String... events) {
            String aggregate = lastSegment(prefix, ':');
            for (String ev : events) {
                String code = prefix + ":" + ev;
                out.add(new Definition(code, titleCase(aggregate) + " " + titleCase(ev), schemas.get(code)));
            }
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
