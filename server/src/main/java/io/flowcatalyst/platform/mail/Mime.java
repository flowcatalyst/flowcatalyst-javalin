package io.flowcatalyst.platform.mail;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/// The RFC 5322 message the transport hands to `DATA` (§9 with ruling
/// I-Q15): `From`, `To`, `Subject` (RFC 2047 encoded when it is not
/// plain ASCII), `Date`, `Message-ID`, `MIME-Version`, `Content-Type:
/// text/html; charset=UTF-8`, a blank line, the body. CRLF throughout.
public final class Mime {

    private static final DateTimeFormatter RFC_5322 = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ROOT);

    private Mime() {
    }

    public static String build(String from, Mail mail, ZonedDateTime date, String messageIdHost) {
        return "From: " + from + "\r\n"
                + "To: " + mail.to() + "\r\n"
                + "Subject: " + encodeHeader(mail.subject()) + "\r\n"
                + "Date: " + RFC_5322.format(date) + "\r\n"
                + "Message-ID: <" + UUID.randomUUID() + "@" + messageIdHost + ">\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + mail.html();
    }

    /// An `=?UTF-8?B?…?=` encoded word when the text carries anything
    /// outside printable ASCII (or a CR/LF that would fold the header);
    /// plain ASCII passes through unchanged.
    public static String encodeHeader(String text) {
        boolean plain = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                plain = false;
                break;
            }
        }
        if (plain) {
            return text;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
