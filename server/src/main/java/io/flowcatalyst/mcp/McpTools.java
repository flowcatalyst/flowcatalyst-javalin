package io.flowcatalyst.mcp;

import io.flowcatalyst.platform.shared.json.Json;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/// The twelve read-only platform tools (`docs/spec/mcp.md` §3). Names,
/// descriptions and argument shapes are copied verbatim from Go
/// (`internal/mcp/tools.go`) — agents read them.
public final class McpTools {

    private McpTools() {
    }

    /// Builds the tool catalogue against `platform`.
    public static List<McpServerFeatures.SyncToolSpecification> all(PlatformClient platform) {
        return List.of(
                listEventTypes(platform),
                getEventType(platform),
                getSchema(platform),
                listSubscriptions(platform),
                getSubscription(platform),
                listApplications(platform),
                listRoles(platform),
                getRole(platform),
                getOpenApi(platform),
                whoami(platform),
                listMyApplications(platform),
                getApplicationCapabilities(platform));
    }

    // ── tools ────────────────────────────────────────────────────────────

    private static McpServerFeatures.SyncToolSpecification listEventTypes(PlatformClient platform) {
        var schema = objectSchema(Map.of(
                "status", stringProperty("filter by lifecycle status, e.g. CURRENT or FINALISING"),
                "application", stringProperty("filter by application code"),
                "subdomain", stringProperty("filter by subdomain"),
                "aggregate", stringProperty("filter by aggregate"),
                "clientId", stringProperty("filter by client id (admin-scoped)")), List.of());
        return tool("list_event_types",
                "List event types. Optionally filter by status, application, subdomain, aggregate, or clientId.",
                schema, request -> {
                    var args = request.arguments();
                    var query = new Query()
                            .param("status", string(args, "status"))
                            .param("application", string(args, "application"))
                            .param("subdomain", string(args, "subdomain"))
                            .param("aggregate", string(args, "aggregate"))
                            .param("clientId", string(args, "clientId"));
                    return platform.getPretty("/api/event-types" + query.encode());
                });
    }

    private static McpServerFeatures.SyncToolSpecification getEventType(PlatformClient platform) {
        var schema = objectSchema(Map.of("id", stringProperty("the resource id")), List.of("id"));
        return tool("get_event_type", "Get a single event type by id, including all schema spec versions.",
                schema, request -> platform.getPretty("/api/event-types/" + escape(requireString(request, "id"))));
    }

    private static McpServerFeatures.SyncToolSpecification getSchema(PlatformClient platform) {
        var schema = objectSchema(Map.of(
                "id", stringProperty("the event type id"),
                "version", stringProperty("spec version status to extract; defaults to CURRENT (falls back to FINALISING)")),
                List.of("id"));
        return tool("get_schema",
                "Extract the JSON Schema for an event type's spec version (defaults to CURRENT, falls back to FINALISING).",
                schema, request -> {
                    var id = requireString(request, "id");
                    var wanted = string(request.arguments(), "version");
                    var want = wanted == null || wanted.isBlank() ? "CURRENT" : wanted;

                    var eventType = platform.get("/api/event-types/" + escape(id));
                    var found = findSchema(eventType, want);
                    if (found == null && "CURRENT".equals(want)) {
                        // CURRENT falls back to FINALISING (TestGetSchemaFallsBackCurrentToFinalising).
                        found = findSchema(eventType, "FINALISING");
                    }
                    if (found == null) {
                        return "no " + want + " spec version";
                    }
                    return PlatformClient.pretty(found);
                });
    }

    private static McpServerFeatures.SyncToolSpecification listSubscriptions(PlatformClient platform) {
        var schema = objectSchema(Map.of(
                "clientId", stringProperty("admin-scoped: list a specific client's subscriptions")), List.of());
        return tool("list_subscriptions",
                "List webhook subscriptions, scoped to the caller unless an admin clientId is given.",
                schema, request -> {
                    var query = new Query().param("clientId", string(request.arguments(), "clientId"));
                    return platform.getPretty("/api/subscriptions" + query.encode());
                });
    }

    private static McpServerFeatures.SyncToolSpecification getSubscription(PlatformClient platform) {
        var schema = objectSchema(Map.of("id", stringProperty("the resource id")), List.of("id"));
        return tool("get_subscription", "Get a single subscription by id.", schema,
                request -> platform.getPretty("/api/subscriptions/" + escape(requireString(request, "id"))));
    }

    private static McpServerFeatures.SyncToolSpecification listApplications(PlatformClient platform) {
        var schema = objectSchema(Map.of(
                "active", booleanProperty("filter by active flag; defaults to true (active only)")), List.of());
        return tool("list_applications",
                "List registered applications. Defaults to active only; pass active=false to include inactive.",
                schema, request -> {
                    var active = bool(request.arguments(), "active", true);
                    var query = new Query().param("active", Boolean.toString(active));
                    return platform.getPretty("/api/applications" + query.encode());
                });
    }

    private static McpServerFeatures.SyncToolSpecification listRoles(PlatformClient platform) {
        var schema = objectSchema(Map.of(
                "source", stringProperty("filter by role source, e.g. CODE, BOOTSTRAP, API, UI")), List.of());
        return tool("list_roles", "List platform roles. Optionally filter by source (e.g. CODE, BOOTSTRAP, API, UI).",
                schema, request -> {
                    var query = new Query().param("source", string(request.arguments(), "source"));
                    return platform.getPretty("/api/roles" + query.encode());
                });
    }

    private static McpServerFeatures.SyncToolSpecification getRole(PlatformClient platform) {
        var schema = objectSchema(Map.of("id", stringProperty("the resource id")), List.of("id"));
        return tool("get_role", "Get a single role by id, including its assigned permissions.", schema,
                request -> platform.getPretty("/api/roles/" + escape(requireString(request, "id"))));
    }

