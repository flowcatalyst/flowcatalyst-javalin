package io.flowcatalyst.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/// A vertx-web implementation of the MCP SDK's streamable-HTTP server transport
/// (`docs/vertx-migration-brief.md` phase 3; `docs/spec/mcp.md` §1), modelled line
/// by line on the SDK's own
/// [io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider]
/// (mcp-core 2.0.1 sources, read before writing this class) — same protocol
/// behaviour over vertx-web instead of a servlet container: `POST` (JSON-RPC in;
/// a single JSON response, or an SSE response stream when the session decides
/// so), `GET` (the standalone SSE listening stream — `Mcp-Session-Id` required,
/// `Last-Event-ID` resume), `DELETE` (session end), the same `Mcp-Session-Id`
/// response header, the same [ServerTransportSecurityValidator] Origin/Host
/// checks (reused directly from mcp-core — that type is public and has no
/// servlet dependency), the same error statuses/bodies, `notifyClients`,
/// `closeGracefully`.
///
/// One difference from the servlet transport, both deliberate: (1) the servlet
/// checks `request.getRequestURI().endsWith(mcpEndpoint)` because one servlet
/// instance can be mapped under a path prefix; vertx-web's `Router#route(method,
/// path)` already matches only the exact `mcpEndpoint` path, so that check has
/// no equivalent need here. (2) the servlet has no security validator configured
/// by [McpServer] (`ServerTransportSecurityValidator.NOOP`, fully permissive);
/// this provider defaults to [DefaultServerTransportSecurityValidator] with
/// empty allow-lists, which is **not** the same as NOOP — it passes every
/// request with no `Origin` header (every non-browser MCP client, including the
/// SDK's own [io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport])
/// unaffected, but rejects one that *does* send an `Origin` header unless it is
/// allow-listed (403) — real DNS-rebinding protection with nothing to configure,
/// closing a gap the servlet-based `McpServer` left open.
///
/// Every request's JSON-RPC handling (which may call the platform over HTTP
/// through [PlatformClient] — `McpTools`) runs on a virtual thread, never the
/// Vert.x event loop; every response write is marshalled back onto the
/// request's own [Context] with [Context#runOnContext], the same dispatch
/// pattern [io.flowcatalyst.http.vertx.VertxListener] uses for the API
/// listener. A session's out-of-band messages (server notifications,
/// `notifyClients`/`notifyClient`, replay) reach the wire the same way: the
/// calling thread (also always a virtual thread — never the loop, `notifyClients`
/// fans out over `parallelStream()`) blocks on a latch until the write has been
/// handed to the loop, matching the "the Mono completes when the message has
/// been sent" contract [McpStreamableServerTransport#sendMessage] documents.
public final class VertxStreamableServerTransportProvider implements McpStreamableServerTransportProvider {

    private static final Logger LOG = LoggerFactory.getLogger(VertxStreamableServerTransportProvider.class);

    static final String MESSAGE_EVENT_TYPE = "message";
    private static final String APPLICATION_JSON = "application/json";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final long DEFAULT_REQUEST_MAX_SIZE = 16 * 1024 * 1024;

    private final Vertx vertx;
    private final ExecutorService virtualThreads;
    private final McpJsonMapper jsonMapper;
    private final String mcpEndpoint;
    private final boolean disallowDelete;
    private final McpTransportContextExtractor<RoutingContext> contextExtractor;
    private final ServerTransportSecurityValidator securityValidator;
    private final long requestMaxSize;

    /// Active sessions, keyed by `Mcp-Session-Id` — the same shape as the
    /// servlet transport's `sessions` map.
    private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();
    private volatile McpStreamableServerSession.Factory sessionFactory;
    private volatile boolean isClosing = false;

