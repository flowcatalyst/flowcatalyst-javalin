package io.flowcatalyst.platform.mail;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The SMTP dialogue against an in-process server that records it: the
/// exact command sequence, `AUTH PLAIN` framing, dot-stuffing, and that a
/// refused recipient surfaces as a [MailException] the caller can act on.
class SmtpMailServiceTest {

    /// A one-connection SMTP server scripted by reply code per command.
    static final class FakeSmtp implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final List<String> received = new ArrayList<>();
        final StringBuilder data = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        volatile String rejectRcptWith;

        FakeSmtp() throws IOException {
            Thread.ofVirtual().start(this::serve);
        }

        void serve() {
            try (Socket s = server.accept();
                 var in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                 var out = new PrintWriter(s.getOutputStream(), true)) {
                out.print("220 fake ESMTP\r\n");
                out.flush();
                String line;
                boolean inData = false;
                while ((line = in.readLine()) != null) {
                    if (inData) {
                        if (line.equals(".")) {
                            inData = false;
                            reply(out, "250 queued");
                        } else {
                            data.append(line).append("\r\n");
                        }
                        continue;
                    }
                    received.add(line);
                    String upper = line.toUpperCase();
                    if (upper.startsWith("EHLO")) {
                        reply(out, "250-fake\r\n250-SIZE 10000000\r\n250 AUTH PLAIN LOGIN");
                    } else if (upper.startsWith("AUTH PLAIN")) {
                        reply(out, "235 ok");
                    } else if (upper.startsWith("MAIL FROM")) {
                        reply(out, "250 ok");
                    } else if (upper.startsWith("RCPT TO")) {
                        reply(out, rejectRcptWith != null ? rejectRcptWith : "250 ok");
                    } else if (upper.startsWith("DATA")) {
                        inData = true;
                        reply(out, "354 go");
                    } else if (upper.startsWith("QUIT")) {
                        reply(out, "221 bye");
                        break;
                    } else {
                        reply(out, "500 what");
                    }
                }
            } catch (IOException e) {
                // the client hung up; the recorded dialogue is what matters
            } finally {
                done.countDown();
            }
        }

