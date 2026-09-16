package io.flowcatalyst.platform.principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Sends a first-time "set your password" invite to a newly created
/// internal user that has no password yet (spec §7, §10; app-managed
/// invitations §1, §1a). Best-effort: the create handlers log a failure and
/// still answer success. `ResetLinks` (password-reset subsystem) is the real
/// implementation. Both methods take the already-validated redirect URI to
/// carry on the minted INVITE token (`null` = none — app-managed-invitations
/// §1a); the caller has already run `resolveInviteRedirect`.
public interface InviteEmailer {

    void sendInvite(Principal principal, String redirectUri);

    /// Mints the INVITE token (deleting the subject's outstanding tokens, as
    /// every mint does) and returns the set-password link without mailing —
    /// the embedded-link pattern (app-managed-invitations §0, §1).
    String inviteLink(Principal principal, String redirectUri);

    /// No mailer wired: logs the invite that would have been sent;
    /// `inviteLink` cannot mint anything with no token store wired, so it
    /// throws — the caller (`PrincipalApi#notifyNewUser` step 3) turns that
    /// into a logged best-effort `null`.
    static InviteEmailer logging() {
        Logger log = LoggerFactory.getLogger(InviteEmailer.class);
        return new InviteEmailer() {
            @Override
            public void sendInvite(Principal p, String redirectUri) {
                log.atInfo().setMessage("invite emailer not configured; no invite sent to principal")
                        .addKeyValue("principal", p.id())
                        .log();
            }

            @Override
            public String inviteLink(Principal p, String redirectUri) {
                throw new IllegalStateException("invite emailer not configured");
            }
        };
    }
}
