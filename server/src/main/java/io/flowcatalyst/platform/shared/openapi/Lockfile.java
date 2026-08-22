package io.flowcatalyst.platform.shared.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// The canonical wire spec: `classpath:openapi/openapi.lock.json`, a
/// byte-for-byte copy of `api/openapi.lock.json` from the Go platform.
///
/// It is served as-is at `/api/openapi.json`, `/api/openapi.yaml` and
/// `/q/openapi`, and it is the arbiter for every request/response shape: the
/// Java routes are written *from* it, never generated *into* it. The route
/// coverage test compares the registered Javalin routes against
/// [#operations()].
public final class Lockfile {

    private static final String RESOURCE = "openapi/openapi.lock.json";
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /// One `method path` pair from the lockfile, e.g. `GET /api/event-types/{id}`.
    /// Path parameters keep the OpenAPI `{name}` syntax, which is also
    /// Javalin's — so the path can be registered verbatim.
    public record Operation(String method, String path, String operationId) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private final byte[] bytes;
    private final JsonNode root;

    private Lockfile(byte[] bytes, JsonNode root) {
        this.bytes = bytes;
        this.root = root;
    }

    /// Loads the embedded lockfile. Fails loudly if it is missing — the
    /// server must never start without its wire contract.
    public static Lockfile load(ObjectMapper mapper) {
        try (InputStream in = Lockfile.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE + " on the classpath");
            }
            byte[] bytes = in.readAllBytes();
            return new Lockfile(bytes, mapper.readTree(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + RESOURCE, e);
        }
    }

    /// The exact bytes to serve.
    public byte[] bytes() {
        return bytes.clone();
    }

    public JsonNode json() {
        return root;
    }

    /// Every operation in `paths`, in document order.
    public List<Operation> operations() {
        var out = new ArrayList<Operation>();
        JsonNode paths = root.path("paths");
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
                String method = op.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) continue;
                out.add(new Operation(method.toUpperCase(Locale.ROOT), path.getKey(), op.getValue().path("operationId").asText(null)));
            }
        }
        return List.copyOf(out);
    }

    /// Number of distinct paths.
    public int pathCount() {
        return root.path("paths").size();
    }
}