        private static void reply(PrintWriter out, String text) {
            out.print(text + "\r\n");
            out.flush();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static SmtpMailService service(int port, String username) {
        return new SmtpMailService(new SmtpMailService.Config("localhost", port, username, "s3cret", "noreply@flowcatalyst.local", false),
                Clock.fixed(Instant.parse("2026-09-05T14:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void sendsTheGoDialogueWithPlainAuthAndADotStuffedBody() throws Exception {
        try (var smtp = new FakeSmtp()) {
            service(smtp.port(), "user").send(new Mail("to@example.com", "Hello", "<p>line</p>\r\n.starts with a dot\r\n..two"));
            assertThat(smtp.done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(smtp.received).containsExactly(
                    "EHLO localhost",
                    "AUTH PLAIN " + Base64.getEncoder().encodeToString("\0user\0s3cret".getBytes(StandardCharsets.UTF_8)),
                    "MAIL FROM:<noreply@flowcatalyst.local>",
                    "RCPT TO:<to@example.com>",
                    "DATA",
                    "QUIT");
            String body = smtp.data.toString();
            assertThat(body).startsWith("From: noreply@flowcatalyst.local\r\nTo: to@example.com\r\nSubject: Hello\r\nDate: Sat, 5 Sep 2026 14:30:00 +0000\r\nMessage-ID: <");
            assertThat(body).contains("\r\n\r\n<p>line</p>\r\n..starts with a dot\r\n...two\r\n").as("dot-stuffed on the wire");
        }
    }

    @Test
    void withoutAUsernameThereIsNoAuthCommand() throws Exception {
        try (var smtp = new FakeSmtp()) {
            service(smtp.port(), "").send(new Mail("to@example.com", "Hello", "x"));
            assertThat(smtp.done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(smtp.received).doesNotContain("AUTH PLAIN").contains("MAIL FROM:<noreply@flowcatalyst.local>");
        }
    }

    @Test
    void aRefusedRecipientIsAMailExceptionNamingTheReply() throws Exception {
        try (var smtp = new FakeSmtp()) {
            smtp.rejectRcptWith = "550 no such user";
            assertThatThrownBy(() -> service(smtp.port(), "").send(new Mail("nobody@example.com", "Hello", "x")))
                    .isInstanceOf(MailException.class).hasMessageContaining("550 no such user");
        }
    }

    @Test
    void anUnreachableServerIsAMailExceptionTooAndTheLoggingTransportNeverThrows() throws Exception {
        int free;
        try (var s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        assertThatThrownBy(() -> service(free, "").send(new Mail("to@example.com", "Hello", "x"))).isInstanceOf(MailException.class);
        MailService.logging(false).send(new Mail("to@example.com", "Hello", "x"));
    }

    /// The dev transport logs the message body, which carries the one-time
    /// PIN and the reset link. That is deliberate with no mail server — and
    /// must not happen anywhere else: a deployment that merely forgot its
    /// SMTP settings would otherwise write live PINs into the log pipeline
    /// (docs/backlog.md, owner ruling 2026-09-08).
    @Test
    void theBodyIsLoggedOnlyInDevModeAndTheRecipientAlways() {
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        var log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MailService.class);
        log.addAppender(captured);
        try {
            var secret = "your code is 123456";

            MailService.logging(false).send(new Mail("to@example.com", "Your code", secret));
            MailService.logging(true).send(new Mail("to@example.com", "Your code", secret));

            var withheld = captured.list.get(0);
            var included = captured.list.get(1);

            // Neither may ever put the body in the message text itself.
            assertThat(withheld.getFormattedMessage()).doesNotContain(secret);
            assertThat(included.getFormattedMessage()).doesNotContain(secret);

            assertThat(keys(withheld)).containsExactlyInAnyOrder("to", "subject");
            assertThat(values(withheld)).doesNotContain(secret);
            assertThat(keys(included)).containsExactlyInAnyOrder("to", "subject", "body");
            assertThat(values(included)).contains(secret);
        } finally {
            log.detachAppender(captured);
        }
    }

    /// `fromEnv` is what production actually calls, so the gate has to be
    /// wired there, not merely available on the factory.
    @Test
    void fromEnvWithholdsTheBodyUnlessDevModeIsSet() {
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        var log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MailService.class);
        log.addAppender(captured);
        try {
            var secret = "your code is 654321";
            MailService.fromEnv(new EnvReader(Map.of())).send(new Mail("a@b.c", "s", secret));
            MailService.fromEnv(new EnvReader(Map.of("FLOWCATALYST_DEV_MODE", "true"))).send(new Mail("a@b.c", "s", secret));

            var sent = captured.list.stream().filter(e -> keys(e).contains("to")).toList();
            assertThat(values(sent.get(0))).as("default deployment").doesNotContain(secret);
            assertThat(values(sent.get(1))).as("dev mode").contains(secret);
        } finally {
            log.detachAppender(captured);
        }
    }

    private static List<String> keys(ILoggingEvent e) {
        return e.getKeyValuePairs() == null ? List.of() : e.getKeyValuePairs().stream().map(kv -> kv.key).toList();
    }

    private static List<String> values(ILoggingEvent e) {
        return e.getKeyValuePairs() == null ? List.of()
                : e.getKeyValuePairs().stream().map(kv -> String.valueOf(kv.value)).toList();
    }

    @Test
    void configurationReadsTheFcPrefixedNamesFirstWithGoDefaults() {
        assertThat(SmtpMailService.Config.fromEnv(new EnvReader(Map.of()))).isEmpty();
        var c = SmtpMailService.Config.fromEnv(new EnvReader(Map.of("SMTP_HOST", "bare.example", "FC_SMTP_HOST", "fc.example",
                "SMTP_SECURE", "YES"))).orElseThrow();
        assertThat(c.host()).as("FC_ wins").isEqualTo("fc.example");
        assertThat(c.port()).isEqualTo(587);
        assertThat(c.from()).isEqualTo("noreply@flowcatalyst.local");
        assertThat(c.secure()).as("case-insensitive yes").isTrue();
        assertThat(SmtpMailService.Config.fromEnv(new EnvReader(Map.of("SMTP_HOST", "h", "SMTP_SECURE", "no"))).orElseThrow().secure()).isFalse();
        assertThat(MailService.fromEnv(new EnvReader(Map.of("SMTP_HOST", "h", "SMTP_PORT", "2525")))).isInstanceOf(SmtpMailService.class);
    }
}
