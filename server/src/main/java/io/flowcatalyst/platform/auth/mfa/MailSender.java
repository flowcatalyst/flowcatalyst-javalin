package io.flowcatalyst.platform.auth.mfa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Outbound mail as the second-factor flows need it (`docs/spec/auth-identity.md`
/// §9 is the transport; this is only its seam). A send failure throws — the
/// caller decides whether the user can proceed without the message.
public interface MailSender {

    void send(String to, String subject, String html);

    /// The real transport behind the seam.
    static MailSender of(io.flowcatalyst.platform.mail.MailService mail) {
        return (to, subject, html) -> mail.send(new io.flowcatalyst.platform.mail.Mail(to, subject, html));
    }

    /// Go's `LogService`: the development transport that logs the message
    /// instead of sending it. `includeBody` logs the rendered HTML — the PIN
    /// included — and is only ever true in dev (`FLOWCATALYST_DEV_MODE`);
    /// see [io.flowcatalyst.platform.mail.MailService#logging(boolean)],
    /// which is what production actually resolves.
    static MailSender logging(boolean includeBody) {
        Logger log = LoggerFactory.getLogger(MailSender.class);
        return (to, subject, html) -> {
            var event = log.atInfo().setMessage("mail transport not configured; message logged instead of sent")
                    .addKeyValue("to", to)
                    .addKeyValue("subject", subject);
            if (includeBody) {
                event = event.addKeyValue("body", html);
            }
            event.log();
        };
    }
}
