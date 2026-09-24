package io.flowcatalyst.platform.mail;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/// The SMTP dialogue Go's `net/smtp` runs, and nothing more: EHLO, an
/// optional STARTTLS (when the server advertises it and the connection is
/// not already TLS), `AUTH PLAIN` when a username is configured, `MAIL
/// FROM`, `RCPT TO`, `DATA` with dot-stuffing, `QUIT`. One message per
/// connection. Implicit TLS (`secure`) negotiates TLS from the first byte
/// with SNI = the host.
final class SmtpClient implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String host;
    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;
    private final List<String> extensions = new ArrayList<>();

    SmtpClient(String host) {
        this.host = host;
    }

    /// Opens the connection and reads the greeting.
    static SmtpClient connect(String host, int port, boolean implicitTls) throws IOException {
        var c = new SmtpClient(host);
        Socket s;
        if (implicitTls) {
            var ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket();
            ssl.connect(new InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
            var params = ssl.getSSLParameters();
            params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
        }
        s.setSoTimeout((int) TIMEOUT.toMillis());
        c.attach(s);
        c.expect(220, "greeting");
        return c;
    }

    private void attach(Socket s) throws IOException {
        this.socket = s;
        this.in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
        this.out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.ISO_8859_1));
    }

    void ehlo(String localName) throws IOException {
        send("EHLO " + localName);
        List<String> lines = expect(250, "EHLO");
        extensions.clear();
        for (int i = 1; i < lines.size(); i++) {
            extensions.add(lines.get(i).substring(4).trim().toUpperCase(Locale.ROOT)); // never the Turkish I
        }
    }

    boolean supports(String extension) {
        return extensions.stream().anyMatch(e -> e.equals(extension) || e.startsWith(extension + " "));
    }

    /// Upgrades a plain connection when the server offers it; re-EHLOs after.
    void startTls(String localName) throws IOException {
        send("STARTTLS");
        expect(220, "STARTTLS");
        var ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, socket.getPort(), true);
        var params = ssl.getSSLParameters();
        params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.setUseClientMode(true);
        ssl.startHandshake();
        attach(ssl);
        ehlo(localName);
    }

    void authPlain(String username, String password) throws IOException {
        String token = Base64.getEncoder().encodeToString(("\0" + username + "\0" + password).getBytes(StandardCharsets.UTF_8));
        send("AUTH PLAIN " + token);
        expect(235, "AUTH");
    }

    void mail(String from, String to, String data) throws IOException {
        send("MAIL FROM:<" + from + ">");
        expect(250, "MAIL FROM");
        send("RCPT TO:<" + to + ">");
        expect(250, "RCPT TO");
        send("DATA");
        expect(354, "DATA");
        for (String line : data.split("\r\n", -1)) {
            out.write(line.startsWith(".") ? "." + line : line);
            out.write("\r\n");
        }
        out.write(".\r\n");
        out.flush();
        expect(250, "message body");
    }

    void quit() throws IOException {
        send("QUIT");
        try {
            expect(221, "QUIT");
        } catch (IOException _) {
            // A server that closes first is fine.
        }
    }

    private void send(String line) throws IOException {
        out.write(line);
        out.write("\r\n");
        out.flush();
    }

    /// Reads one (possibly multi-line) reply; throws unless its code matches.
    private List<String> expect(int code, String step) throws IOException {
        var lines = new ArrayList<String>();
        while (true) {
            String line = in.readLine();
            if (line == null) {
                throw new IOException("smtp " + step + ": connection closed");
            }
            lines.add(line);
            if (line.length() < 4 || line.charAt(3) != '-') {
                break;
            }
        }
        String last = lines.getLast();
        int got;
        try {
            got = Integer.parseInt(last.substring(0, 3));
        } catch (RuntimeException e) {
            throw new IOException("smtp " + step + ": malformed reply '" + last + "'");
        }
        if (got != code) {
            throw new IOException("smtp " + step + ": " + last);
        }
        return lines;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException _) {
            // nothing left to do with it
        }
    }
}