    private static McpServerFeatures.SyncToolSpecification getOpenApi(PlatformClient platform) {
        var schema = objectSchema(Map.of(
                "applicationCode", stringProperty("the application code; defaults to 'platform'")), List.of());
        return tool("get_openapi", "Fetch the CURRENT OpenAPI spec for an application (defaults to the 'platform' application).",
                schema, request -> {
                    var code = string(request.arguments(), "applicationCode");
                    if (code == null || code.isBlank()) code = "platform";
                    return fetchOpenApiByCode(platform, code);
                });
    }

    private static McpServerFeatures.SyncToolSpecification whoami(PlatformClient platform) {
        return tool("whoami",
                "Identify the caller: id, type (USER/SERVICE), scope, roles, and accessible clients/apps. Start here.",
                objectSchema(Map.of(), List.of()), request -> platform.getPretty("/api/me"));
    }

    private static McpServerFeatures.SyncToolSpecification listMyApplications(PlatformClient platform) {
        return tool("list_my_applications", "List the applications the caller has access to.",
                objectSchema(Map.of(), List.of()), request -> platform.getPretty("/api/me/applications"));
    }

    private static McpServerFeatures.SyncToolSpecification getApplicationCapabilities(PlatformClient platform) {
        var schema = objectSchema(Map.of(
                "applicationCode", stringProperty("the application code")), List.of("applicationCode"));
        return tool("get_application_capabilities",
                "Bundle an application's metadata, CURRENT OpenAPI spec, assignable roles, and CURRENT event types.",
                schema, request -> {
                    var code = requireString(request, "applicationCode");
                    var app = platform.get("/api/applications/by-code/" + escape(code));
                    var appId = textOrNull(app, "id");

                    var bundle = Json.MAPPER.createObjectNode();
                    bundle.set("application", app);
                    bundle.set("openapi", appId == null ? null
                            : platform.getTolerating404("/bff/developer/applications/" + escape(appId) + "/openapi/current"));
                    bundle.set("assignableRoles", appId == null ? null
                            : platform.getTolerating404("/api/roles/by-application/" + escape(appId)));
                    var eventTypesQuery = new Query().param("application", code).param("status", "CURRENT");
                    bundle.set("eventTypes", platform.getTolerating404("/api/event-types" + eventTypesQuery.encode()));

                    return PlatformClient.pretty(bundle);
                });
    }

    // ── platform helpers ─────────────────────────────────────────────────

    /// Resolves an application by code, then fetches its CURRENT OpenAPI spec
    /// via the developer BFF (two-hop — shared by the `get_openapi` tool and
    /// the `flowcatalyst://openapi/platform` resource).
    static String fetchOpenApiByCode(PlatformClient platform, String code) {
        var app = platform.get("/api/applications/by-code/" + escape(code));
        var appId = textOrNull(app, "id");
        if (appId == null) {
            throw new PlatformClient.PlatformException(0, "application has no id", null);
        }
        return platform.getPretty("/bff/developer/applications/" + escape(appId) + "/openapi/current");
    }

    private static JsonNode findSchema(JsonNode eventType, String status) {
        var versions = eventType.get("specVersions");
        if (versions == null || !versions.isArray()) {
            return null;
        }
        for (var version : versions) {
            var versionStatus = version.get("status");
            if (versionStatus != null && versionStatus.isString() && status.equals(versionStatus.asString())) {
                var schema = version.get("schema");
                if (schema != null && !schema.isNull()) {
                    return schema;
                }
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        var value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    // ── argument reading ─────────────────────────────────────────────────

    private static String requireString(McpSchema.CallToolRequest request, String key) {
        var value = string(request.arguments(), key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return value;
    }

    private static String string(Map<String, Object> args, String key) {
        if (args == null) return null;
        var value = args.get(key);
        return value instanceof String s ? s : null;
    }

    private static boolean bool(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null) return defaultValue;
        var value = args.get(key);
        return value instanceof Boolean b ? b : defaultValue;
    }

    private static String escape(String segment) {
        return PlatformClient.escapePathSegment(segment);
    }

    // ── schema / tool / result plumbing ──────────────────────────────────

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> booleanProperty(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    /// A plain-`Map` JSON Schema object — [McpSchema.JsonSchema] itself is
    /// deprecated in the SDK in favour of the raw wire shape.
    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /// Wires a tool's name/description/schema together with a body that
    /// returns the success text; platform and missing-credentials failures
    /// are caught here and rendered as a tool error
    /// (`docs/spec/mcp.md` §3: "Platform errors ... become tool errors").
    private static McpServerFeatures.SyncToolSpecification tool(String name, String description,
                                                                 Map<String, Object> inputSchema,
                                                                 Function<McpSchema.CallToolRequest, String> body) {
        var tool = McpSchema.Tool.builder(name, inputSchema)
                .description(description)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        return textResult(body.apply(request));
                    } catch (McpConfig.NoCredentialsException | PlatformClient.PlatformException
                             | IllegalArgumentException e) {
                        return errorResult(e.getMessage());
                    }
                })
                .build();
    }

    private static McpSchema.CallToolResult textResult(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).build();
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    /// A minimal query-string builder (Go `client.QueryBuilder`): each
    /// non-blank `param` is appended `name=value`, URL-encoded; `encode()`
    /// renders `?a=1&b=2` or `""` if nothing was set.
    private static final class Query {
        private final StringBuilder sb = new StringBuilder();

        Query param(String name, String value) {
            if (value != null && !value.isEmpty()) {
                sb.append(sb.isEmpty() ? '?' : '&').append(name).append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
            return this;
        }

        String encode() {
            return sb.toString();
        }
    }
}
