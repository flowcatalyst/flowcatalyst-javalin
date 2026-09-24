package io.flowcatalyst.platform.bff.api;

import io.flowcatalyst.platform.audit.AuditLogRepository;
import io.flowcatalyst.platform.audit.StoredAuditRedaction;
import io.flowcatalyst.platform.audit.operations.AuditLogsRedacted;
import io.flowcatalyst.platform.audit.operations.RedactExistingAuditLogs;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.AuditRedaction;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.AUDIT_LOG_VIEW;

/// **Temporary** (`docs/spec/audit-redaction.md` "Temporary: redact
/// existing rows from the dashboard", owner 2026-09-24; to be removed once
/// every `aud_logs` row written before the source-side redaction landed has
/// been swept): a one-shot dashboard action that walks every row and
/// applies [AuditRedaction] to `operation_json`, so rows already on disk
/// stop carrying a plaintext secret too.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | POST | `/bff/audit-logs/redact-existing` | 200 [RedactResponse]; anchor-only + [AUDIT_LOG_VIEW] |
public final class AuditLogsBff {

    private AuditLogsBff() {
    }

    public record State(AuditLogRepository auditLogs, UnitOfWork uow) {
        public State {
            Objects.requireNonNull(auditLogs, "auditLogs");
            Objects.requireNonNull(uow, "uow");
        }
    }

    public static void register(Routes routes, State s) {
        routes.post("/bff/audit-logs/redact-existing", Auth.scoped(ctx -> redactExisting(ctx, s)));
    }

    /// Same gate shape as `/bff/roles/sync-platform` (anchor-only), plus the
    /// audit-log read permission (spec: this action reads and rewrites
    /// `aud_logs`, so it needs at least the read gate on top of anchor).
    private static void redactExisting(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.requireAnchor(ac);
        Checks.require(ac, AUDIT_LOG_VIEW);

        var result = s.auditLogs().redactExisting(StoredAuditRedaction::redact);
        var event = s.uow().emitEvent(
                AuditLogsRedacted.of(Auth.executionContext(), result.scanned(), result.redacted()),
                new RedactExistingAuditLogs(result.scanned(), result.redacted()));
        ctx.json(new RedactResponse(event.scanned(), event.redacted()));
    }

    /// `{scanned, redacted}` (spec: "Answers `{scanned, redacted}`").
    public record RedactResponse(int scanned, int redacted) {
    }
}
