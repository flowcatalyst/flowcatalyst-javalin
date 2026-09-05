package io.flowcatalyst.platform.mail;

import io.flowcatalyst.server.EnvReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// The outbound-mail seam (`docs/spec/auth-identity.md` §9). Without an
/// SMTP host the development transport logs the whole message — links and
/// PINs included — at WARN, which is what Go's `LogService` does.
public interface MailService {

    void send(Mail mail);

    static MailService logging() {
        Logger log = LoggerFactory.getLogger(MailService.class);
        return mail -> log.warn("[email] SMTP not configured — logging instead of sending to={} subject={} body={}",
                mail.to(), mail.subject(), mail.html());
    }

    /// `FC_SMTP_HOST` (else `SMTP_HOST`) selects SMTP; unset ⇒ [#logging()].
    static MailService fromEnv(EnvReader env) {
        return SmtpMailService.Config.fromEnv(env).<MailService>map(SmtpMailService::new).orElseGet(() -> {
            LoggerFactory.getLogger(MailService.class)
                    .warn("SMTP not configured (no SMTP_HOST); password-reset and other emails will be logged only");
            return logging();
        });
    }
}
