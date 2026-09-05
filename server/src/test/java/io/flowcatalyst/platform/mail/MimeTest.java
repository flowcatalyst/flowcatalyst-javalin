package io.flowcatalyst.platform.mail;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/auth-identity.md` §9 with ruling I-Q15: the headers Go
/// omitted (`Date`, `Message-ID`, an RFC 2047 subject) are present, and
/// the message stays a CRLF RFC 5322 text/html body.
class MimeTest {

    private static final ZonedDateTime AT = ZonedDateTime.of(2026, 9, 5, 14, 30, 0, 0, ZoneOffset.UTC);

    @Test
    void theMessageCarriesEveryHeaderAndABlankLineBeforeTheBody() {
        String m = Mime.build("noreply@flowcatalyst.local", new Mail("to@example.com", "Reset your password", "<p>hi</p>"), AT, "flowcatalyst.local");
        String[] parts = m.split("\r\n\r\n", 2);
        assertThat(parts).hasSize(2);
        assertThat(parts[1]).isEqualTo("<p>hi</p>");
        String headers = parts[0];
        assertThat(headers.split("\r\n")).containsExactly(
                "From: noreply@flowcatalyst.local",
                "To: to@example.com",
                "Subject: Reset your password",
                "Date: Sat, 5 Sep 2026 14:30:00 +0000",
                headers.split("\r\n")[4],
                "MIME-Version: 1.0",
                "Content-Type: text/html; charset=UTF-8");
        assertThat(headers.split("\r\n")[4]).matches("Message-ID: <[0-9a-f-]{36}@flowcatalyst\\.local>");
        assertThat(m).doesNotContain("\n\n").as("CRLF only");
        assertThat(m.chars().filter(c -> c == '\n').count()).isEqualTo(m.split("\r\n", -1).length - 1L);
    }

    @Test
    void aNonAsciiSubjectIsAnRfc2047EncodedWordAndAsciiPassesThrough() {
        assertThat(Mime.encodeHeader("Reset your password")).isEqualTo("Reset your password");
        String encoded = Mime.encodeHeader("Réinitialisez — 🔐");
        assertThat(encoded).startsWith("=?UTF-8?B?").endsWith("?=");
        String b64 = encoded.substring("=?UTF-8?B?".length(), encoded.length() - 2);
        assertThat(new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("Réinitialisez — 🔐");
        assertThat(Mime.encodeHeader("line\r\nbreak")).as("a header injection is encoded away").startsWith("=?UTF-8?B?");
    }

    @Test
    void eachMessageGetsItsOwnMessageId() {
        var mail = new Mail("to@example.com", "s", "b");
        String a = Mime.build("f@x", mail, AT, "x");
        String b = Mime.build("f@x", mail, AT, "x");
        assertThat(a).isNotEqualTo(b);
    }
}
