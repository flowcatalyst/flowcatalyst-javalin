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
    /// body — the PIN included — instead of sending it. Never for
    /// production; the SMTP transport lands with the mail unit.
    static MailSender logging() {
        Logger log = LoggerFactory.getLogger(MailSender.class);
        return (to, subject, html) -> log.info("mail transport not configured; would send to={} subject={} body={}", to, subject, html);
    }
}
