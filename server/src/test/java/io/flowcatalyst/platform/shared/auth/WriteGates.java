package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins "a write checks the permission for its operation, never any of
/// create/update/delete" (`docs/go-mirror/2026-10-10-platform-bug-fixes.md`,
/// the permission-gates group). Each write is sent by an anchor principal
/// holding exactly one permission; the route must refuse it with 403
/// `PERMISSION_REQUIRED` unless that permission is the operation's. A call the
/// gate admits may still fail further on (404 for an invented id, 400 for an
/// empty body) — only the permission refusal is asserted.
public final class WriteGates {

    private WriteGates() {
    }

    /// One write route and the permission it needs.
    public record Write(String name, String method, String path, String body, String need) {
        public static Write of(String name, String method, String path, String body, Permission need) {
            return new Write(name, method, path, body, need.code());
        }
    }

    /// Test headers for an anchor principal holding only `permission`.
    public static String[] holding(String permission) {
        return new String[] {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, permission};
    }

    /// Whether the response is the coarse permission refusal.
    public static boolean refusedForPermission(HttpResponse<String> r) {
        if (r.statusCode() != 403) return false;
        try {
            return "PERMISSION_REQUIRED".equals(Json.MAPPER.readTree(r.body()).path("error").asString());
        } catch (Exception e) {
            return false;
        }
    }

    /// Sends every write once per held permission and asserts it is refused
    /// exactly when the held permission is not the operation's.
    public static void assertEachWriteNeedsItsPermission(TestHttp http, List<Write> writes, Permission... held) {
        var wrong = new ArrayList<String>();
        for (Write w : writes) {
            for (Permission p : held) {
                var r = http.send(w.method(), w.path(), w.body(), holding(p.code()));
                boolean refused = refusedForPermission(r);
                boolean want = !p.code().equals(w.need());
                if (refused != want) {
                    wrong.add(w.name() + " holding only " + p.code() + ": refused=" + refused + ", want " + want
                            + " (" + r.statusCode() + " " + r.body() + ")");
                }
            }
        }
        assertThat(wrong).as("writes gated by the wrong permission").isEmpty();
    }
}
