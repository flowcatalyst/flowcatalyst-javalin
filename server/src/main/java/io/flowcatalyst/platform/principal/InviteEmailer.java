package io.flowcatalyst.platform.principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Sends a first-time "set your password" invite to a newly created
/// internal user that has no password yet (spec §7, §10). Best-effort: the
/// create handlers log a failure and still answer success. The
/// password-reset subsystem will provide the real implementation.
public interface InviteEmailer {

    void sendInvite(Principal principal);

    /// No mailer wired: logs the invite that would have been sent.
    static InviteEmailer logging() {
        Logger log = LoggerFactory.getLogger(InviteEmailer.class);
        return p -> log.info("invite emailer not configured; no invite sent to principal {}", p.id());
    }
}
