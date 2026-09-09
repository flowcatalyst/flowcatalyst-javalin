package io.flowcatalyst.platform.mail;

import io.flowcatalyst.server.EnvReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The outbound-mail seam (`docs/spec/auth-identity.md` §9). Without an
/// SMTP host the message is logged instead of sent, which is what Go's
/// `LogService` does — but the **body is only logged in dev**.
public interface MailService {

    void send(Mail mail);

    /// The development transport: no SMTP host, so the message is logged
    /// rather than sent.
    ///
    /// `includeBody` logs the rendered HTML, which carries the one-time PIN
    /// and the password-reset link — that is how a developer completes a
    /// login with no mail server. It is true only under
    /// `FLOWCATALYST_DEV_MODE`. Outside dev the body is withheld: a
    /// deployment that merely forgot its SMTP settings must not write live
    /// PINs and reset links into whatever aggregates the logs. Recipient and
    /// subject are kept either way, so "was the mail attempted?" is still
    /// answerable.
    static MailService logging(boolean includeBody) {
        Logger log = LoggerFactory.getLogger(MailService.class);
        return mail -> {
            var event = log.atWarn().setMessage("SMTP not configured; mail logged instead of sent")
                    .addKeyValue("to", mail.to())
                    .addKeyValue("subject", mail.subject());
            if (includeBody) {
                event = event.addKeyValue("body", mail.html());
            }
            event.log();
        };
    }

    /// `FC_SMTP_HOST` (else `SMTP_HOST`) selects SMTP; unset ⇒ [#logging(boolean)],
    /// carrying the body only when `FLOWCATALYST_DEV_MODE` is set.
    static MailService fromEnv(EnvReader env) {
        return SmtpMailService.Config.fromEnv(env).<MailService>map(SmtpMailService::new).orElseGet(() -> {
            boolean dev = env.bool("FLOWCATALYST_DEV_MODE", false);
            LoggerFactory.getLogger(MailService.class).atWarn()
                    .setMessage("SMTP not configured; password-reset and other emails will be logged only")
                    .addKeyValue("body_logged", dev)
                    .log();
            return logging(dev);
        });
    }
}
