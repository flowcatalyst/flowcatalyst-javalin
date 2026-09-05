package io.flowcatalyst.platform.resetapproval;

import io.flowcatalyst.platform.notify.Notifications;
import io.flowcatalyst.platform.passwordreset.ApprovalQueue;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.resetapproval.operations.QueueCommand;
import io.flowcatalyst.platform.resetapproval.operations.QueueResetApproval;
import io.flowcatalyst.platform.resetapproval.operations.ResetApprovalEvents.ResetApprovalQueued;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/// The real [ApprovalQueue] (spec §8.6): a principal without a `clientId`
/// (anchor users) or with an unexpired `PENDING` request already on file is
/// silently skipped — nothing to insert, nothing to notify; otherwise
/// [QueueResetApproval] inserts the row and every client-admin e-mail for
/// the client is notified with the review link. Best-effort throughout —
/// [io.flowcatalyst.platform.passwordreset.PasswordResetApi] answers its
/// silent 200 regardless of what happens here, so any failure is logged,
/// never propagated.
public final class ResetApprovalQueue implements ApprovalQueue {

    private static final Logger LOG = LoggerFactory.getLogger(ResetApprovalQueue.class);

    /// Q22: the system actor is spelled `"system"`.
    static final String SYSTEM_ACTOR = "system";

    private final ResetApprovalRepository repo;
    private final PrincipalRepository principals;
    private final Notifications notices;
    private final UnitOfWork uow;
    private final String baseUrl;

    /// @param baseUrl `FC_JWT_ISSUER` — the origin the review link lives under
    public ResetApprovalQueue(ResetApprovalRepository repo, PrincipalRepository principals, Notifications notices,
                              UnitOfWork uow, String baseUrl) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.uow = Objects.requireNonNull(uow, "uow");
        this.baseUrl = trim(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    @Override
    public void queue(Principal principal) {
        try {
            if (principal.clientId() == null) {
                return; // anchor users get no approval path (spec §8.6)
            }
            if (repo.hasPendingFor(principal.id())) {
                return; // an unexpired PENDING request already exists
            }
            ResetApprovalQueued event = QueueResetApproval.of(repo)
                    .run(uow, new QueueCommand(principal.id(), principal.clientId()), ExecutionContext.of(SYSTEM_ACTOR));
            notifyClientAdmins(principal.clientId(), event.requestId());
        } catch (RuntimeException e) {
            LOG.warn("reset approval queueing failed principal={}", principal.id(), e);
        }
    }

    private void notifyClientAdmins(String clientId, String requestId) {
        String link = baseUrl + "/authentication/reset-approvals/" + requestId;
        for (String email : principals.findClientAdminEmails(clientId)) {
            notices.resetApprovalNeeded(email, link);
        }
    }

    private static String trim(String base) {
        String b = base;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }
}
