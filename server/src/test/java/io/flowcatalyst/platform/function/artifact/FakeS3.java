package io.flowcatalyst.platform.function.artifact;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// A minimal in-process fake of the S3 REST API (U11, spec
/// `function-artifact-upload.md` §2/§7) — just enough of PutObject,
/// HeadObject, GetObject, DeleteObject and ListObjectsV2 for
/// `S3ArtifactBlobStore` to drive through the real AWS SDK client, path-style,
/// against `http://127.0.0.1:<port>`. Not a general S3 emulator: object keys
/// are always `/{bucket}/{key...}` (at least one key segment), so a
/// bucket-only path is unambiguously a `ListObjectsV2` call.
final class FakeS3 implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    private FakeS3(HttpServer server) {
        this.server = server;
    }

    static FakeS3 start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var fake = new FakeS3(server);
        server.createContext("/", fake::handle);
        server.setExecutor(null);
        server.start();
        return fake;
    }

    int port() {
        return server.getAddress().getPort();
    }

    URI endpoint() {
        return URI.create("http://127.0.0.1:" + port());
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            String method = ex.getRequestMethod();
            switch (method) {
                case "PUT" -> handlePut(ex, path);
                case "HEAD" -> handleHead(ex, path);
                case "GET" -> {
                    if (isBucketOnly(path)) handleList(ex, path, query);
                    else handleGet(ex, path);
                }
                case "DELETE" -> handleDelete(ex, path);
                default -> ex.sendResponseHeaders(405, -1);
            }
        } finally {
            ex.close();
        }
    }

    private static boolean isBucketOnly(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        return !trimmed.contains("/");
    }

    private void handlePut(HttpExchange ex, String path) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        objects.put(path, body);
        ex.getResponseHeaders().add("ETag", "\"" + Integer.toHexString(java.util.Arrays.hashCode(body)) + "\"");
        ex.sendResponseHeaders(200, -1);
    }

    private void handleHead(HttpExchange ex, String path) throws IOException {
        byte[] body = objects.get(path);
        if (body == null) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        ex.getResponseHeaders().add("Content-Length", Integer.toString(body.length));
        ex.sendResponseHeaders(200, -1);
    }

    private void handleGet(HttpExchange ex, String path) throws IOException {
        byte[] body = objects.get(path);
        if (body == null) {
            sendXml(ex, 404, errorXml("NoSuchKey", path));
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void handleDelete(HttpExchange ex, String path) throws IOException {
        objects.remove(path);
        ex.sendResponseHeaders(204, -1);
    }

    private void handleList(HttpExchange ex, String bucketPath, String query) throws IOException {
        String prefix = queryParam(query, "prefix").orElse("");
        String bucketPrefix = bucketPath.endsWith("/") ? bucketPath : bucketPath + "/";
        String fullPrefix = bucketPrefix + prefix;
        List<String> keys = objects.keySet().stream()
                .filter(k -> k.startsWith(fullPrefix))
                .sorted()
                .toList();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        xml.append("<Name>").append(escape(bucketPath.replaceFirst("^/", ""))).append("</Name>");
        xml.append("<Prefix>").append(escape(prefix)).append("</Prefix>");
        xml.append("<KeyCount>").append(keys.size()).append("</KeyCount>");
        xml.append("<MaxKeys>1000</MaxKeys>");
        xml.append("<IsTruncated>false</IsTruncated>");
        for (String key : keys) {
            String objectKey = key.substring(bucketPrefix.length());
            xml.append("<Contents>");
            xml.append("<Key>").append(escape(objectKey)).append("</Key>");
            xml.append("<LastModified>2026-01-01T00:00:00.000Z</LastModified>");
            xml.append("<ETag>&quot;fake&quot;</ETag>");
            xml.append("<Size>").append(objects.get(key).length).append("</Size>");
            xml.append("<StorageClass>STANDARD</StorageClass>");
            xml.append("</Contents>");
        }
        xml.append("</ListBucketResult>");
        sendXml(ex, 200, xml.toString());
    }

    private static String errorXml(String code, String key) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Error><Code>" + code + "</Code><Message>The specified key does not exist.</Message>"
                + "<Key>" + escape(key) + "</Key><RequestId>fake-request-id</RequestId></Error>";
    }

    private static void sendXml(HttpExchange ex, int status, String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/xml");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static java.util.Optional<String> queryParam(String query, String name) {
        if (query == null) return java.util.Optional.empty();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                return java.util.Optional.of(java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
            }
        }
        return java.util.Optional.empty();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
