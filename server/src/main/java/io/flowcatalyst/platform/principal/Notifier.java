package io.flowcatalyst.platform.principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Transactional notices this package sends (spec §7, §10): the "your
/// account was created" welcome for a user created with a password, and the
/// "your two-factor was reset" notice. Best-effort, never fails a request.
/// The notify subsystem will provide the real implementation.
public interface Notifier {

    void accountCreated(String email);

    void twoFactorReset(String email);

    /// No mail transport wired: logs what would have been sent.
    static Notifier logging() {
        Logger log = LoggerFactory.getLogger(Notifier.class);
        return new Notifier() {
            @Override
            public void accountCreated(String email) {
                log.info("notifier not configured; account-created notice not sent");
            }

            @Override
            public void twoFactorReset(String email) {
                log.info("notifier not configured; two-factor-reset notice not sent");
            }
        };
    }
}
