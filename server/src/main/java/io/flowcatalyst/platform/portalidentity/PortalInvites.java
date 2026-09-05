package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.platform.passwordreset.ResetLinks;

import java.util.Objects;

/// The real [PortalInviteEmailer]: every portal mail and invite link is a
/// single-use reset token minted by [ResetLinks] for the `ptu_` subject
/// (auth-identity §8, the portal rows of the token table), so
/// `returnInviteLink=true` hands the portal a link its set-password page can
/// complete. The parity harness (S1-B) found the logging stub still wired in
/// production, answering `null?invite=<id>`.
public final class PortalInvites implements PortalInviteEmailer {

    private final ResetLinks links;

    public PortalInvites(ResetLinks links) {
        this.links = Objects.requireNonNull(links, "links");
    }

    @Override
    public void sendPortalInvite(PortalIdentity identity, String target) {
        links.sendPortalInvite(identity.id(), identity.email(), target);
    }

    @Override
    public void sendPortalSsoInvite(String email, String target) {
        links.sendPortalSsoInvite(email, target);
    }

    @Override
    public void sendPortalReset(String identityId, String email, String origin) {
        links.sendPortalReset(identityId, email, origin);
    }

    @Override
    public String inviteLink(PortalIdentity identity, String target) {
        return links.portalInviteLink(identity.id(), target);
    }
}
