package io.flowcatalyst.fnhost.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A minimal, explicit HTTP/1.1 client over a raw [Socket] — `java.net.http`
/// forbids a caller from setting the `Host` header at all
/// (`jdk.httpclient.allowRestrictedHeaders`, not settable from the test JVM
/// here without a pom edit; see the slice's own handback report), and the
/// public listener's whole contract (spec `function-public-routes.md` §3) is
/// "which function answers depends on `Host`" — so tests that need an
/// ARBITRARY `Host` value against `127.0.0.1:<port>` go through this instead.
/// Deliberately small: one request, `Connection: close`, response read to
/// EOF or `Content-Length`, no keep-alive, no chunked-response decoding
/// (nothing this module's fixtures ever send back is chunked).
final class RawHttpClient {

    private RawHttpClient() {
    }

    record RawResponse(int status, Map<String, List<String>> headers, byte[] body) {
        String bodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        List<String> header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return List.of();
        }
    }

    static RawResponse send(int port, String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        return send("127.0.0.1", port, method, path, headers, body);
    }

    static RawResponse send(String host, int port, String method, String path, Map<String, String> headers,
                             byte[] body) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout((int) Duration.ofSeconds(20).toMillis());
            OutputStream out = socket.getOutputStream();
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            boolean hasConnection = false;
            for (var e : headers.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                if (e.getKey().equalsIgnoreCase("Connection")) {
                    hasConnection = true;
                }
            }
            if (!hasConnection) {
                req.append("Connection: close\r\n");
            }
            if (body != null && body.length > 0) {
                req.append("Content-Length: ").append(body.length).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            if (body != null && body.length > 0) {
                out.write(body);
            }
            out.flush();

            InputStream in = socket.getInputStream();
            String statusLine = readLine(in);
            if (statusLine == null || statusLine.isBlank()) {
                throw new IOException("empty response from " + host + ":" + port);
            }
            String[] statusParts = statusLine.split(" ", 3);
            int status = Integer.parseInt(statusParts[1]);

            Map<String, List<String>> respHeaders = new LinkedHashMap<>();
            String line;
            long contentLength = -1;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                respHeaders.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
                if (name.equalsIgnoreCase("Content-Length")) {
                    contentLength = Long.parseLong(value);
                }
            }

            byte[] responseBody = contentLength >= 0 ? readExactly(in, contentLength) : readToEnd(in);
            return new RawResponse(status, respHeaders, responseBody);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        boolean sawAny = false;
        while ((b = in.read()) != -1) {
            sawAny = true;
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
        }
        return sawAny ? line.toString(StandardCharsets.ISO_8859_1) : null;
    }

    private static byte[] readExactly(InputStream in, long length) throws IOException {
        byte[] out = new byte[Math.toIntExact(length)];
        int read = 0;
        while (read < out.length) {
            int n = in.read(out, read, out.length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read == out.length ? out : java.util.Arrays.copyOf(out, read);
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
