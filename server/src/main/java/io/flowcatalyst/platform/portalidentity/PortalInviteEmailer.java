package io.flowcatalyst.platform.portalidentity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The mail seam for the portal admin API and the portal auth surface
/// (spec `auth-identity.md` §5.4, §5.7). Mirrors
/// [io.flowcatalyst.platform.principal.InviteEmailer]: the real
/// implementation lands with the password-reset unit; until then
/// [#logging()] answers as Go does without a mailer, logging what would
/// have been sent so a portal-admin call still succeeds.
public interface PortalInviteEmailer {

    /// A set-password invite for an identity with no password yet
    /// (§5.7, non-SSO branch).
    void sendPortalInvite(PortalIdentity identity, String target);

    /// The "open the portal" notice for an SSO-managed domain (§5.7): there
    /// is no password to set, so the mail just points at the portal.
    void sendPortalSsoInvite(String email, String target);

    /// The password-reset mail (§5.4); the caller swallows every error —
    /// `/portal/auth/password-reset` is always a silent 200.
    void sendPortalReset(String identityId, String email, String origin);

    /// The set-password invite link itself, for `returnInviteLink=true`
    /// (§5.7): a real mailer mints a single-use reset token; the logging
    /// stub returns a link whose path is at least testable.
    String inviteLink(PortalIdentity identity, String target);

    /// No mailer wired: logs every call instead of sending.
    static PortalInviteEmailer logging() {
        Logger log = LoggerFactory.getLogger(PortalInviteEmailer.class);
        return new PortalInviteEmailer() {
            @Override
            public void sendPortalInvite(PortalIdentity identity, String target) {
                log.atInfo().setMessage("portal invite emailer not configured; no invite sent to portal identity")
                        .addKeyValue("identity", identity.id())
                        .addKeyValue("target", target)
                        .log();
            }

            @Override
            public void sendPortalSsoInvite(String email, String target) {
                log.atInfo().setMessage("portal invite emailer not configured; no SSO invite sent")
                        .addKeyValue("email", email)
                        .addKeyValue("target", target)
                        .log();
            }

            @Override
            public void sendPortalReset(String identityId, String email, String origin) {
                log.atInfo().setMessage("portal invite emailer not configured; no reset email sent to portal identity")
                        .addKeyValue("identity", identityId)
                        .addKeyValue("origin", origin)
                        .log();
            }

            @Override
            public String inviteLink(PortalIdentity identity, String target) {
                return target + "?invite=" + identity.id();
            }
        };
    }
}
