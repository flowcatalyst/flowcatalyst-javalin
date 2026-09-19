package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.load.UnimplementedFunctionContext;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.HttpMethod;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.RoutePattern;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.auth.TokenClaims;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// The host's own small Vert.x `HttpServer` wrapper (spec
/// `function-host-listener.md` §0's departure table, §2): the event loop
/// buffers the request body, the whole seam chain (auth, permits, load,
/// invoke) runs on a fresh virtual thread, and the response is written back
/// with `runOnContext` — the same model-B shape as
/// `io.flowcatalyst.http.vertx.VertxListener`, without its admission-group
/// machinery (this host has no database and no request-group pools; its
/// admission is per-function permits, [Permits]).
public final class FnHttpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FnHttpServer.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "transfer-encoding", "keep-alive", "upgrade", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "content-length");

    private static final Set<String> CONSUMED_AUTH_HEADERS =
            Set.of("authorization", "x-flowcatalyst-signature", "x-flowcatalyst-timestamp");

    private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(60);

    private final Vertx vertx;
    private final HttpServer httpServer;
    private final Reconciler reconciler;
    private final Permits permits;
    private final PinnedVersions pinnedVersions;
    private final BearerAuthenticator bearerAuthenticator;
    private volatile boolean draining;
    private volatile int port;

    /// What [#start] needs beyond the [Reconciler] (spec §2, §5).
    ///
    /// @param maxConcurrency the host-global permit ceiling (`FC_FN_MAX_CONCURRENCY`)
    /// @param platformUrl    where the platform's `/.well-known/jwks.json` lives (`auth: platform`)
    public record Options(String host, int port, int maxConcurrency, String platformUrl, Clock clock) {
        public Options {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(platformUrl, "platformUrl");
            Objects.requireNonNull(clock, "clock");
        }

        public static Options of(int port, int maxConcurrency, String platformUrl) {
            return new Options("0.0.0.0", port, maxConcurrency, platformUrl, Clock.systemUTC());
        }
    }

    private FnHttpServer(Vertx vertx, HttpServer httpServer, Reconciler reconciler, Permits permits,
                          PinnedVersions pinnedVersions, BearerAuthenticator bearerAuthenticator) {
        this.vertx = vertx;
        this.httpServer = httpServer;
        this.reconciler = reconciler;
        this.permits = permits;
        this.pinnedVersions = pinnedVersions;
        this.bearerAuthenticator = bearerAuthenticator;
    }

    /// Builds and binds. Returns once the socket is listening.
    public static FnHttpServer start(Reconciler reconciler, Options options) {
        Objects.requireNonNull(reconciler, "reconciler");
        Objects.requireNonNull(options, "options");
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        Permits permits = new Permits(options.maxConcurrency());
        PinnedVersions pinnedVersions = new PinnedVersions(reconciler);
        JwksKeySource keySource =
                new JwksKeySource(HttpClient.newHttpClient(), options.platformUrl(), options.clock());
        BearerAuthenticator bearerAuthenticator =
                new BearerAuthenticator(keySource, options.platformUrl(), options.clock());

        FnHttpServer[] holder = new FnHttpServer[1];
        HttpServerOptions serverOptions = new HttpServerOptions()
                .setHost(options.host())
                .setPort(options.port())
                .setHttp2ClearTextEnabled(true);
        HttpServer server = vertx.createHttpServer(serverOptions)
                .requestHandler(req -> holder[0].handle(req));

        FnHttpServer instance = new FnHttpServer(vertx, server, reconciler, permits, pinnedVersions, bearerAuthenticator);
        holder[0] = instance;
        try {
            server.listen().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            vertx.close();
            throw new IllegalStateException("binding the function host listener on "
                    + options.host() + ":" + options.port(), e);
        }
        instance.port = server.actualPort();
        return instance;
    }

    public int port() {
        return port;
    }

    /// Reported `DRAINING` (spec §5): new requests get `503 DRAINING`
    /// immediately; in-flight requests finish. One-way.
    public void drain() {
        draining = true;
    }

    public PinnedVersions pinnedVersions() {
        return pinnedVersions;
    }

    /// Test-only seam: the permit gauges (H6, H7).
    Permits permitsForTest() {
        return permits;
    }

    // ── the request pipeline (spec §2) ──────────────────────────────────────

    private void handle(HttpServerRequest req) {
        io.vertx.core.Context requestContext = vertx.getOrCreateContext();

        // Step 1: draining.
        if (draining) {
            answer(req, requestContext, 503, Map.of("Retry-After", List.of("5")),
                    ErrorBody.json("DRAINING", "the host is draining"));
            return;
        }

        // Steps 2-3: parse + resolve. Cheap, no I/O — safe on the event loop.
        Resolution resolution = resolve(req);
        if (resolution instanceof Resolution.Error(HttpAnswer error)) {
            answer(req, requestContext, error.status(), error.headers(), error.body());
            return;
        }
        Resolution.Ready ready = (Resolution.Ready) resolution;

        // Step 4: endpoint match.
        EndpointMatch match = matchEndpoint(ready.entry().manifest(), ready.path().functionPath(), req.method().name());
        if (match instanceof EndpointMatch.NotFound) {
            answer(req, requestContext, 404, Map.of(), ErrorBody.json("ENDPOINT_NOT_FOUND", "no endpoint matches this path"));
            return;
        }
        if (match instanceof EndpointMatch.MethodNotAllowed(List<String> allowed)) {
            answer(req, requestContext, 405, Map.of("Allow", List.of(String.join(", ", allowed))),
                    ErrorBody.json("METHOD_NOT_ALLOWED", "method not allowed on this endpoint"));
            return;
        }
        EndpointMatch.Ok ok = (EndpointMatch.Ok) match;

        // Step 5: body cap — checked against THIS endpoint's own maxBodyBytes, before reading.
        int cap = ok.endpoint().maxBodyBytes();
        String declaredLength = req.getHeader("Content-Length");
        if (declaredLength != null) {
            try {
                if (Long.parseLong(declaredLength) > cap) {
                    answer(req, requestContext, 413, Map.of(), ErrorBody.json("BODY_TOO_LARGE", "request body exceeds the endpoint's limit"));
                    return;
                }
            } catch (NumberFormatException ignored) {
                // fall through — Vert.x itself will reject a malformed Content-Length
            }
        }

        Buffer buffer = Buffer.buffer();
        boolean[] oversized = {false};
        req.handler(chunk -> {
            if (oversized[0]) {
                return;
            }
            if (buffer.length() + chunk.length() > cap) {
                oversized[0] = true;
                return;
            }
            buffer.appendBuffer(chunk);
        });
        req.exceptionHandler(t -> {
            LOG.debug("request body read failed", t);
            answer(req, requestContext, 400, Map.of(), ErrorBody.json("BAD_REQUEST", "failed reading the request body"));
        });
        req.endHandler(v -> {
            if (oversized[0]) {
                answer(req, requestContext, 413, Map.of(), ErrorBody.json("BODY_TOO_LARGE", "request body exceeds the endpoint's limit"));
                return;
            }
            byte[] body = buffer.getBytes();
            Thread.ofVirtual().start(() -> {
                HttpAnswer result;
                try {
                    result = continueAfterBody(req, ready, ok.endpoint(), ok.pathParams(), body);
                } catch (RuntimeException e) {
                    // A safety net around auth/permits/load — the ONLY exceptions expected past this
                    // point are the invocation's own (already handled inside #continueAfterBody); an
                    // unexpected throw here must still answer the client, never leave the connection
                    // hanging forever (spec §2: never a stack trace or exception message in the body).
                    LOG.atError().setMessage("unexpected failure before/around invocation").setCause(e).log();
                    result = HttpAnswer.of(500, Map.of(), "INTERNAL", "internal error");
                }
                HttpAnswer finalResult = result;
                requestContext.runOnContext(v2 -> write(req, finalResult));
            });
        });
    }

    // ── steps 2-3: parse + resolve ──────────────────────────────────────────

    private sealed interface Resolution {
        record Ready(RoutePath path, DesiredDocument.Entry entry, boolean versioned, TokenClaims versionedCaller)
                implements Resolution {
        }

        record Error(HttpAnswer answer) implements Resolution {
        }
    }

    private Resolution resolve(HttpServerRequest req) {
        RoutePath.Result parsed = RoutePath.parse(req.path());
        return switch (parsed) {
            case RoutePath.NotFunctionsRoute ignored ->
                    new Resolution.Error(HttpAnswer.of(404, Map.of(), "NOT_FOUND", "not found"));
            case RoutePath.AddressInvalid ignored ->
                    new Resolution.Error(HttpAnswer.of(400, Map.of(), "ADDRESS_INVALID", "invalid function address"));
            case RoutePath.VersionInvalid ignored ->
                    new Resolution.Error(HttpAnswer.of(400, Map.of(), "VERSION_INVALID", "version must be a positive integer"));
            case RoutePath.Matched(RoutePath path) -> path.version() == null
                    ? resolveUnversioned(path)
                    : resolveVersioned(req, path);
        };
    }

    private Resolution resolveUnversioned(RoutePath path) {
        DesiredDocument.Entry entry = reconciler.liveEntry(path.address());
        if (entry == null) {
            return new Resolution.Error(HttpAnswer.of(404, Map.of(), "FUNCTION_NOT_FOUND", "no such function"));
        }
        return new Resolution.Ready(path, entry, false, null);
    }

    /// Spec §4: whatever the endpoint's own `auth` says, a versioned call
    /// ALWAYS needs a platform bearer token with `platform:function:version:invoke`
    /// plus reach — checked here, on the event loop, before the body is even
    /// read (none of these checks need it).
    private Resolution resolveVersioned(HttpServerRequest req, RoutePath path) {
        DesiredDocument.Entry entry = reconciler.entryFor(path.address(), path.version());
        if (entry == null) {
            return new Resolution.Error(HttpAnswer.of(404, Map.of(), "VERSION_NOT_AVAILABLE", "no such version"));
        }
        BearerAuthenticator.Outcome auth = bearerAuthenticator.authenticate(req.getHeader("Authorization"));
        if (auth instanceof BearerAuthenticator.Rejected(String reason)) {
            return new Resolution.Error(HttpAnswer.of(401, Map.of("WWW-Authenticate", List.of("Bearer")),
                    "UNAUTHORIZED", reason));
        }
        TokenClaims claims = ((BearerAuthenticator.Authenticated) auth).claims();
        if (!Permission.grants(claims.permissions(), Permission.FUNCTION_VERSION_INVOKE.code())) {
            return new Resolution.Error(HttpAnswer.of(403, Map.of(), "PERMISSION_REQUIRED",
                    "platform:function:version:invoke required"));
        }
        if (!hasReach(claims, entry)) {
            // Spec §4: reach failure is 404, not 403 — "same rule as the platform API".
            return new Resolution.Error(HttpAnswer.of(404, Map.of(), "VERSION_NOT_AVAILABLE", "no such version"));
        }
        return new Resolution.Ready(path, entry, true, claims);
    }

    private static boolean hasReach(TokenClaims claims, DesiredDocument.Entry entry) {
        boolean anchor = "ANCHOR".equalsIgnoreCase(claims.tier());
        if (anchor) {
            return true;
        }
        if (entry.clientId() == null) {
            return false; // a platform-owned function needs anchor
        }
        if (!claims.clients().contains(entry.clientId())) {
            return false;
        }
        if (entry.applicationId() == null) {
            return true;
        }
        return claims.allApplications() || claims.applications().contains(entry.applicationId());
    }

    // ── step 4: endpoint match ───────────────────────────────────────────────

    private sealed interface EndpointMatch {
        record Ok(Manifest.Endpoint endpoint, Map<String, String> pathParams) implements EndpointMatch {
        }

        record NotFound() implements EndpointMatch {
        }

        record MethodNotAllowed(List<String> allowed) implements EndpointMatch {
        }
    }

    private static EndpointMatch matchEndpoint(Manifest manifest, String functionPath, String rawMethod) {
        List<Manifest.Endpoint> endpoints = manifest.endpoints();
        List<RoutePattern> patterns = endpoints.stream().map(Manifest.Endpoint::path).toList();
        var match = RoutePattern.firstMatch(patterns, functionPath);
        if (match.isEmpty()) {
            return new EndpointMatch.NotFound();
        }
        Manifest.Endpoint endpoint = endpoints.stream()
                .filter(e -> e.path().equals(match.get().pattern()))
                .findFirst()
                .orElseThrow();

        // Spec §2 step 4: "webhook ⇒ POST" — an override of the manifest's own
        // (empty-means-all) `methods` storage, applied here regardless of what is stored.
        List<HttpMethod> effective = endpoint.auth() == io.flowcatalyst.platform.function.EndpointAuth.WEBHOOK
                ? List.of(HttpMethod.POST)
                : endpoint.methods();

        if (!effective.isEmpty()) {
            HttpMethod requested;
            try {
                requested = HttpMethod.parse(rawMethod);
            } catch (IllegalArgumentException e) {
                requested = null;
            }
            if (requested == null || !effective.contains(requested)) {
                return new EndpointMatch.MethodNotAllowed(effective.stream().map(Enum::name).toList());
            }
        }
        return new EndpointMatch.Ok(endpoint, match.get().params());
    }

    // ── steps 6-10: auth, permits, load, invoke, respond (runs on a virtual thread) ──

    private HttpAnswer continueAfterBody(HttpServerRequest req, Resolution.Ready ready, Manifest.Endpoint endpoint,
                                          Map<String, String> pathParams, byte[] body) {
        DesiredDocument.Entry entry = ready.entry();
        Caller caller;
        boolean stripAuthHeaders;

        if (ready.versioned()) {
            // Spec §4: "The endpoint's own auth is then not applied" — already authenticated
            // in #resolveVersioned; the caller is always a Principal.
            caller = principalFrom(ready.versionedCaller());
            stripAuthHeaders = true;
        } else {
            AuthResult authResult = authenticateUnversioned(endpoint, req.headers(), body, entry);
            if (authResult instanceof AuthResult.Failed(HttpAnswer failure)) {
                return failure;
            }
            caller = ((AuthResult.Ok) authResult).caller();
            stripAuthHeaders = endpoint.auth() != io.flowcatalyst.platform.function.EndpointAuth.NONE;
        }

        // Step 7: permits.
        int maxConcurrency = entry.manifest().limits().maxConcurrency();
        Permits.Grant grant = permits.tryAcquire(entry.address(), maxConcurrency);
        if (!grant.granted()) {
            return HttpAnswer.of(429, Map.of("Retry-After", List.of("1")), "BUSY", "the function is at capacity");
        }

        try {
            // Step 8: load.
            LoadedFunction fn = ready.versioned() ? pinnedVersions.getOrLoad(entry) : reconciler.ensureLoaded(entry.address());
            if (fn == null) {
                permits.release(grant);
                return ready.versioned()
                        ? HttpAnswer.of(404, Map.of(), "VERSION_NOT_AVAILABLE", "version is not loadable")
                        : HttpAnswer.of(503, Map.of("Retry-After", List.of("15")), "FUNCTION_UNAVAILABLE",
                        "the function could not be loaded");
            }

            // Step 9: invoke.
            String invocationId = Tsid.generate();
            io.flowcatalyst.function.FunctionAddress apiAddress = toApiAddress(entry.address());
            Request request = buildRequest(apiAddress, fn.version(), invocationId, req, ready.path().functionPath(),
                    pathParams, body, stripAuthHeaders, caller);
            UnimplementedFunctionContext ctx = new UnimplementedFunctionContext(apiAddress, fn.version());
            InvocationRunner.Invocation invocation = InvocationRunner.start(fn, request, ctx);
            long timeoutMs = endpoint.timeoutMs();
            try {
                Result result = invocation.future().get(timeoutMs, TimeUnit.MILLISECONDS);
                permits.release(grant);
                if (result == null) {
                    LOG.atWarn().setMessage("function returned a null Result")
                            .addKeyValue("address", entry.address().render())
                            .addKeyValue("invocation_id", invocationId)
                            .log();
                    return HttpAnswer.of(500, Map.of(), "FUNCTION_ERROR", "the function failed");
                }
                return toAnswer(result);
            } catch (TimeoutException e) {
                invocation.worker().interrupt();
                // The permit is released only once the worker actually finishes (H7): a
                // function that swallows the interrupt keeps it until it returns.
                invocation.future().whenComplete((r, ex) -> permits.release(grant));
                return HttpAnswer.of(504, Map.of(), "FUNCTION_TIMEOUT", "the invocation exceeded its deadline");
            } catch (ExecutionException e) {
                permits.release(grant);
                LOG.atWarn().setMessage("function invocation threw")
                        .addKeyValue("address", entry.address().render())
                        .addKeyValue("invocation_id", invocationId)
                        .setCause(e.getCause())
                        .log();
                return HttpAnswer.of(500, Map.of(), "FUNCTION_ERROR", "the function failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                permits.release(grant);
                return HttpAnswer.of(500, Map.of(), "FUNCTION_ERROR", "interrupted");
            }
        } catch (RuntimeException e) {
            permits.release(grant);
            throw e;
        }
    }

    // ── auth (unversioned, spec §3) ─────────────────────────────────────────

    private sealed interface AuthResult {
        record Ok(Caller caller) implements AuthResult {
        }

        record Failed(HttpAnswer answer) implements AuthResult {
        }
    }

    private AuthResult authenticateUnversioned(Manifest.Endpoint endpoint, MultiMap headers, byte[] body,
                                                DesiredDocument.Entry entry) {
        return switch (endpoint.auth()) {
            case WEBHOOK -> {
                String signature = headers.get("X-FlowCatalyst-Signature");
                String timestamp = headers.get("X-FlowCatalyst-Timestamp");
                String current = reconciler.currentWebhookSecret(entry.address()).orElse(null);
                String previous = reconciler.previousWebhookSecret(entry.address()).orElse(null);
                yield switch (WebhookVerifier.verify(body, signature, timestamp, current, previous)) {
                    case WebhookVerifier.Verified ignored -> new AuthResult.Ok(Caller.Platform.INSTANCE);
                    case WebhookVerifier.Rejected(String reason) -> new AuthResult.Failed(
                            HttpAnswer.of(401, Map.of(), "UNAUTHORIZED", reason));
                };
            }
            case PLATFORM -> {
                BearerAuthenticator.Outcome outcome = bearerAuthenticator.authenticate(headers.get("Authorization"));
                yield switch (outcome) {
                    case BearerAuthenticator.Authenticated(TokenClaims claims) ->
                            new AuthResult.Ok(principalFrom(claims));
                    case BearerAuthenticator.Rejected(String reason) -> new AuthResult.Failed(HttpAnswer.of(
                            401, Map.of("WWW-Authenticate", List.of("Bearer")), "UNAUTHORIZED", reason));
                };
            }
            case NONE -> new AuthResult.Ok(Caller.Anonymous.INSTANCE);
        };
    }

    /// A [Caller.Principal] can only truthfully carry ONE `clientId` (spec
    /// `function-invocation.md` §7's `Principal(id, type, clientId,
    /// permissions)`), while a token's `clients` claim is a LIST (an anchor
    /// carries `["*"]`, a partner principal may carry several real ids) — see
    /// the slice's handback report for the full finding. This host reports
    /// the client only when it is unambiguous: exactly one real (non-`*`)
    /// client on the token; otherwise `null`, same as an anchor or an
    /// unscoped principal.
    private static Caller.Principal principalFrom(TokenClaims claims) {
        String type = claims.principalType() == null ? "unknown" : claims.principalType();
        List<String> clients = claims.clients();
        String clientId = (clients.size() == 1 && !"*".equals(clients.get(0))) ? clients.get(0) : null;
        return new Caller.Principal(claims.subject(), type, clientId, Set.copyOf(claims.permissions()));
    }

    // ── building the Request value (spec §2) ────────────────────────────────

    private static io.flowcatalyst.function.FunctionAddress toApiAddress(FunctionAddress platformAddress) {
        return new io.flowcatalyst.function.FunctionAddress(
                platformAddress.application().value(), platformAddress.service().value(), platformAddress.name().value());
    }

    private static Request buildRequest(io.flowcatalyst.function.FunctionAddress apiAddress, int version,
                                         String invocationId, HttpServerRequest req, String functionPath,
                                         Map<String, String> pathParams, byte[] body, boolean stripAuthHeaders,
                                         Caller caller) {
        Map<String, List<String>> headers = collectHeaders(req.headers(), stripAuthHeaders);
        Map<String, List<String>> query = collectQuery(req);
        String remoteAddress = req.remoteAddress() == null ? null : req.remoteAddress().host();
        String host = originalHost(req);
        return new Request(apiAddress, version, invocationId, req.method().name(), functionPath, host, req.path(),
                pathParams, query, headers, body, remoteAddress, caller);
    }

    /// The `Host` the call actually arrived on (spec §2) — Vert.x exposes it
    /// as the request's `authority` (HTTP/1.1's `Host` header or HTTP/2's
    /// `:authority` pseudo-header, unified), not as an ordinary header
    /// [io.vertx.core.MultiMap] entry.
    private static String originalHost(HttpServerRequest req) {
        var authority = req.authority();
        if (authority == null) {
            return null;
        }
        return authority.port() > 0 ? authority.host() + ":" + authority.port() : authority.host();
    }

    private static Map<String, List<String>> collectHeaders(MultiMap headers, boolean stripAuthHeaders) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : headers.names()) {
            if (stripAuthHeaders && CONSUMED_AUTH_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.put(name, List.copyOf(headers.getAll(name)));
        }
        return out;
    }

    private static Map<String, List<String>> collectQuery(HttpServerRequest req) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : req.params().names()) {
            out.put(name, List.copyOf(req.params().getAll(name)));
        }
        return out;
    }

    // ── step 10: respond ─────────────────────────────────────────────────────

    private record HttpAnswer(int status, Map<String, List<String>> headers, byte[] body) {
        static HttpAnswer of(int status, Map<String, List<String>> headers, String code, String message) {
            return new HttpAnswer(status, headers, ErrorBody.json(code, message));
        }
    }

    private static HttpAnswer toAnswer(Result result) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (var e : result.headers().entrySet()) {
            if (HOP_BY_HOP.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            headers.put(e.getKey(), e.getValue());
        }
        return new HttpAnswer(result.status(), headers, result.body());
    }

    private void answer(HttpServerRequest req, io.vertx.core.Context requestContext, int status,
                         Map<String, List<String>> headers, byte[] body) {
        requestContext.runOnContext(v -> write(req, new HttpAnswer(status, headers, body)));
    }

    private static void write(HttpServerRequest req, HttpAnswer answer) {
        var response = req.response();
        if (response.ended() || response.closed()) {
            return;
        }
        response.setStatusCode(answer.status());
        for (var e : answer.headers().entrySet()) {
            // putHeader REPLACES; a multi-valued header must accumulate via addHeader
            // after the first value, or only the last value would ever reach the caller.
            boolean first = true;
            for (String value : e.getValue()) {
                if (first) {
                    response.putHeader(e.getKey(), value);
                    first = false;
                } else {
                    response.headers().add(e.getKey(), value);
                }
            }
        }
        if (!response.headers().contains("Content-Type") && answer.body().length > 0) {
            response.putHeader("Content-Type", "application/json");
        }
        response.end(Buffer.buffer(answer.body()));
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void close() {
        close(DEFAULT_DRAIN_TIMEOUT);
    }

    /// Bounded, same shape as `io.flowcatalyst.http.vertx.VertxListener#close`:
    /// [#drain] first, then wait at most `timeout` for the socket to finish
    /// closing (in-flight requests get to complete), then tear Vert.x down
    /// regardless — a function that never returns must not keep this call
    /// blocked past `timeout` (spec §5, H14).
    public void close(Duration timeout) {
        draining = true;
        try {
            httpServer.close().toCompletionStage().toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.atWarn().setMessage("closing the function host listener did not complete cleanly within the drain timeout").log();
        }
        pinnedVersions.close();
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.atWarn().setMessage("closing Vert.x did not complete cleanly").log();
        }
    }
}
