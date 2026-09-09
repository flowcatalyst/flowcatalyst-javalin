package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Transactional notices the second-factor and password-change HTTP layer
/// sends (`docs/spec/auth-identity.md` §6.4, §6.6, §6.7, §6.8): enrolment,
/// removal, recovery-code regeneration and use, a new trusted device, and a
/// changed password. Best-effort — callers log and continue rather than
/// fail a request on a notify error. The notify subsystem will provide the
/// real implementation; this is the seam, mirroring `principal.Notifier`.
public interface TwoFactorNotifier {

    void twoFactorEnrolled(String email, MfaMethod method);

    void twoFactorMethodRemoved(String email, MfaMethod method);

    void recoveryCodesRegenerated(String email);

    void recoveryCodeUsed(String email);

    void newTrustedDevice(String email, String label);

    void passwordChanged(String email);

    /// No mail transport wired: logs what would have been sent.
    static TwoFactorNotifier logging() {
        Logger log = LoggerFactory.getLogger(TwoFactorNotifier.class);
        return new TwoFactorNotifier() {
            @Override
            public void twoFactorEnrolled(String email, MfaMethod method) {
                log.atInfo().setMessage("notifier not configured; two-factor-enrolled notice not sent")
                        .addKeyValue("method", method)
                        .log();
            }

            @Override
            public void twoFactorMethodRemoved(String email, MfaMethod method) {
                log.atInfo().setMessage("notifier not configured; two-factor-method-removed notice not sent")
                        .addKeyValue("method", method)
                        .log();
            }

            @Override
            public void recoveryCodesRegenerated(String email) {
                log.info("notifier not configured; recovery-codes-regenerated notice not sent");
            }

            @Override
            public void recoveryCodeUsed(String email) {
                log.info("notifier not configured; recovery-code-used notice not sent");
            }

            @Override
            public void newTrustedDevice(String email, String label) {
                log.atInfo().setMessage("notifier not configured; new-trusted-device notice not sent")
                        .addKeyValue("label", label)
                        .log();
            }

            @Override
            public void passwordChanged(String email) {
                log.info("notifier not configured; password-changed notice not sent");
            }
        };
    }
}
