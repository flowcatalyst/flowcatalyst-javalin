package io.flowcatalyst.platform.mail;

import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.Test;

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
        MailService.logging().send(new Mail("to@example.com", "Hello", "x"));
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
