package io.flowcatalyst.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.function.Function;

/// The nine read-only platform resources (`docs/spec/mcp.md` §4): five fixed
/// collection resources plus four single-entity templates. Names and
/// descriptions are copied verbatim from Go (`internal/mcp/resources.go`).
/// Go registers a fifth template (`flowcatalyst://openapi/{applicationCode}`)
/// that the spec's §4 does not name (it lists exactly four) — deliberately
/// not ported here; `McpServerTest` pins the count at nine.
public final class McpResources {

    private static final String MIME_JSON = "application/json";

    private McpResources() {
    }

    /// The five fixed collection resources.
    public static List<McpServerFeatures.SyncResourceSpecification> staticResources(PlatformClient platform) {
        return List.of(
                resource("flowcatalyst://openapi/platform", "Platform OpenAPI",
                        "CURRENT OpenAPI spec for the platform application.",
                        uri -> McpTools.fetchOpenApiByCode(platform, "platform")),
                resource("flowcatalyst://applications", "Applications",
                        "Registered applications (active only).",
                        uri -> platform.getPretty("/api/applications?active=true")),
                resource("flowcatalyst://roles", "Roles", "Platform roles.",
                        uri -> platform.getPretty("/api/roles")),
                resource("flowcatalyst://event-types", "Event Types", "Event types.",
                        uri -> platform.getPretty("/api/event-types")),
                resource("flowcatalyst://subscriptions", "Subscriptions", "Webhook subscriptions.",
                        uri -> platform.getPretty("/api/subscriptions")));
    }

    /// The four single-entity templates, each `flowcatalyst://<plural>/{key}`
    /// → `/api/<plural>/{key}` (applications resolve by code, the others by id).
    public static List<McpServerFeatures.SyncResourceTemplateSpecification> templates(PlatformClient platform) {
        return List.of(
                template("flowcatalyst://event-types/{id}", "Event Type", "A single event type by id.",
                        "flowcatalyst://event-types/", "/api/event-types/", platform),
                template("flowcatalyst://subscriptions/{id}", "Subscription", "A single subscription by id.",
                        "flowcatalyst://subscriptions/", "/api/subscriptions/", platform),
                template("flowcatalyst://roles/{id}", "Role", "A single role by id.",
                        "flowcatalyst://roles/", "/api/roles/", platform),
                template("flowcatalyst://applications/{code}", "Application", "A single application by code.",
                        "flowcatalyst://applications/", "/api/applications/by-code/", platform));
    }

    private static McpServerFeatures.SyncResourceSpecification resource(String uri, String name, String description,
                                                                         Function<String, String> read) {
        var resource = McpSchema.Resource.builder(uri, name)
                .description(description)
                .mimeType(MIME_JSON)
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource,
                (exchange, request) -> readResult(request.uri(), read.apply(request.uri())));
    }

    /// `uriPrefix` is stripped from the requested URI to recover the
    /// templated key, which is then escaped into `apiPrefix` (Go
    /// `readByPrefix`: `strings.TrimPrefix` + `url.PathEscape`).
    private static McpServerFeatures.SyncResourceTemplateSpecification template(String uriTemplate, String name,
                                                                                 String description,
                                                                                 String uriPrefix, String apiPrefix,
                                                                                 PlatformClient platform) {
        var resourceTemplate = McpSchema.ResourceTemplate.builder(uriTemplate, name)
                .description(description)
                .mimeType(MIME_JSON)
                .build();
        return new McpServerFeatures.SyncResourceTemplateSpecification(resourceTemplate,
                (exchange, request) -> {
                    var key = request.uri().substring(uriPrefix.length());
                    var body = platform.getPretty(apiPrefix + PlatformClient.escapePathSegment(key));
                    return readResult(request.uri(), body);
                });
    }

    private static McpSchema.ReadResourceResult readResult(String uri, String text) {
        return new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, MIME_JSON, text, null)), null);
    }
}
