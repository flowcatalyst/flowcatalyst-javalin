package io.flowcatalyst.platform.shared.openapi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
/// `asText()`/`isTextual()` are deprecated in Jackson 3 for `stringValue()`/
/// `isString()`, which are NOT equivalent (throws on non-string, `null` not
/// `""` for JSON `null`) — kept deliberately, suppressed rather than migrated.
@SuppressWarnings("deprecation")
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
        return load(mapper, RESOURCE);
    }

    /// Loads any OpenAPI-shaped document off the classpath as a
    /// `Lockfile`-like view (spec `function-openapi.md` §3: "a small
    /// generalisation — a Lockfile-like view over any document"). Used for
    /// `openapi/functions.openapi.json`, which is hand-authored rather than
    /// vendored from Go but shares every structural convention this class
    /// already reads (`paths`, `operationId`, `components/schemas`, `$ref`).
    public static Lockfile load(ObjectMapper mapper, String resourcePath) {
        try (InputStream in = Lockfile.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resourcePath + " on the classpath");
            }
            byte[] bytes = in.readAllBytes();
            return new Lockfile(bytes, mapper.readTree(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resourcePath, e);
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

    /// The raw operation object for `method path` (`paths.<path>.<method>`),
    /// or a missing node if the lockfile has no such operation. Used by
    /// [SchemaValidation] to reach `requestBody` / `parameters` without a
    /// second document walk.
    public JsonNode operationNode(String method, String path) {
        return root.path("paths").path(path).path(method.toLowerCase(Locale.ROOT));
    }

    /// Follows a `$ref` chain (`#/components/schemas/Name`) to the schema it
    /// names, looping in case a component itself is a bare `$ref` (none are,
    /// today, but a lockfile bump should not have to touch this). A node with
    /// no `$ref` is returned unchanged, so callers can pass any schema node
    /// through unconditionally.
    public JsonNode resolveRef(JsonNode schema) {
        var node = schema;
        while (node.has("$ref")) {
            var ref = node.path("$ref").stringValue();
            if (!ref.startsWith("#/")) {
                throw new IllegalStateException("unsupported $ref (not a local component pointer): " + ref);
            }
            node = root.at(ref.substring(1));
            if (node.isMissingNode()) {
                throw new IllegalStateException("$ref target not found: " + ref);
            }
        }
        return node;
    }
}