    private VertxStreamableServerTransportProvider(Vertx vertx, McpJsonMapper jsonMapper, String mcpEndpoint,
                                                    boolean disallowDelete,
                                                    McpTransportContextExtractor<RoutingContext> contextExtractor,
                                                    ServerTransportSecurityValidator securityValidator,
                                                    long requestMaxSize) {
        this.vertx = vertx;
        this.virtualThreads = Executors.newVirtualThreadPerTaskExecutor();
        this.jsonMapper = jsonMapper;
        this.mcpEndpoint = mcpEndpoint;
        this.disallowDelete = disallowDelete;
        this.contextExtractor = contextExtractor;
        this.securityValidator = securityValidator;
        this.requestMaxSize = requestMaxSize;
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /// Mounts `POST`/`GET`/`DELETE` on [#mcpEndpoint] onto `router` — called
    /// once by [McpServer] while building its own listener's router.
    public void mount(Router router) {
        router.route(HttpMethod.POST, mcpEndpoint).handler(this::handlePost);
        router.route(HttpMethod.GET, mcpEndpoint).handler(this::handleGet);
        router.route(HttpMethod.DELETE, mcpEndpoint).handler(this::handleDelete);
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            LOG.debug("no active sessions to broadcast message to");
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> sessions.values().parallelStream().forEach(session -> {
            try {
                session.sendNotification(method, params).block();
            } catch (Exception e) {
                LOG.atInfo().setMessage("failed to send message to session")
                        .addKeyValue("sessionId", session.getId())
                        .setCause(e)
                        .log();
            }
        }));
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return Mono.defer(() -> {
            McpStreamableServerSession session = sessions.get(sessionId);
            if (session == null) {
                LOG.debug("session {} not found", sessionId);
                return Mono.empty();
            }
            return session.sendNotification(method, params);
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            isClosing = true;
            LOG.debug("initiating graceful shutdown with {} active sessions", sessions.size());
            sessions.values().parallelStream().forEach(session -> {
                try {
                    session.closeGracefully().block();
                } catch (Exception e) {
                    LOG.atWarn().setMessage("failed to close session")
                            .addKeyValue("sessionId", session.getId())
                            .setCause(e)
                            .log();
                }
            });
            sessions.clear();
        }).then(Mono.fromRunnable(virtualThreads::shutdown));
    }

    // ── POST: JSON-RPC in ───────────────────────────────────────────────────

