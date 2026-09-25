package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.shared.auth.jwks.BearerAuthenticator;
import io.flowcatalyst.platform.shared.auth.jwks.JwksKeySource;
import io.flowcatalyst.fnhost.FunctionInvocationEvent;
import io.flowcatalyst.fnhost.load.LoadedFunction;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.fnhost.route.PublicRouteTable;
import io.flowcatalyst.fnhost.route.TrustedProxies;
import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.platform.function.EndpointAuth;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.HttpMethod;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.RoutePattern;
import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.auth.ScopeClaim;
import io.flowcatalyst.platform.shared.auth.TokenClaims;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.server.Logging;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// The host's own small Vert.x `HttpServer` wrapper (spec
/// `function-host-listener.md` §0's departure table, §2): the event loop
/// does only cheap parsing, draining, and — for an UNVERSIONED call — the
/// live lookup, endpoint match and body cap; everything that can block
/// (authentication, permits, load, invoke) runs on a fresh virtual thread,
/// and the response is written back with `runOnContext` — the same model-B
/// shape as `io.flowcatalyst.http.vertx.VertxListener`, without its
/// admission-group machinery (this host has no database and no request-group
/// pools; its admission is per-function permits, [Permits]).
///
/// A VERSIONED call (spec §4) is different: its `platform:function:version:invoke`
/// authentication, permission and reach checks must run BEFORE the entry (and
/// therefore its manifest/endpoint) may be looked at at all — an
/// unauthenticated caller must not be able to tell a version exists by the
/// shape of the failure. So for a versioned call the event loop does nothing
/// but buffer the body under a conservative cap ([#VERSIONED_BODY_CAP_BYTES]);
/// auth, permission, entry/reach, endpoint match and the endpoint's own body
/// cap all run together on the virtual thread, in that order.
public final class FnHttpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FnHttpServer.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "transfer-encoding", "keep-alive", "upgrade", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "content-length");

    private static final Set<String> CONSUMED_AUTH_HEADERS =
            Set.of("authorization", "x-flowcatalyst-signature", "x-flowcatalyst-timestamp");

    private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(60);

    /// A versioned call's entry (and so its OWN `maxBodyBytes`) must not be
    /// revealed before authentication (spec §4), so the event loop buffers
    /// under this conservative, fixed cap instead; the endpoint's own,
    /// possibly tighter, cap is then checked on the virtual thread once the
    /// entry is known (spec §2 step 5 / §4).
    private static final long VERSIONED_BODY_CAP_BYTES = 1_048_576; // 1 MiB

    private final Vertx vertx;
    private final HttpServer httpServer;
    private final HttpServer publicHttpServer;
    private final Reconciler reconciler;
    private final Permits permits;
    private final PinnedVersions pinnedVersions;
    private final BearerAuthenticator bearerAuthenticator;
    private final InvocationObserver observer;
    private final Clock clock;
    private final TrustedProxies trustedProxies;
    private volatile boolean draining;
    private volatile int port;
    private volatile int publicPort;

    /// `Options#publicPort`'s "no public listener" sentinel — spec
    /// `function-public-routes.md` §3's wire value `off`, mirroring
    /// [io.flowcatalyst.fnhost.reconcile.HostEnv#PUBLIC_PORT_DISABLED].
    public static final int PUBLIC_PORT_DISABLED = -1;

    /// What [#start] needs beyond the [Reconciler] (spec §2, §5, and §3/§4 of
    /// `function-public-routes.md` for the public listener + CORS).
    ///
    /// @param maxConcurrency    the host-global permit ceiling (`FC_FN_MAX_CONCURRENCY`)
    /// @param platformUrl       where the platform's `/.well-known/jwks.json` lives (`auth: platform`)
    /// @param eventLoopPoolSize the Vert.x event-loop thread count — a test-only seam (H1's
    ///                          "the event loop is not blocked" proof needs exactly one to mean
    ///                          anything); production never overrides Vert.x's own default
    /// @param observer          D5 (`function-host-process.md` §2): told about permits/refusals/
    ///                          entry/exit/outcome — `NOOP` unless a caller (`FnHost`) wires
    ///                          `FnMetrics` in. Kept as this interface, not a Prometheus type,
    ///                          so this class stays free of any metrics-library dependency.
    /// @param publicPort        the public listener's bind port; [#PUBLIC_PORT_DISABLED] (the
    ///                          default on every pre-F2 constructor below) starts no public
    ///                          listener at all — same host as `host` above
    /// @param trustedProxies    spec `function-public-routes.md` §3's CIDR allow-list for the
    ///                          public listener's `X-Forwarded-For` trust decision
    public record Options(String host, int port, int maxConcurrency, String platformUrl, Clock clock,
                           int eventLoopPoolSize, InvocationObserver observer, int publicPort,
                           TrustedProxies trustedProxies) {
        public Options {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(platformUrl, "platformUrl");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(observer, "observer");
            Objects.requireNonNull(trustedProxies, "trustedProxies");
            if (eventLoopPoolSize <= 0) {
                throw new IllegalArgumentException("eventLoopPoolSize must be positive: " + eventLoopPoolSize);
            }
        }

        /// Pre-F2 shape: no public listener.
        public Options(String host, int port, int maxConcurrency, String platformUrl, Clock clock,
                        int eventLoopPoolSize, InvocationObserver observer) {
            this(host, port, maxConcurrency, platformUrl, clock, eventLoopPoolSize, observer, PUBLIC_PORT_DISABLED,
                    TrustedProxies.DEFAULT);
        }

        /// No observer wired; no public listener.
        public Options(String host, int port, int maxConcurrency, String platformUrl, Clock clock,
                        int eventLoopPoolSize) {
            this(host, port, maxConcurrency, platformUrl, clock, eventLoopPoolSize, InvocationObserver.NOOP);
        }

        /// Vert.x's own default event-loop pool size; no observer wired; no public listener.
        public Options(String host, int port, int maxConcurrency, String platformUrl, Clock clock) {
            this(host, port, maxConcurrency, platformUrl, clock, VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE,
                    InvocationObserver.NOOP);
        }

        /// The same options bound on `newHost` — tests bind the loopback address they dial
        /// (`127.0.0.1`): on macOS a wildcard bind can land on a port a `127.0.0.1` listener
        /// already holds (SO_REUSEADDR), and that listener then receives the connections.
        public Options withHost(String newHost) {
            return new Options(newHost, port, maxConcurrency, platformUrl, clock, eventLoopPoolSize, observer,
                    publicPort, trustedProxies);
        }

        public static Options of(int port, int maxConcurrency, String platformUrl) {
            return new Options("0.0.0.0", port, maxConcurrency, platformUrl, Clock.systemUTC());
        }

        public static Options of(int port, int maxConcurrency, String platformUrl, InvocationObserver observer) {
            return new Options("0.0.0.0", port, maxConcurrency, platformUrl, Clock.systemUTC(),
                    VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE, observer);
        }

        /// With a public listener — [io.flowcatalyst.fnhost.FnHost]'s own composition root.
        public static Options of(int port, int maxConcurrency, String platformUrl, InvocationObserver observer,
                                  int publicPort, TrustedProxies trustedProxies) {
            return new Options("0.0.0.0", port, maxConcurrency, platformUrl, Clock.systemUTC(),
                    VertxOptions.DEFAULT_EVENT_LOOP_POOL_SIZE, observer, publicPort, trustedProxies);
        }
    }

    private FnHttpServer(Vertx vertx, HttpServer httpServer, HttpServer publicHttpServer, Reconciler reconciler,
                          Permits permits, PinnedVersions pinnedVersions, BearerAuthenticator bearerAuthenticator,
                          InvocationObserver observer, Clock clock, TrustedProxies trustedProxies) {
        this.vertx = vertx;
        this.httpServer = httpServer;
        this.publicHttpServer = publicHttpServer;
        this.reconciler = reconciler;
        this.permits = permits;
        this.pinnedVersions = pinnedVersions;
        this.bearerAuthenticator = bearerAuthenticator;
        this.observer = observer;
        this.clock = clock;
        this.trustedProxies = trustedProxies;
    }

    /// Builds and binds. Returns once the socket is listening.
    public static FnHttpServer start(Reconciler reconciler, Options options) {
        Objects.requireNonNull(reconciler, "reconciler");
        Objects.requireNonNull(options, "options");
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(options.eventLoopPoolSize()));
        Permits permits = new Permits(options.maxConcurrency());
        options.observer().permitsReady(permits);
        PinnedVersions pinnedVersions = new PinnedVersions(reconciler);
        // Spec §4: a pinned candidate is closed once its version leaves desired state —
        // swept after every reconcile, not just at load time (nothing else ever calls
        // #sweep, so a pinned entry would otherwise never be evicted at all).
        reconciler.addPostReconcileListener(pinnedVersions::sweep);
        JwksKeySource keySource =
                new JwksKeySource(HttpClient.newHttpClient(), options.platformUrl(), options.clock());
        BearerAuthenticator bearerAuthenticator =
                new BearerAuthenticator(keySource, options.clock());

        FnHttpServer[] holder = new FnHttpServer[1];
        HttpServerOptions serverOptions = new HttpServerOptions()
                .setHost(options.host())
                .setPort(options.port())
                .setHttp2ClearTextEnabled(true);
        // A connection with no request in flight closes after 75 s (KeepAliveIdle, backlog item 10);
        // a long invocation keeps its connection busy, so it is never cut.
        HttpServer server = vertx.createHttpServer(serverOptions);
        server.requestHandler(io.flowcatalyst.http.vertx.KeepAliveIdle.install(vertx, server, req -> holder[0].handle(req)));

        // Spec `function-public-routes.md` §3: a SECOND entry, same Vert.x instance, sharing
        // permits/registry/reconciler/observer — bound only when a public port was configured
        // (PUBLIC_PORT_DISABLED, the default on every pre-F2 Options constructor, binds nothing).
        HttpServer publicServer = null;
        if (options.publicPort() != PUBLIC_PORT_DISABLED) {
            HttpServerOptions publicServerOptions = new HttpServerOptions()
                    .setHost(options.host())
                    .setPort(options.publicPort())
                    .setHttp2ClearTextEnabled(true);
            publicServer = vertx.createHttpServer(publicServerOptions);
            publicServer.requestHandler(io.flowcatalyst.http.vertx.KeepAliveIdle.install(vertx, publicServer,
                    req -> holder[0].handlePublic(req)));
        }

        FnHttpServer instance = new FnHttpServer(vertx, server, publicServer, reconciler, permits, pinnedVersions,
                bearerAuthenticator, options.observer(), options.clock(), options.trustedProxies());
        holder[0] = instance;
        try {
            server.listen().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            if (publicServer != null) {
                publicServer.listen().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            vertx.close();
            throw new IllegalStateException("binding the function host listener(s) on "
                    + options.host() + ":" + options.port() + (publicServer == null ? "" : " / :" + options.publicPort()), e);
        }
        instance.port = server.actualPort();
        instance.publicPort = publicServer == null ? PUBLIC_PORT_DISABLED : publicServer.actualPort();
        return instance;
    }

    public int port() {
        return port;
    }

    /// The public listener's bound port, or [#PUBLIC_PORT_DISABLED] when
    /// none was configured (spec §3).
    public int publicPort() {
        return publicPort;
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

        // Step 2: parse. Cheap, no I/O — safe on the event loop.
        RoutePath.Result parsed = RoutePath.parse(req.path());
        switch (parsed) {
            case RoutePath.NotFunctionsRoute ignored ->
                    answer(req, requestContext, 404, Map.of(), ErrorBody.json("NOT_FOUND", "not found"));
            case RoutePath.AddressInvalid ignored ->
                    answer(req, requestContext, 400, Map.of(), ErrorBody.json("ADDRESS_INVALID", "invalid function address"));
            case RoutePath.VersionInvalid ignored -> answer(req, requestContext, 400, Map.of(),
                    ErrorBody.json("VERSION_INVALID", "version must be a positive integer"));
            case RoutePath.Matched(RoutePath path) -> {
                if (path.version() == null) {
                    handleUnversioned(req, requestContext, path);
                } else {
                    handleVersioned(req, requestContext, path);
                }
            }
        }
    }

    /// Step 3 (unversioned) + step 4: the live lookup and the endpoint match
    /// both run on the event loop — an unversioned call reveals nothing that
    /// authentication would otherwise gate (spec §3's per-endpoint `auth` is
    /// what protects an unversioned call, and it runs on the virtual thread
    /// once the body is read, same as ever).
    private void handleUnversioned(HttpServerRequest req, io.vertx.core.Context requestContext, RoutePath path) {
        DesiredDocument.Entry entry = reconciler.liveEntry(path.address());
        if (entry == null) {
            // address = null: an unknown address is never a metrics label value
            // (spec `function-host-process.md` §2's cardinality rule) — see
            // InvocationObserver's own doc for why.
            observer.refused("not_found", null, InvocationObserver.Entry.PRIVATE);
            answer(req, requestContext, 404, Map.of(), ErrorBody.json("FUNCTION_NOT_FOUND", "no such function"));
            return;
        }
        handleEntry(req, requestContext, entry, path.functionPath(), InvocationObserver.Entry.PRIVATE, null, false);
    }

    // ── public entry (function-public-routes.md §3) ─────────────────────────

    /// The public listener's own request path: `Host` (or `:authority`, spec
    /// §3 step 1) → longest whole-segment prefix over the current
    /// [PublicRouteTable] → the shared pipeline ([#handleEntry]) — NEVER
    /// [RoutePath] parsing, so `/functions/…` is just an ordinary path here
    /// (spec: "is not special here"; there is no by-address and no versioned
    /// access on this listener at all).
    private void handlePublic(HttpServerRequest req) {
        io.vertx.core.Context requestContext = vertx.getOrCreateContext();

        if (draining) {
            answer(req, requestContext, 503, Map.of("Retry-After", List.of("5")),
                    ErrorBody.json("DRAINING", "the host is draining"));
            return;
        }

        String hostname = publicHostname(req);
        if (hostname == null) {
            observer.refused("not_found", null, InvocationObserver.Entry.PUBLIC);
            answer(req, requestContext, 404, Map.of(), ErrorBody.json("NOT_FOUND", "not found"));
            return;
        }

        Optional<PublicRouteTable.Match> matched = reconciler.publicRouteTable().match(hostname, req.path());
        if (matched.isEmpty()) {
            observer.refused("not_found", null, InvocationObserver.Entry.PUBLIC);
            answer(req, requestContext, 404, Map.of(), ErrorBody.json("NOT_FOUND", "not found"));
            return;
        }

        PublicRouteTable.Match m = matched.get();
        // spec `function-zones-and-aliases.md` §4: "live" is the exact-hostname match,
        // unchanged; anything else is an alias-prefixed hostname, resolved through
        // entryForAlias and run over the VERSIONED load path (pinnedVersions) so the
        // aliased version's OWN manifest drives endpoint matching, auth and limits —
        // never the address's live entry.
        boolean isLive = "live".equals(m.alias());
        DesiredDocument.Entry entry =
                isLive ? reconciler.liveEntry(m.address()) : reconciler.entryForAlias(m.address(), m.alias());
        if (entry == null) {
            // The route table can briefly name a function the entry lookup no longer
            // finds (a reconcile in between the two reads), or an alias the document
            // does not (yet, or ever) carry for this address — 404, same anti-leak
            // shape, never a 500; the next reconcile's swapped table/document clears
            // this up either way.
            observer.refused("not_found", null, InvocationObserver.Entry.PUBLIC);
            answer(req, requestContext, 404, Map.of(), ErrorBody.json("NOT_FOUND", "not found"));
            return;
        }

        String remoteAddress = publicRemoteAddress(req);
        handleEntry(req, requestContext, entry, m.functionPath(), InvocationObserver.Entry.PUBLIC, remoteAddress,
                !isLive);
    }

    /// spec §3 step 1: `Host` (HTTP/1.1) or `:authority` (HTTP/2, Vert.x
    /// unifies both as [HttpServerRequest#authority]) — lower-cased, port
    /// stripped, must parse as a [Hostname] or the request is 404.
    /// `X-Forwarded-Host` is deliberately never consulted (spec: "ignored" —
    /// only the load balancer may pick the route).
    private static String publicHostname(HttpServerRequest req) {
        var authority = req.authority();
        if (authority == null || authority.host() == null || authority.host().isBlank()) {
            return null;
        }
        String lower = authority.host().toLowerCase(Locale.ROOT);
        return switch (Hostname.check(lower)) {
            case io.flowcatalyst.sdk.result.Result.Ok<Hostname, Hostname.Invalid> ok -> ok.value().value();
            case io.flowcatalyst.sdk.result.Result.Err<Hostname, Hostname.Invalid> ignored -> null;
        };
    }

    /// spec §3's trust rule: the right-most `X-Forwarded-For` entry ONLY
    /// when the TCP peer is in [#trustedProxies]; an untrusted peer, a
    /// missing header, or a malformed entry (not a parseable IP literal)
    /// all fall back to the TCP peer itself.
    private String publicRemoteAddress(HttpServerRequest req) {
        var peer = req.remoteAddress();
        String peerHost = peer == null ? null : peer.host();
        if (peerHost == null) {
            return null;
        }
        if (!trustedProxies.isTrusted(peerHost)) {
            return peerHost;
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return peerHost;
        }
        String[] parts = xff.split(",");
        String rightmost = parts[parts.length - 1].trim();
        // spec §3: "malformed entry ⇒ the peer" — TrustedProxies.isIpLiteral is a
        // syntactic-only check (never DNS), shared with its own isTrusted(String).
        return !rightmost.isEmpty() && TrustedProxies.isIpLiteral(rightmost) ? rightmost : peerHost;
    }

    // ── shared pipeline: endpoint match → CORS preflight → body cap → auth → invoke ──

    /// Spec §2 steps 4-10, shared by BOTH the private (unversioned) and
    /// public entries once each has resolved its own [DesiredDocument.Entry]
    /// and function-path: a genuine CORS preflight (spec §4) is answered
    /// here, by the host, BEFORE the ordinary method-based endpoint match —
    /// a preflight's method is `OPTIONS`, which an endpoint's own `methods`
    /// list would otherwise 405.
    ///
    /// @param versioned `true` only for a public alias-prefixed call (spec
    ///                  `function-zones-and-aliases.md` §4) — selects the
    ///                  VERSIONED load mechanism ([PinnedVersions], the same
    ///                  one a pinned `address:version` call and a candidate
    ///                  already use) in [#invoke] below; it does NOT change
    ///                  which auth applies — the endpoint's own `auth` still
    ///                  runs, unlike the private versioned path.
    private void handleEntry(HttpServerRequest req, io.vertx.core.Context requestContext, DesiredDocument.Entry entry,
                              String functionPath, InvocationObserver.Entry entryKind, String remoteAddressOverride,
                              boolean versioned) {
        if (CorsPolicy.isPreflight(req.method().name(), req.headers())) {
            Optional<Manifest.Endpoint> byPath = matchEndpointByPathOnly(entry.manifest(), functionPath);
            if (byPath.isPresent() && byPath.get().cors() != null) {
                HttpAnswer preflight = CorsPolicy.preflight(byPath.get(), req.headers());
                observer.refused("preflight", entry.address(), entryKind);
                answer(req, requestContext, preflight.status(), preflight.headers(), preflight.body());
                return;
            }
            // Not a CORS-declared endpoint at this path — spec §4's last bullet: falls
            // through to ordinary handling, which will very likely 405 on OPTIONS.
        }

        EndpointMatch match = matchEndpoint(entry.manifest(), functionPath, req.method().name());
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
        readBodyThenRun(req, requestContext, ok.endpoint().maxBodyBytes(),
                body -> continueAfterBody(req, entry, ok.endpoint(), functionPath, ok.pathParams(), body, entryKind,
                        remoteAddressOverride, versioned));
    }

    /// Spec §4: NOTHING about the entry, its manifest or its endpoints may be
    /// looked at on the event loop — only path parsing and a conservative,
    /// address-independent body cap happen here; everything else (auth,
    /// permission, entry/reach, endpoint match, the endpoint's own body cap,
    /// permits, load, invoke) runs together on the virtual thread.
    private void handleVersioned(HttpServerRequest req, io.vertx.core.Context requestContext, RoutePath path) {
        readBodyThenRun(req, requestContext, VERSIONED_BODY_CAP_BYTES, body -> continueVersionedAfterBody(req, path, body));
    }

    @FunctionalInterface
    private interface BodyContinuation {
        HttpAnswer run(byte[] body);
    }

    /// Buffers the request body under `cap`, then runs `continuation` on a
    /// fresh virtual thread and writes whatever it returns back on the
    /// request's own Vert.x context (spec §2 step 5's body cap, shared by
    /// both the unversioned and versioned paths — only what `cap` means, and
    /// what runs after, differs between them). `Content-Length` over `cap` is
    /// rejected BEFORE any body byte is read; a chunked body is cut off the
    /// moment it crosses `cap` (spec §2 step 5, H9).
    private void readBodyThenRun(HttpServerRequest req, io.vertx.core.Context requestContext, long cap,
                                  BodyContinuation continuation) {
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
                    result = continuation.run(body);
                } catch (RuntimeException e) {
                    // A safety net around auth/permits/load — the ONLY exceptions expected past this
                    // point are the invocation's own (already handled inside the continuation); an
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

    // ── shared: auth (endpoint's own) → invoke (virtual thread) → CORS on the actual response ──

    /// Spec §3's own auth step for BOTH the private unversioned entry and
    /// the public entry (identical rules — the endpoint's `auth` decides,
    /// regardless of which listener the call arrived on), followed by the
    /// CORS §4 "actual request" rule applied to whatever the function (or a
    /// host-generated error) answered — the host is the sole authority for
    /// any endpoint that declares `cors`, on both listeners.
    private HttpAnswer continueAfterBody(HttpServerRequest req, DesiredDocument.Entry entry,
                                          Manifest.Endpoint endpoint, String functionPath,
                                          Map<String, String> pathParams, byte[] body,
                                          InvocationObserver.Entry entryKind, String remoteAddressOverride,
                                          boolean versioned) {
        AuthResult authResult = authenticateUnversioned(endpoint, req.headers(), body, entry);
        if (authResult instanceof AuthResult.Failed(HttpAnswer failure)) {
            observer.refused("unauthorized", entry.address(), entryKind);
            return CorsPolicy.applyToActualResponse(endpoint, req.getHeader("Origin"), failure);
        }
        Caller caller = ((AuthResult.Ok) authResult).caller();
        boolean stripAuthHeaders = endpoint.auth() != EndpointAuth.NONE;
        HttpAnswer answer = invoke(req, entry, endpoint, functionPath, pathParams, body, caller, stripAuthHeaders,
                versioned, entryKind, remoteAddressOverride);
        return CorsPolicy.applyToActualResponse(endpoint, req.getHeader("Origin"), answer);
    }

    // ── versioned: token → permission → entry/reach → endpoint match → own body cap → invoke ──

    /// Spec §4, in the required order, ALL on the virtual thread: a platform
    /// bearer token (401) → `platform:function:version:invoke` (403) →
    /// entry-exists-and-reach, indistinguishable (404 `VERSION_NOT_AVAILABLE`)
    /// → endpoint match (404/405, now safe — the caller is already proven
    /// authorized to know) → the endpoint's own `maxBodyBytes` (413) →
    /// permits → load → invoke. The endpoint's own `auth` is never applied
    /// (spec §4: "not applied" — a `webhook` endpoint is reachable versioned
    /// without a signature).
    private HttpAnswer continueVersionedAfterBody(HttpServerRequest req, RoutePath path, byte[] body) {
        BearerAuthenticator.Outcome auth = bearerAuthenticator.authenticate(req.getHeader("Authorization"));
        if (auth instanceof BearerAuthenticator.Rejected(String reason)) {
            // address = null: spec §4's own anti-leak requirement — an unauthenticated
            // caller must not be able to tell a version exists by the shape of the
            // failure, so the metrics label must not either.
            observer.refused("unauthorized", null, InvocationObserver.Entry.PRIVATE);
            return HttpAnswer.of(401, Map.of("WWW-Authenticate", List.of("Bearer")), "UNAUTHORIZED", reason);
        }
        TokenClaims claims = ((BearerAuthenticator.Authenticated) auth).claims();
        if (!Permission.grants(claims.permissions(), Permission.FUNCTION_VERSION_INVOKE.code())) {
            observer.refused("unauthorized", null, InvocationObserver.Entry.PRIVATE);
            return HttpAnswer.of(403, Map.of(), "PERMISSION_REQUIRED", "platform:function:version:invoke required");
        }

        DesiredDocument.Entry entry = reconciler.entryFor(path.address(), path.version());
        if (entry == null || !hasReach(claims, entry)) {
            // Spec §4: reach failure (or no such version) is 404, not 403 — "same rule as
            // the platform API" — and indistinguishable from each other. address = null
            // for the same reason.
            observer.refused("not_found", null, InvocationObserver.Entry.PRIVATE);
            return HttpAnswer.of(404, Map.of(), "VERSION_NOT_AVAILABLE", "no such version");
        }

        EndpointMatch match = matchEndpoint(entry.manifest(), path.functionPath(), req.method().name());
        if (match instanceof EndpointMatch.NotFound) {
            return HttpAnswer.of(404, Map.of(), "ENDPOINT_NOT_FOUND", "no endpoint matches this path");
        }
        if (match instanceof EndpointMatch.MethodNotAllowed(List<String> allowed)) {
            return HttpAnswer.of(405, Map.of("Allow", List.of(String.join(", ", allowed))),
                    "METHOD_NOT_ALLOWED", "method not allowed on this endpoint");
        }
        EndpointMatch.Ok ok = (EndpointMatch.Ok) match;
        if (body.length > ok.endpoint().maxBodyBytes()) {
            return HttpAnswer.of(413, Map.of(), "BODY_TOO_LARGE", "request body exceeds the endpoint's limit");
        }

        Caller.Principal caller = principalFrom(claims);
        return invoke(req, entry, ok.endpoint(), path.functionPath(), ok.pathParams(), body, caller, true, true,
                InvocationObserver.Entry.PRIVATE, null);
    }

    private static boolean hasReach(TokenClaims claims, DesiredDocument.Entry entry) {
        boolean anchor = "ANCHOR".equalsIgnoreCase(claims.tier());
        if (anchor) {
            return true;
        }
        if (entry.clientId() == null) {
            return false; // a platform-owned function needs anchor
        }
        // The claims carry "{id}:{label}" pairs (or the "*" sentinel), never bare ids —
        // comparing them raw denied every non-anchor caller. ScopeClaim is the one parser.
        ScopeClaim.Parsed clients = ScopeClaim.parse(claims.clients());
        if (!clients.wildcard() && !clients.ids().contains(entry.clientId())) {
            return false;
        }
        if (entry.applicationId() == null) {
            return true;
        }
        ScopeClaim.Parsed applications = ScopeClaim.parse(claims.applications());
        return claims.allApplications() || applications.wildcard() || applications.ids().contains(entry.applicationId());
    }

    // ── endpoint match (spec §2 step 4) ─────────────────────────────────────

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
        List<HttpMethod> effective = endpoint.auth() == EndpointAuth.WEBHOOK
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

    /// spec §4's own need: whether SOME endpoint owns `functionPath` at all
    /// — ignoring method entirely — so a genuine CORS preflight (always
    /// `OPTIONS`, essentially never itself a configured method) can find the
    /// endpoint whose `cors` policy applies before the ordinary method-based
    /// match would 405 it.
    private static Optional<Manifest.Endpoint> matchEndpointByPathOnly(Manifest manifest, String functionPath) {
        List<Manifest.Endpoint> endpoints = manifest.endpoints();
        List<RoutePattern> patterns = endpoints.stream().map(Manifest.Endpoint::path).toList();
        var match = RoutePattern.firstMatch(patterns, functionPath);
        if (match.isEmpty()) {
            return Optional.empty();
        }
        return endpoints.stream().filter(e -> e.path().equals(match.get().pattern())).findFirst();
    }

    // ── permits, load, invoke, respond (spec §2 steps 7-10; already on a virtual thread) ──

    private HttpAnswer invoke(HttpServerRequest req, DesiredDocument.Entry entry, Manifest.Endpoint endpoint,
                               String functionPath, Map<String, String> pathParams, byte[] body, Caller caller,
                               boolean stripAuthHeaders, boolean versioned, InvocationObserver.Entry entryKind,
                               String remoteAddressOverride) {
        // Step 7: permits.
        int maxConcurrency = entry.manifest().limits().maxConcurrency();
        Permits.Grant grant = permits.tryAcquire(entry.address(), maxConcurrency);
        if (!grant.granted()) {
            observer.refused("busy", entry.address(), entryKind);
            return HttpAnswer.of(429, Map.of("Retry-After", List.of("1")), "BUSY", "the function is at capacity");
        }

        try {
            // Step 8: load.
            LoadedFunction fn = versioned ? pinnedVersions.getOrLoad(entry) : reconciler.ensureLoaded(entry.address());
            if (fn == null) {
                permits.release(grant);
                if (versioned) {
                    // Past the token, permission and reach checks, so saying the version exists
                    // leaks nothing. Still preparing: 503 with Retry-After (owner ruling
                    // 2026-09-25, item 12); refused for good: 404. address = null, as above.
                    if (reconciler.isPreparing(entry)) {
                        observer.refused("unavailable", null, entryKind);
                        return HttpAnswer.of(503, Map.of("Retry-After", List.of("5")), "VERSION_NOT_READY",
                                "the version is still being prepared");
                    }
                    observer.refused("not_found", null, entryKind);
                    return HttpAnswer.of(404, Map.of(), "VERSION_NOT_AVAILABLE", "version is not loadable");
                }
                observer.refused("unavailable", entry.address(), entryKind);
                return HttpAnswer.of(503, Map.of("Retry-After", List.of("15")), "FUNCTION_UNAVAILABLE",
                        "the function could not be loaded");
            }

            // Step 9: invoke.
            String invocationId = Tsid.generate();
            io.flowcatalyst.function.FunctionAddress apiAddress = toApiAddress(entry.address());
            String remoteAddress = remoteAddressOverride != null ? remoteAddressOverride
                    : (req.remoteAddress() == null ? null : req.remoteAddress().host());
            Request request = buildRequest(apiAddress, fn.version(), invocationId, req, functionPath,
                    pathParams, body, stripAuthHeaders, caller, remoteAddress);
            // D4b: the version's OWN context, built once at load time (Reconciler) and
            // attached to it — the same instance for every call, never a fresh throwaway
            // per request (docs/spec/function-context.md §2).
            io.flowcatalyst.function.FunctionContext ctx = fn.context();

            // MDC (spec §2 step 9): set on THIS thread too, independently of the worker
            // thread InvocationRunner spawns (which sets its own copy — D4b/X6 moved the
            // authoritative set there, since Logback's MDC does not reliably reach a
            // function's own log lines otherwise) — so the HOST's own log lines below
            // (timeout/error) still carry the keys while the call is in flight. Cleared in
            // `finally` regardless of outcome; nothing after this method returns carries them.
            Map<String, String> mdc = new LinkedHashMap<>();
            mdc.put(Logging.MdcKeys.FUNCTION, entry.address().render());
            mdc.put(Logging.MdcKeys.VERSION, String.valueOf(fn.version()));
            mdc.put(Logging.MdcKeys.EXECUTION_ID, invocationId);
            String correlationId = req.getHeader("X-Correlation-Id");
            if (correlationId != null) {
                mdc.put(Logging.MdcKeys.CORRELATION_ID, correlationId);
            }
            mdc.forEach(MDC::put);
            observer.entered(entry.address());
            FunctionInvocationEvent event = new FunctionInvocationEvent();
            event.begin();
            long startNanos = System.nanoTime();
            try {
                long timeoutMs = endpoint.timeoutMs();
                java.time.Instant deadline = clock.instant().plusMillis(timeoutMs);
                InvocationRunner.Invocation invocation = InvocationRunner.start(fn, request, ctx, deadline);
                try {
                    Result result = invocation.future().get(timeoutMs, TimeUnit.MILLISECONDS);
                    permits.release(grant);
                    observer.exited(entry.address());
                    if (result == null) {
                        LOG.atWarn().setMessage("function returned a null Result")
                                .addKeyValue("address", entry.address().render())
                                .addKeyValue("invocation_id", invocationId)
                                .log();
                        recordOutcome(event, entry, fn, invocationId, startNanos, 500, "error", entryKind);
                        return HttpAnswer.of(500, Map.of(), "FUNCTION_ERROR", "the function failed");
                    }
                    HttpAnswer answer = toAnswer(result);
                    recordOutcome(event, entry, fn, invocationId, startNanos, answer.status(), outcomeFor(answer.status()), entryKind);
                    return answer;
                } catch (TimeoutException e) {
                    invocation.worker().interrupt();
                    // The permit is released only once the worker actually finishes (H7): a
                    // function that swallows the interrupt keeps it until it returns —
                    // fc_fn_active (D5) mirrors the permit gauge for the same reason.
                    invocation.future().whenComplete((r, ex) -> {
                        permits.release(grant);
                        observer.exited(entry.address());
                    });
                    recordOutcome(event, entry, fn, invocationId, startNanos, 504, "timeout", entryKind);
                    return HttpAnswer.of(504, Map.of(), "FUNCTION_TIMEOUT", "the invocation exceeded its deadline");
                } catch (ExecutionException e) {
                    permits.release(grant);
                    observer.exited(entry.address());
                    LOG.atWarn().setMessage("function invocation threw")
                            .addKeyValue("address", entry.address().render())
                            .addKeyValue("invocation_id", invocationId)
                            .setCause(e.getCause())
                            .log();
                    recordOutcome(event, entry, fn, invocationId, startNanos, 500, "error", entryKind);
                    return HttpAnswer.of(500, Map.of(), "FUNCTION_ERROR", "the function failed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    permits.release(grant);
                    observer.exited(entry.address());
                    recordOutcome(event, entry, fn, invocationId, startNanos, 500, "error", entryKind);
                    return HttpAnswer.of(500, Map.of(), "FUNCTION_ERROR", "interrupted");
                }
            } finally {
                mdc.keySet().forEach(MDC::remove);
            }
        } catch (RuntimeException e) {
            permits.release(grant);
            throw e;
        }
    }

    /// D5: the one place that turns an entered invocation's outcome into
    /// both [InvocationObserver#completed] and the committed
    /// [FunctionInvocationEvent] — every return path through [#invoke] above
    /// calls this exactly once, right before returning.
    private void recordOutcome(FunctionInvocationEvent event, DesiredDocument.Entry entry, LoadedFunction fn,
                                String invocationId, long startNanos, int status, String outcome,
                                InvocationObserver.Entry entryKind) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        observer.completed(entry.address(), fn.version(), outcome, elapsed, entryKind);
        if (event.shouldCommit()) {
            event.address = entry.address().render();
            event.version = fn.version();
            event.invocationId = invocationId;
            event.status = status;
            event.outcome = outcome;
            event.commit();
        }
    }

    /// Spec §2's outcome table, restricted to what an ENTERED invocation can
    /// reach: `ok` (2xx), `retry` (429 — distinct from a 4xx `client_error`),
    /// `client_error` (any other 4xx), `error` (5xx, and any other status
    /// the function's own [Result#http] could technically produce — 1xx/3xx
    /// are not part of the dispatch contract at all, so they are bucketed
    /// here rather than left unclassified).
    static String outcomeFor(int status) {
        // 1xx–3xx: the function answered and did not report a problem — a redirect
        // from a function is not an error (it used to fall through to "error").
        if (status < 400) {
            return "ok";
        }
        if (status == 429) {
            return "retry";
        }
        if (status >= 400 && status < 500) {
            return "client_error";
        }
        return "error";
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

    /// Maps every claim the verified token carries onto [Caller.Principal]
    /// (`docs/spec/function-caller-claims.md` §3) — `email`/`name` excepted,
    /// deliberately: a function has no business with them. `clients`,
    /// `roles` and `applications` are passed through verbatim (a token's
    /// `clients` claim is a LIST — an anchor carries `["*"]`, a partner
    /// principal may carry several real ids — so [Caller.Principal#clientId]
    /// derives the single unambiguous one, rather than this method
    /// collapsing the list itself).
    ///
    /// `clients` and `applications` arrive as `"{id}:{label}"` pairs (or `"*"`);
    /// the function sees bare ids, exactly as the platform's own
    /// `Authenticator` builds an `AuthContext` — `canAccessClient(id)` and
    /// `clientId()` are written against ids, and the pair form must not leak
    /// past this boundary ([ScopeClaim]).
    private static Caller.Principal principalFrom(TokenClaims claims) {
        String type = claims.principalType() == null ? "unknown" : claims.principalType();
        ScopeClaim.Parsed clients = ScopeClaim.parse(claims.clients());
        ScopeClaim.Parsed applications = ScopeClaim.parse(claims.applications());
        return new Caller.Principal(claims.subject(), type, claims.tier(),
                clients.wildcard() ? List.of(ScopeClaim.WILDCARD) : clients.ids(), claims.roles(),
                applications.ids(), claims.allApplications() || applications.wildcard(),
                Set.copyOf(claims.permissions()));
    }

    // ── building the Request value (spec §2) ────────────────────────────────

    private static io.flowcatalyst.function.FunctionAddress toApiAddress(FunctionAddress platformAddress) {
        return new io.flowcatalyst.function.FunctionAddress(
                platformAddress.application().value(), platformAddress.service().value(), platformAddress.name().value());
    }

    private static Request buildRequest(io.flowcatalyst.function.FunctionAddress apiAddress, int version,
                                         String invocationId, HttpServerRequest req, String functionPath,
                                         Map<String, String> pathParams, byte[] body, boolean stripAuthHeaders,
                                         Caller caller, String remoteAddress) {
        Map<String, List<String>> headers = collectHeaders(req.headers(), stripAuthHeaders);
        Map<String, List<String>> query = collectQuery(req);
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

    /// spec `function-public-routes.md` §3: `X-FlowCatalyst-Function` is an
    /// inbound, caller-controllable header (never something a legitimate
    /// caller needs to send — it names the internal routing outcome) and is
    /// therefore ALWAYS stripped, on both listeners, regardless of the
    /// endpoint's `auth` — unlike [#CONSUMED_AUTH_HEADERS], which are only
    /// stripped when the host itself consumed them for authentication.
    private static final String INBOUND_ROUTING_HEADER = "x-flowcatalyst-function";

    private static Map<String, List<String>> collectHeaders(MultiMap headers, boolean stripAuthHeaders) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : headers.names()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals(INBOUND_ROUTING_HEADER)) {
                continue;
            }
            if (stripAuthHeaders && CONSUMED_AUTH_HEADERS.contains(lower)) {
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

    // Package-visible (not private): CorsPolicy, in this same package, builds/reads these too.
    record HttpAnswer(int status, Map<String, List<String>> headers, byte[] body) {
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
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.atWarn().setMessage("closing the function host listener did not complete cleanly within the drain timeout").setCause(e).log();
        }
        if (publicHttpServer != null) {
            try {
                publicHttpServer.close().toCompletionStage().toCompletableFuture()
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.atWarn().setMessage("closing the function host public listener did not complete cleanly within the drain timeout").setCause(e).log();
            }
        }
        pinnedVersions.close();
        try {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.atWarn().setMessage("closing Vert.x did not complete cleanly").setCause(e).log();
        }
    }
}
