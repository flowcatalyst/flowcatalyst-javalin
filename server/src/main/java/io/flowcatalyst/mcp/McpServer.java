package io.flowcatalyst.mcp;

/// The MCP server subsystem's HTTP transport (`docs/spec/mcp.md` §1):
/// streamable HTTP at `/mcp` (POST + GET-as-SSE + DELETE) plus `GET /health`,
/// on its own `FC_MCP_BIND:FC_MCP_PORT` listener.
///
/// **Not available on the Vert.x listener** (`docs/vertx-plan.md` Q4, closed
/// 2026-09-08, part of the Javalin/Jetty removal): the Java MCP SDK
/// (`io.modelcontextprotocol.sdk:mcp` 2.0.1) ships its streamable-HTTP
/// server transport as a plain `jakarta.servlet.http.HttpServlet`
/// (`HttpServletStreamableServerTransportProvider`) with no
/// framework-agnostic alternative — it mounted on the Jetty Javalin used to
/// embed via `JettyConfig#modifyServletContextHandler`. Its SSE responses
/// also outlive the request that opens them (the SDK holds the response's
/// `AsyncContext` open and writes to it from other threads as the session's
/// reactive stream produces events), which does not fit the seam's
/// buffered-response model (`docs/spec/vertx-listener.md` §1 "dispatch model
/// B": the whole chain runs once, then one write). A native Vert.x
/// transport — a small `jakarta.servlet.http.HttpServletRequest`/`Response`/
/// `AsyncContext` bridge over a raw (non-seam) `HttpServer`, the way the
/// SDK's own reactive session already expects — is future work, not this
/// unit; [io.flowcatalyst.server.Server#start] fails fast with
/// [#UNAVAILABLE] when `FC_MCP_ENABLED=true` rather than silently starting
/// nothing. The domain logic this transport would serve ([McpTools],
/// [McpResources], [McpConfig], [PlatformClient], [TokenManager]) is
/// unaffected and still tested on its own.
public final class McpServer {

    /// The startup-error message [io.flowcatalyst.server.Server#start] raises
    /// for `FC_MCP_ENABLED=true` — one spelling, referenced from both the
    /// composition root and this class's own javadoc.
    public static final String UNAVAILABLE = "FC_MCP_ENABLED=true, but the MCP HTTP transport has no Vert.x "
            + "implementation yet (docs/vertx-plan.md Q4): its streamable-HTTP transport is a servlet the MCP SDK "
            + "ships no framework-agnostic alternative to; unset FC_MCP_ENABLED until MCP gets a native Vert.x "
            + "transport";

    private McpServer() {
    }
}