    private void handlePost(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        HttpServerResponse response = rc.response();
        if (isClosing) {
            response.setStatusCode(503).end("Server is shutting down");
            return;
        }

        Map<String, List<String>> headers = extractHeaders(request);
        try {
            securityValidator.validateHeaders(headers);
        } catch (ServerTransportSecurityException e) {
            respondSecurityError(response, e);
            return;
        }

        String contentLength = firstHeader(headers, HttpHeaders.CONTENT_LENGTH);
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > requestMaxSize) {
                    response.setStatusCode(413).end();
                    return;
                }
            } catch (NumberFormatException ignored) {
                // Malformed Content-Length: fall through, the read-time cap still applies.
            }
        }

        Context requestContext = vertx.getOrCreateContext();
        readBodyCapped(request, response, bodyBytes ->
                virtualThreads.execute(() -> handlePostBody(rc, requestContext, headers, bodyBytes)));
    }

    /// Runs on a virtual thread: parse, then either the initialize handshake or
    /// session dispatch (`accept`/`responseStream`), which may block on a
    /// platform HTTP call — never on the event loop.
    private void handlePostBody(RoutingContext rc, Context requestContext, Map<String, List<String>> headers,
                                 byte[] bodyBytes) {
        HttpServerResponse response = rc.response();
        try {
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

            String accept = firstHeader(headers, HttpHeaders.ACCEPT);
            List<String> badRequestErrors = new ArrayList<>();
            if (accept == null || !accept.contains(TEXT_EVENT_STREAM)) {
                badRequestErrors.add("text/event-stream required in Accept header");
            }
            if (accept == null || !accept.contains(APPLICATION_JSON)) {
                badRequestErrors.add("application/json required in Accept header");
            }

            McpTransportContext transportContext = contextExtractor.extract(rc);

            if (message instanceof McpSchema.JSONRPCRequest req && req.method().equals(McpSchema.METHOD_INITIALIZE)) {
                handleInitialize(response, requestContext, badRequestErrors, req);
                return;
            }

            String sessionId = firstHeader(headers, HttpHeaders.MCP_SESSION_ID);
            if (sessionId == null || sessionId.isBlank()) {
                badRequestErrors.add("Session ID required in mcp-session-id header");
            }
            if (!badRequestErrors.isEmpty()) {
                String combined = String.join("; ", badRequestErrors);
                requestContext.runOnContext(v -> writeJsonError(response, 400,
                        McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND).message(combined).build()));
                return;
            }

            McpStreamableServerSession session = sessions.get(sessionId);
            if (session == null) {
                requestContext.runOnContext(v -> writeJsonError(response, 404,
                        McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                                .message("Session not found: " + sessionId).build()));
                return;
            }

            if (message instanceof McpSchema.JSONRPCResponse resp) {
                session.accept(resp).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)).block();
                requestContext.runOnContext(v -> response.setStatusCode(202).end());
            } else if (message instanceof McpSchema.JSONRPCNotification notif) {
                session.accept(notif).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)).block();
                requestContext.runOnContext(v -> response.setStatusCode(202).end());
            } else if (message instanceof McpSchema.JSONRPCRequest req) {
                startResponseStream(rc, requestContext, sessionId, session, req, transportContext);
            } else {
                requestContext.runOnContext(v -> writeJsonError(response, 500,
                        McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Unknown message type").build()));
            }
        } catch (IllegalArgumentException | IOException e) {
            LOG.atError().setMessage("failed to deserialize message").setCause(e).log();
            requestContext.runOnContext(v -> writeJsonError(response, 400,
                    McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
                            .message("Invalid message format: " + e.getMessage()).build()));
        } catch (Exception e) {
            LOG.atError().setMessage("error handling message").setCause(e).log();
            requestContext.runOnContext(v -> writeJsonError(response, 500,
                    McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                            .message("Error processing message: " + e.getMessage()).build()));
        }
    }

    private void handleInitialize(HttpServerResponse response, Context requestContext, List<String> badRequestErrors,
                                   McpSchema.JSONRPCRequest jsonrpcRequest) {
        if (!badRequestErrors.isEmpty()) {
            String combined = String.join("; ", badRequestErrors);
            requestContext.runOnContext(v -> writeJsonError(response, 400,
                    McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND).message(combined).build()));
            return;
        }
        McpSchema.InitializeRequest initializeRequest = jsonMapper.convertValue(jsonrpcRequest.params(),
                new TypeRef<McpSchema.InitializeRequest>() {
                });
        McpStreamableServerSession.McpStreamableServerSessionInit init = sessionFactory.startSession(initializeRequest);
        sessions.put(init.session().getId(), init.session());
        try {
            McpSchema.InitializeResult initResult = init.initResult().block();
            String sessionId = init.session().getId();
            String json = jsonMapper.writeValueAsString(McpSchema.JSONRPCResponse.result(jsonrpcRequest.id(), initResult));
            requestContext.runOnContext(v -> {
                response.putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON);
                response.putHeader(HttpHeaders.MCP_SESSION_ID, sessionId);
                response.setStatusCode(200);
                response.end(Buffer.buffer(json));
            });
        } catch (Exception e) {
            LOG.atError().setMessage("failed to initialize session").setCause(e).log();
            requestContext.runOnContext(v -> writeJsonError(response, 500,
                    McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                            .message("Failed to initialize session: " + e.getMessage()).build()));
        }
    }

    private void startResponseStream(RoutingContext rc, Context requestContext, String sessionId,
                                      McpStreamableServerSession session, McpSchema.JSONRPCRequest jsonrpcRequest,
                                      McpTransportContext transportContext) {
        HttpServerResponse response = rc.response();
        var transport = new VertxStreamableMcpSessionTransport(sessionId, requestContext, response);
        requestContext.runOnContext(v -> {
            if (!response.ended() && !response.closed()) setSseHeaders(response);
        });
        try {
            session.responseStream(jsonrpcRequest, transport)
                    .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                    .block();
        } catch (Exception e) {
            LOG.atError().setMessage("failed to handle request stream").setCause(e).log();
            transport.close();
        }
    }

    // ── GET: the standalone SSE listening stream ────────────────────────────

    private void handleGet(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        HttpServerResponse response = rc.response();
        if (isClosing) {
            response.setStatusCode(503).end("Server is shutting down");
            return;
        }

        Map<String, List<String>> headers = extractHeaders(request);
        try {
            securityValidator.validateHeaders(headers);
        } catch (ServerTransportSecurityException e) {
            respondSecurityError(response, e);
            return;
        }

        List<String> badRequestErrors = new ArrayList<>();
        String accept = firstHeader(headers, HttpHeaders.ACCEPT);
        if (accept == null || !accept.contains(TEXT_EVENT_STREAM)) {
            badRequestErrors.add("text/event-stream required in Accept header");
        }
        String sessionId = firstHeader(headers, HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            badRequestErrors.add("Session ID required in mcp-session-id header");
        }
        if (!badRequestErrors.isEmpty()) {
            writeJsonError(response, 400, McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
                    .message(String.join("; ", badRequestErrors)).build());
            return;
        }

        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            response.setStatusCode(404).end();
            return;
        }

        Context requestContext = vertx.getOrCreateContext();
        McpTransportContext transportContext = contextExtractor.extract(rc);
        String lastEventId = firstHeader(headers, HttpHeaders.LAST_EVENT_ID);

        virtualThreads.execute(() ->
                handleGetOnVirtualThread(rc, requestContext, session, sessionId, transportContext, lastEventId));
    }

    private void handleGetOnVirtualThread(RoutingContext rc, Context requestContext, McpStreamableServerSession session,
                                           String sessionId, McpTransportContext transportContext, String lastEventId) {
        HttpServerResponse response = rc.response();
        var transport = new VertxStreamableMcpSessionTransport(sessionId, requestContext, response);

        if (lastEventId != null) {
            requestContext.runOnContext(v -> {
                if (!response.ended() && !response.closed()) setSseHeaders(response);
            });
            try {
                session.replay(lastEventId)
                        .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                        .toIterable()
                        .forEach(message -> {
                            try {
                                transport.sendMessage(message)
                                        .contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
                                        .block();
                            } catch (Exception e) {
                                LOG.atError().setMessage("failed to replay message").setCause(e).log();
                                transport.close();
                            }
                        });
            } catch (Exception e) {
                LOG.atError().setMessage("failed to replay messages").setCause(e).log();
                transport.close();
            }
        } else {
            // Registered BEFORE anything client-visible (headers) so a concurrent
            // notifyClients/notifyClient can never lose the race against a client
            // that only waited for headers to arrive before broadcasting — see
            // VertxStreamableServerTransportProviderTest's GET/notification test.
            McpStreamableServerSession.McpStreamableServerSessionStream listeningStream = session.listeningStream(transport);
            requestContext.runOnContext(v -> {
                if (response.ended() || response.closed()) {
                    listeningStream.close();
                    return;
                }
                setSseHeaders(response);
                response.closeHandler(v2 -> listeningStream.close());
                response.exceptionHandler(t -> listeningStream.close());
            });
        }
    }

    /// No `Connection: keep-alive` header: Vert.x's `HttpServer` accepts h2c by
    /// default (`HttpServerOptions#DEFAULT_HTTP2_CLEAR_TEXT_ENABLED`), and
    /// HTTP/2 forbids hop-by-hop headers like `Connection` outright (RFC 7540
    /// §8.1.2.2) — a client that upgrades rejects the whole response as
    /// malformed. The servlet transport's `Connection: keep-alive` has no
    /// equivalent need here: HTTP/1.1 keep-alive is already the default.
    private static void setSseHeaders(HttpServerResponse response) {
        response.setChunked(true);
        response.putHeader(HttpHeaders.CONTENT_TYPE, TEXT_EVENT_STREAM);
        response.putHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    }

    // ── DELETE: session end ──────────────────────────────────────────────────

    private void handleDelete(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        HttpServerResponse response = rc.response();
        if (isClosing) {
            response.setStatusCode(503).end("Server is shutting down");
            return;
        }

        Map<String, List<String>> headers = extractHeaders(request);
        try {
            securityValidator.validateHeaders(headers);
        } catch (ServerTransportSecurityException e) {
            respondSecurityError(response, e);
            return;
        }

        if (disallowDelete) {
            response.setStatusCode(405).end();
            return;
        }

        McpTransportContext transportContext = contextExtractor.extract(rc);
        String sessionId = firstHeader(headers, HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null) {
            writeJsonError(response, 400, McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
                    .message("Session ID required in mcp-session-id header").build());
            return;
        }
        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            response.setStatusCode(404).end();
            return;
        }

        Context requestContext = vertx.getOrCreateContext();
        virtualThreads.execute(() -> {
            try {
                session.delete().contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext)).block();
                sessions.remove(sessionId);
                requestContext.runOnContext(v -> response.setStatusCode(200).end());
            } catch (Exception e) {
                LOG.atError().setMessage("failed to delete session")
                        .addKeyValue("sessionId", sessionId)
                        .setCause(e)
                        .log();
                requestContext.runOnContext(v -> writeJsonError(response, 500,
                        McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(e.getMessage()).build()));
            }
        });
    }

    // ── shared plumbing ──────────────────────────────────────────────────────

    /// Reads the request body on the event loop, capped at [#requestMaxSize]
    /// (413 on overflow, the same status the servlet transport's
    /// `MaxSizeExceededException` path answers) — mirrors
    /// [io.flowcatalyst.http.vertx.VertxListener]'s own body-read pattern
    /// rather than depending on vertx-web's `BodyHandler`, since this
    /// provider mounts its own routes without one.
    private void readBodyCapped(HttpServerRequest request, HttpServerResponse response, Consumer<byte[]> onBody) {
        Buffer buffer = Buffer.buffer();
        boolean[] oversized = {false};
        request.handler(chunk -> {
            if (oversized[0]) return;
            if (buffer.length() + chunk.length() > requestMaxSize) {
                oversized[0] = true;
                return;
            }
            buffer.appendBuffer(chunk);
        });
        request.exceptionHandler(t -> {
            LOG.debug("request body read failed on {} {}", request.method(), request.path(), t);
            if (!response.ended() && !response.closed()) response.setStatusCode(400).end();
        });
        request.endHandler(v -> {
            if (oversized[0]) {
                response.setStatusCode(413).end();
                return;
            }
            onBody.accept(buffer.getBytes());
        });
    }

    private static Map<String, List<String>> extractHeaders(HttpServerRequest request) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : request.headers().names()) {
            headers.put(name, request.headers().getAll(name));
        }
        return headers;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /// The security validator's own status/message, unwrapped verbatim — the
    /// same as the servlet transport's `response.sendError(statusCode, message)`
    /// (a plain container error page, not the JSON [McpError] envelope
    /// [#writeJsonError] produces for protocol-level bad requests).
    private static void respondSecurityError(HttpServerResponse response, ServerTransportSecurityException e) {
        if (response.ended() || response.closed()) return;
        response.setStatusCode(e.getStatusCode());
        response.end(e.getMessage() == null ? "" : e.getMessage());
    }

    private void writeJsonError(HttpServerResponse response, int statusCode, McpError mcpError) {
        if (response.ended() || response.closed()) return;
        try {
            String json = jsonMapper.writeValueAsString(mcpError);
            response.putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON);
            response.setStatusCode(statusCode);
            response.end(Buffer.buffer(json));
        } catch (Exception e) {
            LOG.atError().setMessage("failed to send error response").setCause(e).log();
            if (!response.ended() && !response.closed()) response.setStatusCode(500).end();
        }
    }

    private static void writeSseEvent(HttpServerResponse response, String eventType, String data, String id) {
        var sb = new StringBuilder();
        if (id != null) sb.append("id: ").append(id).append('\n');
        sb.append("event: ").append(eventType).append('\n');
        sb.append("data: ").append(data).append("\n\n");
        response.write(Buffer.buffer(sb.toString()));
    }

    /// [McpStreamableServerTransport] for one session's SSE stream (a POST's
    /// response stream, or a GET's listening stream) — the Vert.x equivalent of
    /// the servlet transport's `HttpServletStreamableMcpSessionTransport`, over
    /// an [HttpServerResponse] instead of an `AsyncContext`/`PrintWriter`. Every
    /// write hops onto `context` with [Context#runOnContext] (Vert.x forbids
    /// touching a response from any other thread) and the calling (virtual)
    /// thread blocks on a latch until that hop completes, so [#sendMessage]'s
    /// `Mono` keeps the SDK's "completes when sent" contract.
    private final class VertxStreamableMcpSessionTransport implements McpStreamableServerTransport {
        private final String sessionId;
        private final Context context;
        private final HttpServerResponse response;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean closed = false;

        VertxStreamableMcpSessionTransport(String sessionId, Context context, HttpServerResponse response) {
            this.sessionId = sessionId;
            this.context = context;
            this.response = response;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return sendMessage(message, null);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return Mono.fromRunnable(() -> {
                if (closed) {
                    LOG.debug("attempted to send message to closed session: {}", sessionId);
                    return;
                }
                lock.lock();
                try {
                    if (closed) return;
                    String json;
                    try {
                        json = jsonMapper.writeValueAsString(message);
                    } catch (Exception e) {
                        LOG.atError().setMessage("failed to serialise message for session")
                                .addKeyValue("sessionId", sessionId)
                                .setCause(e)
                                .log();
                        return;
                    }
                    String eventId = messageId != null ? messageId : sessionId;
                    var latch = new CountDownLatch(1);
                    context.runOnContext(v -> {
                        try {
                            if (!response.ended() && !response.closed()) {
                                writeSseEvent(response, MESSAGE_EVENT_TYPE, json, eventId);
                            }
                        } catch (Exception e) {
                            LOG.atError().setMessage("failed to send message to session")
                                    .addKeyValue("sessionId", sessionId)
                                    .setCause(e)
                                    .log();
                            sessions.remove(sessionId);
                            closed = true;
                            if (!response.ended() && !response.closed()) response.end();
                        } finally {
                            latch.countDown();
                        }
                    });
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    lock.unlock();
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::close);
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (closed) {
                    LOG.debug("session transport {} already closed", sessionId);
                    return;
                }
                closed = true;
                context.runOnContext(v -> {
                    if (!response.ended() && !response.closed()) response.end();
                });
            } finally {
                lock.unlock();
            }
        }
    }

    public static Builder builder(Vertx vertx) {
        return new Builder(vertx);
    }

    /// Same shape as [io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider.Builder],
    /// minus what only makes sense for a servlet container.
    public static final class Builder {
        private final Vertx vertx;
        private McpJsonMapper jsonMapper;
        private String mcpEndpoint = "/mcp";
        private boolean disallowDelete = false;
        private McpTransportContextExtractor<RoutingContext> contextExtractor = rc -> McpTransportContext.EMPTY;
        private ServerTransportSecurityValidator securityValidator =
                DefaultServerTransportSecurityValidator.builder().build();
        private long requestMaxSize = DEFAULT_REQUEST_MAX_SIZE;

        private Builder(Vertx vertx) {
            this.vertx = Objects.requireNonNull(vertx, "vertx");
        }

        public Builder jsonMapper(McpJsonMapper jsonMapper) {
            this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
            return this;
        }

        public Builder mcpEndpoint(String mcpEndpoint) {
            this.mcpEndpoint = Objects.requireNonNull(mcpEndpoint, "mcpEndpoint");
            return this;
        }

        public Builder disallowDelete(boolean disallowDelete) {
            this.disallowDelete = disallowDelete;
            return this;
        }

        public Builder contextExtractor(McpTransportContextExtractor<RoutingContext> contextExtractor) {
            this.contextExtractor = Objects.requireNonNull(contextExtractor, "contextExtractor");
            return this;
        }

        public Builder securityValidator(ServerTransportSecurityValidator securityValidator) {
            this.securityValidator = Objects.requireNonNull(securityValidator, "securityValidator");
            return this;
        }

        public Builder maxRequestSize(long requestMaxSize) {
            this.requestMaxSize = requestMaxSize;
            return this;
        }

        public VertxStreamableServerTransportProvider build() {
            Objects.requireNonNull(jsonMapper, "jsonMapper must be set");
            return new VertxStreamableServerTransportProvider(vertx, jsonMapper, mcpEndpoint, disallowDelete,
                    contextExtractor, securityValidator, requestMaxSize);
        }
    }
}
