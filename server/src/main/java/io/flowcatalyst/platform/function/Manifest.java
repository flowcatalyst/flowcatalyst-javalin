package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// A function version's manifest (spec `function-invocation.md` §3, amending
/// `function-registry.md` §4) — the declared shape of a deployable version:
/// its entrypoint, resource limits, HTTP surface, platform-managed wiring
/// and outbound allow-list.
///
/// **A function is always invoked over HTTP** (spec §1): there is no
/// separate "event invocation" type. [#endpoints] declares the HTTP surface
/// and how the host authenticates each path before the function sees it;
/// [#subscriptions], [#schedules] and [#publicRoutes] declare what the
/// platform wires *to* an endpoint at promote (spec §4) — a subscription and
/// a schedule both need a `webhook`-authed endpoint to deliver to.
///
/// There are two readers (spec §4.2 of `function-registry.md`, unchanged by
/// this amendment):
/// - [#parseStrict] — the publish reader. Rejects unknown keys at every
///   level and returns a manifest with every applicable limit filled in.
/// - [#readStored] — the repository's reader for `fn_versions.manifest`, a
///   foreign JSON shape (`CONVENTIONS.md` §8): tolerant of unknown keys and
///   missing optionals, never throws except when `runtime` or `entrypoint`
///   is unreadable.
///
/// Both read a Jackson tree through [Json#MAPPER]; [#toJson] writes the same
/// shape back so `readStored(parseStrict(x).toJson())` round-trips to an
/// equal record.
///
/// @param runtime      the declared runtime, must match the function's own (`RUNTIME_MISMATCH`)
/// @param entrypoint   the class name (JVM) or export name (Wasm)
/// @param pool         the execution pool; `default` when absent (`POOL_INVALID`)
/// @param warm         whether the function should be kept warm; `false` when absent
/// @param limits       the resolved resource limits — always fully populated
/// @param endpoints    the function's HTTP surface (spec §3)
/// @param subscriptions event-type subscriptions the platform creates at promote (spec §3, §4)
/// @param schedules    cron schedules the platform creates at promote (spec §3, §4)
/// @param publicRoutes  `(hostname, pathPrefix)` pairs exposed on the public listener (wire key `public`)
/// @param config       required config variable names
/// @param secrets      required secret references
/// @param db           database connections this version needs
/// @param httpAllow    outbound hosts this version may call
public record Manifest(Runtime runtime, String entrypoint, DnsLabel pool, boolean warm, Limits limits,
                        List<Endpoint> endpoints, List<SubscriptionSpec> subscriptions,
                        List<ScheduleSpec> schedules, List<PublicRoute> publicRoutes,
                        List<String> config, List<String> secrets, List<DbRef> db,
                        List<String> httpAllow) {

    /// The pool a manifest gets when it does not name one (spec §4.3 `POOL_INVALID`).
    public static final DnsLabel DEFAULT_POOL = new DnsLabel("default");

    /// A subscription/schedule entry's `mode` when absent (spec §3) —
    /// **not** [DispatchMode#DEFAULT]: a function manifest is explicit, so
    /// the ordinary router default (`NEXT_ON_ERROR`, safe-by-default for an
    /// unannounced integration) is not the right absence value here; the
    /// manifest author who wants ordering asks for it.
    public static final DispatchMode DEFAULT_SUBSCRIPTION_MODE = DispatchMode.IMMEDIATE;

    /// A subscription entry's `dataOnly` when absent (spec §3) — **not**
    /// [Subscription#DEFAULT_DATA_ONLY]: a function should see the whole
    /// envelope by default.
    public static final boolean DEFAULT_SUBSCRIPTION_DATA_ONLY = false;

    private static final Pattern JVM_ENTRYPOINT = Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*$");
    private static final Pattern WASM_ENTRYPOINT = Pattern.compile("^[A-Za-z_]\\w*$");

    private static final Set<String> TOP_KEYS = Set.of(
            "runtime", "entrypoint", "pool", "warm", "limits", "endpoints", "subscriptions", "schedules", "public",
            "config", "secrets", "db", "httpAllow");
    private static final Set<String> LIMITS_KEYS = Set.of("maxDurationMs", "maxConcurrency", "wasmMemoryMb");
    private static final Set<String> ENDPOINT_KEYS =
            Set.of("path", "auth", "methods", "cors", "maxBodyBytes", "timeoutMs");
    private static final Set<String> SUBSCRIPTION_KEYS =
            Set.of("eventType", "path", "mode", "maxRetries", "timeoutSeconds", "dataOnly");
    private static final Set<String> SCHEDULE_KEYS = Set.of("cron", "timezone", "path", "payload");
    private static final Set<String> PUBLIC_ROUTE_KEYS = Set.of("hostname", "pathPrefix");
    private static final Set<String> CORS_KEYS = Set.of("origins", "methods", "headers", "allowCredentials");
    private static final Set<String> DB_KEYS = Set.of("name", "secretRef", "poolSize");

    public Manifest {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(limits, "limits");
        endpoints = List.copyOf(endpoints);
        subscriptions = List.copyOf(subscriptions);
        schedules = List.copyOf(schedules);
        publicRoutes = List.copyOf(publicRoutes);
        config = List.copyOf(config);
        secrets = List.copyOf(secrets);
        db = List.copyOf(db);
        httpAllow = List.copyOf(httpAllow);
    }

    /// The resolved resource limits of one version (spec `function-registry.md`
    /// §4.6), frozen at publish: `wasmMemoryMb` is `null` for a JVM function
    /// (`LIMIT_NOT_APPLICABLE` forbids specifying it) and always resolved for
    /// a Wasm one.
    public record Limits(int maxDurationMs, int maxConcurrency, Integer wasmMemoryMb) {
        public Limits {
            if (maxDurationMs <= 0) throw new IllegalArgumentException("maxDurationMs must be > 0");
            if (maxConcurrency <= 0) throw new IllegalArgumentException("maxConcurrency must be > 0");
            if (wasmMemoryMb != null && wasmMemoryMb <= 0) {
                throw new IllegalArgumentException("wasmMemoryMb must be > 0");
            }
        }
    }

    /// One entry of the function's HTTP surface (spec §3): `path` is a
    /// [RoutePattern]; `auth` is how the host authenticates a call at this
    /// path before the function sees it — required, no default
    /// (`ENDPOINT_AUTH_REQUIRED`). `methods` absent/empty means "all"; a
    /// `webhook`-authed endpoint's `methods`, if given, must be exactly
    /// `["POST"]` (spec §3).
    ///
    /// @param path         the route pattern (spec `function-registry.md` §5.2)
    /// @param auth         how the host authenticates the call — required
    /// @param methods      accepted methods; empty means every method
    /// @param cors         optional CORS policy; `null` when absent
    /// @param maxBodyBytes resolved; 1 MiB ([#DEFAULT_MAX_BODY_BYTES]) when absent
    /// @param timeoutMs    resolved; the function's `maxDurationMs` when absent
    public record Endpoint(RoutePattern path, EndpointAuth auth, List<HttpMethod> methods, Cors cors,
                            int maxBodyBytes, int timeoutMs) {

        /// The default `maxBodyBytes` when an endpoint does not name one: 1 MiB.
        public static final int DEFAULT_MAX_BODY_BYTES = 1_048_576;

        public Endpoint {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(auth, "auth");
            methods = List.copyOf(methods);
            if (maxBodyBytes <= 0) throw new IllegalArgumentException("maxBodyBytes must be > 0");
            if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be > 0");
        }
    }

    /// An event-type subscription the platform creates at promote (spec §3,
    /// §4): `path` must be a literal path (no `{param}`/`*`) matching an
    /// endpoint whose `auth` is `webhook` (`SUBSCRIPTION_PATH_NOT_WEBHOOK`).
    /// One entry per `eventType` (`SUBSCRIPTION_DUPLICATE`).
    ///
    /// @param eventType     the pattern, required
    /// @param path          the literal delivery path; must resolve to a `webhook` endpoint
    /// @param mode          router ordering mode; [Manifest#DEFAULT_SUBSCRIPTION_MODE] when absent
    /// @param maxRetries    resolved; [Subscription#DEFAULT_MAX_RETRIES] when absent
    /// @param timeoutSeconds resolved; [Subscription#DEFAULT_TIMEOUT_SECONDS] when absent
    /// @param dataOnly      [Manifest#DEFAULT_SUBSCRIPTION_DATA_ONLY] (`false`) when absent
    public record SubscriptionSpec(String eventType, RoutePattern path, DispatchMode mode,
                                    int maxRetries, int timeoutSeconds, boolean dataOnly) {
        public SubscriptionSpec {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /// A cron schedule the platform creates at promote (spec §3, §4): `path`
    /// carries the same literal-path/`webhook`-endpoint rule as
    /// [SubscriptionSpec] (`SCHEDULE_PATH_NOT_WEBHOOK`). One entry per
    /// `(cron, timezone)` (`SCHEDULE_DUPLICATE`).
    ///
    /// @param cron     the cron expression, required
    /// @param timezone optional IANA timezone name; `null` when absent
    /// @param path     the literal delivery path; must resolve to a `webhook` endpoint
    /// @param payload  opaque JSON delivered with each firing; `null` = none
    public record ScheduleSpec(String cron, String timezone, RoutePattern path, JsonNode payload) {
        public ScheduleSpec {
            Objects.requireNonNull(cron, "cron");
            Objects.requireNonNull(path, "path");
        }
    }

    /// A `(hostname, pathPrefix)` pair the platform exposes on the public
    /// listener for this function (spec §3, §5). The prefix is a literal
    /// path, `/` when absent, stripped before endpoint matching.
    public record PublicRoute(Hostname hostname, RoutePattern pathPrefix) {

        /// `pathPrefix` when a public route entry does not name one.
        public static final RoutePattern DEFAULT_PATH_PREFIX = RoutePattern.parse("/");

        public PublicRoute {
            Objects.requireNonNull(hostname, "hostname");
            Objects.requireNonNull(pathPrefix, "pathPrefix");
        }
    }

    /// A route's CORS policy; every component is optional and defaults empty/`false`.
    public record Cors(List<String> origins, List<String> methods, List<String> headers, boolean allowCredentials) {
        public Cors {
            origins = List.copyOf(origins);
            methods = List.copyOf(methods);
            headers = List.copyOf(headers);
        }
    }

    /// A database connection this version needs (spec `function-registry.md` §4.3 `DB_INVALID`).
    ///
    /// @param name      the connection's local name, unique within the manifest
    /// @param secretRef the secret holding the DSN, required
    /// @param poolSize  resolved against the client's `dbPoolSize` ceiling
    public record DbRef(DnsLabel name, String secretRef, int poolSize) {
        public DbRef {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(secretRef, "secretRef");
            if (poolSize <= 0) throw new IllegalArgumentException("poolSize must be > 0");
        }
    }

    // ── parseStrict — the publish reader ─────────────────────────────────────

    /// The publish reader: validates every rule below (first failure wins,
    /// in the order the fields are read) and rejects unknown keys at every
    /// level.
    ///
    /// @throws UseCaseException validation, one of the codes documented on this class
    public static Manifest parseStrict(JsonNode root, Runtime functionRuntime, FunctionLimits defaults,
                                        ClientCeilings ceilings) {
        Objects.requireNonNull(functionRuntime, "functionRuntime");
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(ceilings, "ceilings");
        if (root == null || !root.isObject()) {
            throw UseCaseException.validation("MANIFEST_REQUIRED", "manifest is required and must be an object");
        }
        rejectUnknown(root, TOP_KEYS, "");

        Runtime runtime = parseRuntimeField(root, functionRuntime);
        String entrypoint = parseEntrypointField(root, runtime);
        DnsLabel pool = parsePoolField(root);
        boolean warm = parseWarmField(root);
        Limits limits = parseLimitsField(root, runtime, defaults, ceilings);
        List<Endpoint> endpoints = parseEndpointsField(root, limits, ceilings);
        List<SubscriptionSpec> subscriptions = parseSubscriptionsField(root, endpoints);
        List<ScheduleSpec> schedules = parseSchedulesField(root, endpoints);
        List<PublicRoute> publicRoutes = parsePublicField(root);
        List<DbRef> db = parseDbField(root, defaults, ceilings);
        List<String> config = parseSimpleStringList(root, "config");
        List<String> secrets = parseSimpleStringList(root, "secrets");
        List<String> httpAllow = parseSimpleStringList(root, "httpAllow");

        return new Manifest(runtime, entrypoint, pool, warm, limits, endpoints, subscriptions, schedules,
                publicRoutes, config, secrets, db, httpAllow);
    }

    private static Runtime parseRuntimeField(JsonNode root, Runtime functionRuntime) {
        Runtime runtime = Runtime.parseStrict(root.path("runtime").asString());
        if (runtime != functionRuntime) {
            throw UseCaseException.validation("RUNTIME_MISMATCH",
                    "manifest runtime '" + runtime.wireValue() + "' does not match the function's runtime '"
                            + functionRuntime.wireValue() + "'");
        }
        return runtime;
    }

    private static String parseEntrypointField(JsonNode root, Runtime runtime) {
        String raw = root.path("entrypoint").asString();
        if (raw.isBlank()) {
            throw UseCaseException.validation("ENTRYPOINT_REQUIRED", "entrypoint is required");
        }
        Pattern pattern = runtime == Runtime.JVM ? JVM_ENTRYPOINT : WASM_ENTRYPOINT;
        if (!pattern.matcher(raw).matches()) {
            throw UseCaseException.validation("ENTRYPOINT_INVALID", runtime == Runtime.JVM
                    ? "entrypoint must be a binary class name" : "entrypoint must be a wasm export name");
        }
        return raw;
    }

    private static DnsLabel parsePoolField(JsonNode root) {
        JsonNode node = root.path("pool");
        if (node.isMissingNode() || node.isNull()) return DEFAULT_POOL;
        if (!node.isString() || !DnsLabel.isValid(node.asString())) {
            throw UseCaseException.validation("POOL_INVALID", "pool must be a DNS label");
        }
        return new DnsLabel(node.asString());
    }

    private static boolean parseWarmField(JsonNode root) {
        JsonNode node = root.path("warm");
        if (node.isMissingNode() || node.isNull()) return false;
        if (!node.isBoolean()) {
            throw UseCaseException.validation("MANIFEST_INVALID", "warm must be a boolean");
        }
        return node.asBoolean();
    }

    private static Limits parseLimitsField(JsonNode root, Runtime runtime, FunctionLimits defaults,
                                            ClientCeilings ceilings) {
        JsonNode node = root.path("limits");
        boolean present = !node.isMissingNode() && !node.isNull();
        if (present) {
            if (!node.isObject()) throw UseCaseException.validation("LIMIT_INVALID", "limits must be an object");
            rejectUnknown(node, LIMITS_KEYS, "limits");
        } else {
            node = Json.MAPPER.createObjectNode();
        }

        int maxDurationMs = resolveLimit(node, "maxDurationMs", defaults.maxDurationMs(), ceilings.maxDurationMs());
        int maxConcurrency =
                resolveLimit(node, "maxConcurrency", defaults.maxConcurrency(), ceilings.maxConcurrency());

        JsonNode wasmNode = node.path("wasmMemoryMb");
        boolean wasmPresent = !wasmNode.isMissingNode() && !wasmNode.isNull();
        Integer wasmMemoryMb;
        if (runtime == Runtime.JVM) {
            if (wasmPresent) {
                throw UseCaseException.validation("LIMIT_NOT_APPLICABLE",
                        "wasmMemoryMb is not applicable to a jvm function");
            }
            wasmMemoryMb = null;
        } else {
            wasmMemoryMb = resolveLimit(node, "wasmMemoryMb", defaults.wasmMemoryMb(), ceilings.wasmMemoryMb());
        }
        return new Limits(maxDurationMs, maxConcurrency, wasmMemoryMb);
    }

    /// True when `node` is an integral JSON number that fits a Java `int`
    /// (spec `function-registry.md` §4.3 `LIMIT_INVALID`: "fits an `int`" —
    /// a value like `5000000000` must be rejected, never silently truncated
    /// by `asInt()`). Shared by every integer field of the manifest.
    private static boolean fitsInt(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt();
    }

    /// Absent ⇒ `min(default, ceiling)`; present ⇒ must be a positive
    /// integer (`LIMIT_INVALID`) not exceeding the ceiling (`LIMIT_OVER_CEILING`).
    private static int resolveLimit(JsonNode limitsNode, String key, int defaultValue, int ceiling) {
        JsonNode node = limitsNode.path(key);
        if (node.isMissingNode() || node.isNull()) {
            return Math.min(defaultValue, ceiling);
        }
        if (!fitsInt(node) || node.asInt() <= 0) {
            throw UseCaseException.validation("LIMIT_INVALID", key + " must be a positive integer");
        }
        int value = node.asInt();
        if (value > ceiling) {
            throw UseCaseException.validation("LIMIT_OVER_CEILING",
                    key + " is " + value + ", which exceeds the ceiling of " + ceiling);
        }
        return value;
    }

    // ── endpoints ─────────────────────────────────────────────────────────

    private static List<Endpoint> parseEndpointsField(JsonNode root, Limits limits, ClientCeilings ceilings) {
        JsonNode node = root.path("endpoints");
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw UseCaseException.validation("ENDPOINT_INVALID", "endpoints must be an array");

        List<Endpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            endpoints.add(parseEndpoint(node.get(i), "endpoints[" + i + "]", limits, ceilings));
        }
        checkEndpointAmbiguity(endpoints);
        return List.copyOf(endpoints);
    }

    private static Endpoint parseEndpoint(JsonNode node, String path, Limits limits, ClientCeilings ceilings) {
        if (!node.isObject()) throw UseCaseException.validation("ENDPOINT_INVALID", path + " must be an object");
        rejectUnknown(node, ENDPOINT_KEYS, path);

        RoutePattern pattern = parseRoutePath(node, path);
        EndpointAuth auth = parseEndpointAuth(node, path);
        List<HttpMethod> methods = parseMethods(node, path);
        if (auth == EndpointAuth.WEBHOOK && !methods.isEmpty()
                && !(methods.size() == 1 && methods.get(0) == HttpMethod.POST)) {
            throw UseCaseException.validation("ENDPOINT_INVALID",
                    path + ": a webhook endpoint's methods, if given, must be exactly [\"POST\"]");
        }
        Cors cors = parseCors(node, path);
        int maxBodyBytes = parsePositiveOrDefault(node, "maxBodyBytes", path, Endpoint.DEFAULT_MAX_BODY_BYTES,
                "ENDPOINT_INVALID");
        int timeoutMs = parseTimeoutMs(node, path, limits, ceilings);

        return new Endpoint(pattern, auth, methods, cors, maxBodyBytes, timeoutMs);
    }

    private static RoutePattern parseRoutePath(JsonNode node, String path) {
        JsonNode pathNode = node.path("path");
        if (!pathNode.isString()) {
            throw UseCaseException.validation("ENDPOINT_INVALID", path + ".path is required");
        }
        try {
            return RoutePattern.parse(pathNode.asString());
        } catch (UseCaseException e) {
            throw UseCaseException.validation("ENDPOINT_INVALID", path + ".path: " + e.error().message());
        }
    }

    /// `auth` is required — an absent value is `ENDPOINT_AUTH_REQUIRED`,
    /// distinct from an unrecognised one (`ENDPOINT_INVALID`, thrown by
    /// [EndpointAuth#parseStrict]) — spec §3: "no default".
    private static EndpointAuth parseEndpointAuth(JsonNode node, String path) {
        JsonNode authNode = node.path("auth");
        if (authNode.isMissingNode() || authNode.isNull()) {
            throw UseCaseException.validation("ENDPOINT_AUTH_REQUIRED", path + ".auth is required");
        }
        if (!authNode.isString()) {
            throw UseCaseException.validation("ENDPOINT_INVALID", path + ".auth must be a string");
        }
        return EndpointAuth.parseStrict(authNode.asString());
    }

    /// `methods` absent/`null` ⇒ every method (spec §3); present ⇒ a
    /// non-empty array of distinct, recognised methods.
    private static List<HttpMethod> parseMethods(JsonNode node, String path) {
        JsonNode methodsNode = node.path("methods");
        if (methodsNode.isMissingNode() || methodsNode.isNull()) return List.of();
        if (!methodsNode.isArray() || methodsNode.isEmpty()) {
            throw UseCaseException.validation("ENDPOINT_INVALID",
                    path + ".methods, if given, must be a non-empty array");
        }
        List<HttpMethod> methods = new ArrayList<>();
        Set<HttpMethod> seen = new HashSet<>();
        for (JsonNode entry : methodsNode) {
            if (!entry.isString()) {
                throw UseCaseException.validation("ENDPOINT_INVALID", path + ".methods entries must be strings");
            }
            HttpMethod method;
            try {
                method = HttpMethod.parseStrict(entry.asString());
            } catch (RuntimeException e) {
                throw UseCaseException.validation("ENDPOINT_INVALID",
                        path + ".methods has an unrecognised entry '" + entry.asString() + "'");
            }
            if (!seen.add(method)) {
                throw UseCaseException.validation("ENDPOINT_INVALID",
                        path + ".methods has a duplicate entry '" + method.name() + "'");
            }
            methods.add(method);
        }
        return List.copyOf(methods);
    }

    private static Cors parseCors(JsonNode node, String path) {
        JsonNode corsNode = node.path("cors");
        if (corsNode.isMissingNode() || corsNode.isNull()) return null;
        if (!corsNode.isObject()) {
            throw UseCaseException.validation("ENDPOINT_INVALID", path + ".cors must be an object");
        }
        String corsPath = path + ".cors";
        rejectUnknown(corsNode, CORS_KEYS, corsPath);
        List<String> origins = parseStringList(corsNode, "origins", corsPath);
        List<String> methods = parseStringList(corsNode, "methods", corsPath);
        List<String> headers = parseStringList(corsNode, "headers", corsPath);
        JsonNode credNode = corsNode.path("allowCredentials");
        boolean allowCredentials;
        if (credNode.isMissingNode() || credNode.isNull()) {
            allowCredentials = false;
        } else if (credNode.isBoolean()) {
            allowCredentials = credNode.asBoolean();
        } else {
            throw UseCaseException.validation("ENDPOINT_INVALID", corsPath + ".allowCredentials must be a boolean");
        }
        return new Cors(origins, methods, headers, allowCredentials);
    }

    private static List<String> parseStringList(JsonNode node, String key, String path) {
        JsonNode listNode = node.path(key);
        if (listNode.isMissingNode() || listNode.isNull()) return List.of();
        if (!listNode.isArray()) {
            throw UseCaseException.validation("ENDPOINT_INVALID", path + "." + key + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode entry : listNode) {
            if (!entry.isString() || entry.asString().isBlank()) {
                throw UseCaseException.validation("ENDPOINT_INVALID",
                        path + "." + key + " entries must be non-blank strings");
            }
            values.add(entry.asString());
        }
        return List.copyOf(values);
    }

    private static int parsePositiveOrDefault(JsonNode node, String key, String path, int defaultValue,
                                               String code) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return defaultValue;
        if (!fitsInt(value) || value.asInt() <= 0) {
            throw UseCaseException.validation(code, path + "." + key + " must be a positive integer");
        }
        return value.asInt();
    }

    private static int parseTimeoutMs(JsonNode node, String path, Limits limits, ClientCeilings ceilings) {
        JsonNode value = node.path("timeoutMs");
        if (value.isMissingNode() || value.isNull()) return limits.maxDurationMs();
        if (!fitsInt(value) || value.asInt() <= 0) {
            throw UseCaseException.validation("ENDPOINT_INVALID", path + ".timeoutMs must be a positive integer");
        }
        int timeoutMs = value.asInt();
        if (timeoutMs > ceilings.maxDurationMs()) {
            throw UseCaseException.validation("LIMIT_OVER_CEILING",
                    "timeoutMs is " + timeoutMs + ", which exceeds the ceiling of " + ceilings.maxDurationMs());
        }
        return timeoutMs;
    }

    /// Two endpoints that share a method (absent/empty `methods` on either
    /// side means "every method", so it always shares) and whose patterns
    /// are ambiguous (spec `function-registry.md` §5.3) are `ROUTE_AMBIGUOUS`.
    private static void checkEndpointAmbiguity(List<Endpoint> endpoints) {
        for (int i = 0; i < endpoints.size(); i++) {
            for (int j = i + 1; j < endpoints.size(); j++) {
                Endpoint a = endpoints.get(i);
                Endpoint b = endpoints.get(j);
                if (shareMethod(a, b) && a.path().ambiguousWith(b.path())) {
                    throw UseCaseException.validation("ROUTE_AMBIGUOUS",
                            "endpoint '" + a.path().value() + "' and endpoint '" + b.path().value()
                                    + "' are ambiguous");
                }
            }
        }
    }

    private static boolean shareMethod(Endpoint a, Endpoint b) {
        if (a.methods().isEmpty() || b.methods().isEmpty()) return true;
        for (HttpMethod method : a.methods()) {
            if (b.methods().contains(method)) return true;
        }
        return false;
    }

    // ── subscriptions / schedules — the literal-path / webhook-endpoint rule ─

    /// A literal path (spec §3: "must ... be a literal path, not a
    /// pattern") — a [RoutePattern] with only [RoutePattern.Literal] segments.
    private static RoutePattern parseLiteralPath(JsonNode node, String key, String path, String code) {
        JsonNode pathNode = node.path(key);
        if (!pathNode.isString()) {
            throw UseCaseException.validation(code, path + "." + key + " is required");
        }
        RoutePattern pattern;
        try {
            pattern = RoutePattern.parse(pathNode.asString());
        } catch (UseCaseException e) {
            throw UseCaseException.validation(code, path + "." + key + ": " + e.error().message());
        }
        for (RoutePattern.Segment segment : pattern.segments()) {
            if (!(segment instanceof RoutePattern.Literal)) {
                throw UseCaseException.validation(code, path + "." + key + " must be a literal path, not a pattern");
            }
        }
        return pattern;
    }

    /// Spec §3: the entry's `path` must match an endpoint whose `auth` is
    /// `webhook`; when several endpoints match, the most specific
    /// ([RoutePattern#compareTo] order, the same order [RoutePattern#firstMatch]
    /// uses) decides which endpoint actually serves that path.
    private static void requireWebhookMatch(RoutePattern literalPath, List<Endpoint> endpoints, String path,
                                             String code) {
        Optional<Endpoint> winner = endpoints.stream()
                .filter(e -> e.path().match(literalPath.value()).isPresent())
                .min(Comparator.comparing(Endpoint::path));
        if (winner.isEmpty() || winner.get().auth() != EndpointAuth.WEBHOOK) {
            throw UseCaseException.validation(code,
                    path + ".path '" + literalPath.value() + "' does not match a webhook endpoint");
        }
    }

    private static List<SubscriptionSpec> parseSubscriptionsField(JsonNode root, List<Endpoint> endpoints) {
        JsonNode node = root.path("subscriptions");
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw UseCaseException.validation("SUBSCRIPTION_INVALID", "subscriptions must be an array");
        }
        List<SubscriptionSpec> specs = new ArrayList<>();
        Set<String> eventTypes = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            SubscriptionSpec spec = parseSubscriptionSpec(node.get(i), "subscriptions[" + i + "]", endpoints);
            if (!eventTypes.add(spec.eventType())) {
                throw UseCaseException.validation("SUBSCRIPTION_DUPLICATE",
                        "duplicate subscription for eventType '" + spec.eventType() + "'");
            }
            specs.add(spec);
        }
        return List.copyOf(specs);
    }

    private static SubscriptionSpec parseSubscriptionSpec(JsonNode node, String path, List<Endpoint> endpoints) {
        if (!node.isObject()) throw UseCaseException.validation("SUBSCRIPTION_INVALID", path + " must be an object");
        rejectUnknown(node, SUBSCRIPTION_KEYS, path);

        JsonNode eventTypeNode = node.path("eventType");
        if (!eventTypeNode.isString() || eventTypeNode.asString().isBlank()) {
            throw UseCaseException.validation("SUBSCRIPTION_INVALID", path + ".eventType is required");
        }
        String eventType = eventTypeNode.asString();

        RoutePattern subscriptionPath = parseLiteralPath(node, "path", path, "SUBSCRIPTION_INVALID");
        requireWebhookMatch(subscriptionPath, endpoints, path, "SUBSCRIPTION_PATH_NOT_WEBHOOK");

        DispatchMode mode = parseSubscriptionMode(node, path);
        int maxRetries = parsePositiveOrDefault(node, "maxRetries", path, Subscription.DEFAULT_MAX_RETRIES,
                "SUBSCRIPTION_INVALID");
        int timeoutSeconds = parsePositiveOrDefault(node, "timeoutSeconds", path,
                Subscription.DEFAULT_TIMEOUT_SECONDS, "SUBSCRIPTION_INVALID");
        boolean dataOnly = parseBooleanOrDefault(node, "dataOnly", path, DEFAULT_SUBSCRIPTION_DATA_ONLY,
                "SUBSCRIPTION_INVALID");

        return new SubscriptionSpec(eventType, subscriptionPath, mode, maxRetries, timeoutSeconds, dataOnly);
    }

    /// Absent ⇒ [Manifest#DEFAULT_SUBSCRIPTION_MODE] (`IMMEDIATE`); present ⇒
    /// [DispatchMode#parseStrict], wrapped as `SUBSCRIPTION_INVALID`.
    private static DispatchMode parseSubscriptionMode(JsonNode node, String path) {
        JsonNode modeNode = node.path("mode");
        if (modeNode.isMissingNode() || modeNode.isNull()) return DEFAULT_SUBSCRIPTION_MODE;
        if (!modeNode.isString()) {
            throw UseCaseException.validation("SUBSCRIPTION_INVALID", path + ".mode must be a string");
        }
        try {
            return DispatchMode.parseStrict(modeNode.asString());
        } catch (UseCaseException e) {
            throw UseCaseException.validation("SUBSCRIPTION_INVALID", path + ".mode: " + e.error().message());
        }
    }

    private static String optionalText(JsonNode node, String key, String path, String code) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) {
            throw UseCaseException.validation(code, path + "." + key + " must be a string");
        }
        return value.asString();
    }

    private static boolean parseBooleanOrDefault(JsonNode node, String key, String path, boolean defaultValue,
                                                  String code) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return defaultValue;
        if (!value.isBoolean()) {
            throw UseCaseException.validation(code, path + "." + key + " must be a boolean");
        }
        return value.asBoolean();
    }

    private static List<ScheduleSpec> parseSchedulesField(JsonNode root, List<Endpoint> endpoints) {
        JsonNode node = root.path("schedules");
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw UseCaseException.validation("SCHEDULE_INVALID", "schedules must be an array");

        List<ScheduleSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            ScheduleSpec spec = parseScheduleSpec(node.get(i), "schedules[" + i + "]", endpoints);
            String key = spec.cron() + " " + (spec.timezone() == null ? "" : spec.timezone());
            if (!seen.add(key)) {
                throw UseCaseException.validation("SCHEDULE_DUPLICATE",
                        "duplicate schedule for cron '" + spec.cron() + "' timezone '" + spec.timezone() + "'");
            }
            specs.add(spec);
        }
        return List.copyOf(specs);
    }

    private static ScheduleSpec parseScheduleSpec(JsonNode node, String path, List<Endpoint> endpoints) {
        if (!node.isObject()) throw UseCaseException.validation("SCHEDULE_INVALID", path + " must be an object");
        rejectUnknown(node, SCHEDULE_KEYS, path);

        JsonNode cronNode = node.path("cron");
        if (!cronNode.isString() || cronNode.asString().isBlank()) {
            throw UseCaseException.validation("SCHEDULE_INVALID", path + ".cron is required");
        }
        String cron = cronNode.asString();
        String timezone = optionalText(node, "timezone", path, "SCHEDULE_INVALID");

        RoutePattern schedulePath = parseLiteralPath(node, "path", path, "SCHEDULE_INVALID");
        requireWebhookMatch(schedulePath, endpoints, path, "SCHEDULE_PATH_NOT_WEBHOOK");

        JsonNode payloadNode = node.path("payload");
        JsonNode payload = (payloadNode.isMissingNode() || payloadNode.isNull()) ? null : payloadNode;

        return new ScheduleSpec(cron, timezone, schedulePath, payload);
    }

    // ── public routes ─────────────────────────────────────────────────────

    private static List<PublicRoute> parsePublicField(JsonNode root) {
        JsonNode node = root.path("public");
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw UseCaseException.validation("PUBLIC_ROUTE_INVALID", "public must be an array");

        List<PublicRoute> routes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            PublicRoute route = parsePublicRoute(node.get(i), "public[" + i + "]");
            String key = route.hostname().value() + " " + route.pathPrefix().value();
            if (!seen.add(key)) {
                throw UseCaseException.validation("PUBLIC_ROUTE_DUPLICATE",
                        "duplicate public route for '" + route.hostname().value() + route.pathPrefix().value()
                                + "'");
            }
            routes.add(route);
        }
        return List.copyOf(routes);
    }

    private static PublicRoute parsePublicRoute(JsonNode node, String path) {
        if (!node.isObject()) throw UseCaseException.validation("PUBLIC_ROUTE_INVALID", path + " must be an object");
        rejectUnknown(node, PUBLIC_ROUTE_KEYS, path);

        JsonNode hostnameNode = node.path("hostname");
        if (!hostnameNode.isString()) {
            throw UseCaseException.validation("PUBLIC_ROUTE_INVALID", path + ".hostname is required");
        }
        Hostname hostname;
        try {
            hostname = Hostname.parse(hostnameNode.asString());
        } catch (UseCaseException e) {
            throw UseCaseException.validation("PUBLIC_ROUTE_INVALID", path + ".hostname: " + e.error().message());
        }

        JsonNode prefixNode = node.path("pathPrefix");
        RoutePattern pathPrefix = (prefixNode.isMissingNode() || prefixNode.isNull())
                ? PublicRoute.DEFAULT_PATH_PREFIX
                : parseLiteralPath(node, "pathPrefix", path, "PUBLIC_ROUTE_INVALID");

        return new PublicRoute(hostname, pathPrefix);
    }

    // ── db / config / secrets / httpAllow ────────────────────────────────────

    private static List<DbRef> parseDbField(JsonNode root, FunctionLimits defaults, ClientCeilings ceilings) {
        JsonNode node = root.path("db");
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw UseCaseException.validation("DB_INVALID", "db must be an array");

        List<DbRef> refs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String path = "db[" + i + "]";
            JsonNode entry = node.get(i);
            if (!entry.isObject()) throw UseCaseException.validation("DB_INVALID", path + " must be an object");
            rejectUnknown(entry, DB_KEYS, path);

            JsonNode nameNode = entry.path("name");
            if (!nameNode.isString() || !DnsLabel.isValid(nameNode.asString())) {
                throw UseCaseException.validation("DB_INVALID", path + ".name must be a DNS label");
            }
            String name = nameNode.asString();
            if (!names.add(name)) {
                throw UseCaseException.validation("DB_INVALID", path + ".name '" + name + "' is duplicated");
            }

            JsonNode secretRefNode = entry.path("secretRef");
            if (!secretRefNode.isString() || secretRefNode.asString().isBlank()) {
                throw UseCaseException.validation("DB_INVALID", path + ".secretRef is required");
            }

            int poolSize = resolvePoolSize(entry, path, defaults.dbPoolSize(), ceilings.dbPoolSize());
            refs.add(new DbRef(new DnsLabel(name), secretRefNode.asString(), poolSize));
        }
        return List.copyOf(refs);
    }

    private static int resolvePoolSize(JsonNode entry, String path, int defaultValue, int ceiling) {
        JsonNode node = entry.path("poolSize");
        if (node.isMissingNode() || node.isNull()) return Math.min(defaultValue, ceiling);
        if (!fitsInt(node) || node.asInt() <= 0) {
            throw UseCaseException.validation("DB_INVALID", path + ".poolSize must be a positive integer");
        }
        int value = node.asInt();
        if (value > ceiling) {
            throw UseCaseException.validation("LIMIT_OVER_CEILING",
                    path + ".poolSize is " + value + ", which exceeds the ceiling of " + ceiling);
        }
        return value;
    }

    private static List<String> parseSimpleStringList(JsonNode root, String key) {
        JsonNode node = root.path(key);
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw UseCaseException.validation("CONFIG_INVALID", key + " must be an array");
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : node) {
            if (!entry.isString() || entry.asString().isBlank()) {
                throw UseCaseException.validation("CONFIG_INVALID", key + " entries must be non-blank strings");
            }
            String value = entry.asString();
            if (!seen.add(value)) {
                throw UseCaseException.validation("CONFIG_INVALID", key + " has a duplicate entry '" + value + "'");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    /// Checked for `node` as soon as it is entered, before any of its fields
    /// are validated for content; the message names the full JSON path
    /// (`limits.maxConcurency`, `endpoints[0].pth`).
    private static void rejectUnknown(JsonNode node, Set<String> allowed, String path) {
        for (var entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                String fullPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                throw UseCaseException.validation("MANIFEST_UNKNOWN_FIELD",
                        fullPath + " is not a recognised manifest field");
            }
        }
    }

    // ── readStored — the repository's reader ─────────────────────────────────

    /// The repository's reader for a stored `fn_versions.manifest` row.
    /// Ignores unknown keys, applies no ceilings, and never throws on a
    /// value a newer writer might add that it does not understand in an
    /// optional position — a malformed endpoint, subscription, schedule,
    /// public route or `db` entry is dropped rather than failing the whole
    /// manifest, mirroring `fn_hosts.loaded`'s tolerant reader
    /// (`function-registry.md` spec §6.3, §8 M14).
    ///
    /// @throws IllegalStateException `runtime` or `entrypoint` is unreadable
    ///                                — there is no safe default for either
    public static Manifest readStored(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("manifest is unreadable: not an object");
        }
        Runtime runtime;
        try {
            runtime = Runtime.parseStrict(root.path("runtime").asString());
        } catch (RuntimeException e) {
            throw new IllegalStateException("manifest runtime is unreadable", e);
        }
        String entrypoint = root.path("entrypoint").asString();
        if (entrypoint.isBlank()) {
            throw new IllegalStateException("manifest entrypoint is unreadable");
        }

        DnsLabel pool = readPool(root);
        boolean warm = readBoolean(root, "warm", false);
        Limits limits = readLimits(root, runtime);
        List<Endpoint> endpoints = readEndpoints(root);
        List<SubscriptionSpec> subscriptions = readSubscriptions(root, endpoints);
        List<ScheduleSpec> schedules = readSchedules(root, endpoints);
        List<PublicRoute> publicRoutes = readPublicRoutes(root);
        List<DbRef> db = readDb(root);
        List<String> config = readStringList(root, "config");
        List<String> secrets = readStringList(root, "secrets");
        List<String> httpAllow = readStringList(root, "httpAllow");

        return new Manifest(runtime, entrypoint, pool, warm, limits, endpoints, subscriptions, schedules,
                publicRoutes, config, secrets, db, httpAllow);
    }

    private static DnsLabel readPool(JsonNode root) {
        JsonNode node = root.path("pool");
        if (node.isString() && DnsLabel.isValid(node.asString())) {
            return new DnsLabel(node.asString());
        }
        return DEFAULT_POOL;
    }

    private static boolean readBoolean(JsonNode node, String key, boolean defaultValue) {
        JsonNode value = node.path(key);
        return value.isBoolean() ? value.asBoolean() : defaultValue;
    }

    private static Limits readLimits(JsonNode root, Runtime runtime) {
        JsonNode node = root.path("limits");
        int maxDurationMs = readPositiveInt(node, "maxDurationMs", FunctionLimits.DEFAULT_MAX_DURATION_MS);
        int maxConcurrency = readPositiveInt(node, "maxConcurrency", FunctionLimits.DEFAULT_MAX_CONCURRENCY);
        Integer wasmMemoryMb = runtime == Runtime.WASM
                ? readPositiveInt(node, "wasmMemoryMb", FunctionLimits.DEFAULT_WASM_MEMORY_MB)
                : null;
        return new Limits(maxDurationMs, maxConcurrency, wasmMemoryMb);
    }

    private static int readPositiveInt(JsonNode node, String key, int defaultValue) {
        JsonNode value = node.path(key);
        return fitsInt(value) && value.asInt() > 0 ? value.asInt() : defaultValue;
    }

    private static List<Endpoint> readEndpoints(JsonNode root) {
        JsonNode node = root.path("endpoints");
        if (!node.isArray()) return List.of();
        List<Endpoint> endpoints = new ArrayList<>();
        for (JsonNode entry : node) {
            readEndpoint(entry).ifPresent(endpoints::add);
        }
        return List.copyOf(endpoints);
    }

    private static Optional<Endpoint> readEndpoint(JsonNode node) {
        if (!node.isObject()) return Optional.empty();
        JsonNode pathNode = node.path("path");
        if (!pathNode.isString()) return Optional.empty();
        RoutePattern pattern;
        try {
            pattern = RoutePattern.parse(pathNode.asString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        JsonNode authNode = node.path("auth");
        if (!authNode.isString()) return Optional.empty();
        EndpointAuth auth;
        try {
            auth = EndpointAuth.parseStrict(authNode.asString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        List<HttpMethod> methods = readMethods(node);
        Cors cors = readCors(node);
        int maxBodyBytes = readPositiveInt(node, "maxBodyBytes", Endpoint.DEFAULT_MAX_BODY_BYTES);
        int timeoutMs = readPositiveInt(node, "timeoutMs", FunctionLimits.DEFAULT_MAX_DURATION_MS);
        try {
            return Optional.of(new Endpoint(pattern, auth, methods, cors, maxBodyBytes, timeoutMs));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<HttpMethod> readMethods(JsonNode node) {
        JsonNode methodsNode = node.path("methods");
        if (!methodsNode.isArray()) return List.of();
        List<HttpMethod> methods = new ArrayList<>();
        for (JsonNode entry : methodsNode) {
            if (!entry.isString()) continue;
            try {
                methods.add(HttpMethod.parseStrict(entry.asString()));
            } catch (RuntimeException ignored) {
                // dropped, see readEndpoint
            }
        }
        return List.copyOf(methods);
    }

    private static Cors readCors(JsonNode node) {
        JsonNode corsNode = node.path("cors");
        if (!corsNode.isObject()) return null;
        List<String> origins = readStringList(corsNode, "origins");
        List<String> methods = readStringList(corsNode, "methods");
        List<String> headers = readStringList(corsNode, "headers");
        boolean allowCredentials = readBoolean(corsNode, "allowCredentials", false);
        return new Cors(origins, methods, headers, allowCredentials);
    }

    private static List<SubscriptionSpec> readSubscriptions(JsonNode root, List<Endpoint> endpoints) {
        JsonNode node = root.path("subscriptions");
        if (!node.isArray()) return List.of();
        List<SubscriptionSpec> specs = new ArrayList<>();
        for (JsonNode entry : node) {
            readSubscription(entry, endpoints).ifPresent(specs::add);
        }
        return List.copyOf(specs);
    }

    private static Optional<SubscriptionSpec> readSubscription(JsonNode node, List<Endpoint> endpoints) {
        if (!node.isObject()) return Optional.empty();
        JsonNode eventTypeNode = node.path("eventType");
        if (!eventTypeNode.isString() || eventTypeNode.asString().isBlank()) return Optional.empty();
        Optional<RoutePattern> path = readLiteralPath(node, "path");
        if (path.isEmpty() || !readWebhookMatch(path.get(), endpoints)) return Optional.empty();
        DispatchMode mode = readMode(node);
        int maxRetries = readPositiveInt(node, "maxRetries", Subscription.DEFAULT_MAX_RETRIES);
        int timeoutSeconds = readPositiveInt(node, "timeoutSeconds", Subscription.DEFAULT_TIMEOUT_SECONDS);
        boolean dataOnly = readBoolean(node, "dataOnly", DEFAULT_SUBSCRIPTION_DATA_ONLY);
        return Optional.of(new SubscriptionSpec(eventTypeNode.asString(), path.get(), mode, maxRetries,
                timeoutSeconds, dataOnly));
    }

    private static DispatchMode readMode(JsonNode node) {
        JsonNode modeNode = node.path("mode");
        if (!modeNode.isString()) return DEFAULT_SUBSCRIPTION_MODE;
        try {
            return DispatchMode.parseStrict(modeNode.asString());
        } catch (RuntimeException e) {
            return DEFAULT_SUBSCRIPTION_MODE;
        }
    }

    private static List<ScheduleSpec> readSchedules(JsonNode root, List<Endpoint> endpoints) {
        JsonNode node = root.path("schedules");
        if (!node.isArray()) return List.of();
        List<ScheduleSpec> specs = new ArrayList<>();
        for (JsonNode entry : node) {
            readSchedule(entry, endpoints).ifPresent(specs::add);
        }
        return List.copyOf(specs);
    }

    private static Optional<ScheduleSpec> readSchedule(JsonNode node, List<Endpoint> endpoints) {
        if (!node.isObject()) return Optional.empty();
        JsonNode cronNode = node.path("cron");
        if (!cronNode.isString() || cronNode.asString().isBlank()) return Optional.empty();
        Optional<RoutePattern> path = readLiteralPath(node, "path");
        if (path.isEmpty() || !readWebhookMatch(path.get(), endpoints)) return Optional.empty();
        String timezone = readOptionalText(node, "timezone");
        JsonNode payloadNode = node.path("payload");
        JsonNode payload = (payloadNode.isMissingNode() || payloadNode.isNull()) ? null : payloadNode;
        return Optional.of(new ScheduleSpec(cronNode.asString(), timezone, path.get(), payload));
    }

    private static Optional<RoutePattern> readLiteralPath(JsonNode node, String key) {
        JsonNode pathNode = node.path(key);
        if (!pathNode.isString()) return Optional.empty();
        RoutePattern pattern;
        try {
            pattern = RoutePattern.parse(pathNode.asString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        for (RoutePattern.Segment segment : pattern.segments()) {
            if (!(segment instanceof RoutePattern.Literal)) return Optional.empty();
        }
        return Optional.of(pattern);
    }

    private static boolean readWebhookMatch(RoutePattern literalPath, List<Endpoint> endpoints) {
        return endpoints.stream()
                .filter(e -> e.path().match(literalPath.value()).isPresent())
                .min(Comparator.comparing(Endpoint::path))
                .map(e -> e.auth() == EndpointAuth.WEBHOOK)
                .orElse(false);
    }

    private static List<PublicRoute> readPublicRoutes(JsonNode root) {
        JsonNode node = root.path("public");
        if (!node.isArray()) return List.of();
        List<PublicRoute> routes = new ArrayList<>();
        for (JsonNode entry : node) {
            readPublicRoute(entry).ifPresent(routes::add);
        }
        return List.copyOf(routes);
    }

    private static Optional<PublicRoute> readPublicRoute(JsonNode node) {
        if (!node.isObject()) return Optional.empty();
        JsonNode hostnameNode = node.path("hostname");
        if (!hostnameNode.isString()) return Optional.empty();
        Hostname hostname;
        try {
            hostname = Hostname.parse(hostnameNode.asString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        JsonNode prefixNode = node.path("pathPrefix");
        RoutePattern pathPrefix;
        if (prefixNode.isMissingNode() || prefixNode.isNull()) {
            pathPrefix = PublicRoute.DEFAULT_PATH_PREFIX;
        } else {
            Optional<RoutePattern> parsed = readLiteralPath(node, "pathPrefix");
            if (parsed.isEmpty()) return Optional.empty();
            pathPrefix = parsed.get();
        }
        return Optional.of(new PublicRoute(hostname, pathPrefix));
    }

    private static List<DbRef> readDb(JsonNode root) {
        JsonNode node = root.path("db");
        if (!node.isArray()) return List.of();
        List<DbRef> refs = new ArrayList<>();
        for (JsonNode entry : node) {
            readDbRef(entry).ifPresent(refs::add);
        }
        return List.copyOf(refs);
    }

    private static Optional<DbRef> readDbRef(JsonNode node) {
        if (!node.isObject()) return Optional.empty();
        JsonNode nameNode = node.path("name");
        JsonNode secretRefNode = node.path("secretRef");
        if (!nameNode.isString() || !DnsLabel.isValid(nameNode.asString())) return Optional.empty();
        if (!secretRefNode.isString() || secretRefNode.asString().isBlank()) return Optional.empty();
        int poolSize = readPositiveInt(node, "poolSize", FunctionLimits.DEFAULT_DB_POOL_SIZE);
        return Optional.of(new DbRef(new DnsLabel(nameNode.asString()), secretRefNode.asString(), poolSize));
    }

    private static List<String> readStringList(JsonNode root, String key) {
        JsonNode node = root.path(key);
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry.isString() && !entry.asString().isBlank()) values.add(entry.asString());
        }
        return List.copyOf(values);
    }

    private static String readOptionalText(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isString() ? value.asString() : null;
    }

    // ── toJson — the wire/stored shape ───────────────────────────────────────

    /// Writes the manifest back to its wire shape (spec §3): lower-case
    /// `runtime`/`auth`, upper-case methods, optional values omitted (never
    /// `""`) when absent.
    public JsonNode toJson() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("runtime", runtime.wireValue());
        node.put("entrypoint", entrypoint);
        node.put("pool", pool.value());
        node.put("warm", warm);
        node.set("limits", limitsToJson());
        ArrayNode endpointsNode = node.putArray("endpoints");
        for (Endpoint endpoint : endpoints) endpointsNode.add(endpointToJson(endpoint));
        ArrayNode subscriptionsNode = node.putArray("subscriptions");
        for (SubscriptionSpec spec : subscriptions) subscriptionsNode.add(subscriptionToJson(spec));
        ArrayNode schedulesNode = node.putArray("schedules");
        for (ScheduleSpec spec : schedules) schedulesNode.add(scheduleToJson(spec));
        ArrayNode publicNode = node.putArray("public");
        for (PublicRoute route : publicRoutes) publicNode.add(publicRouteToJson(route));
        ArrayNode configNode = node.putArray("config");
        config.forEach(configNode::add);
        ArrayNode secretsNode = node.putArray("secrets");
        secrets.forEach(secretsNode::add);
        ArrayNode dbNode = node.putArray("db");
        for (DbRef ref : db) dbNode.add(dbRefToJson(ref));
        ArrayNode httpAllowNode = node.putArray("httpAllow");
        httpAllow.forEach(httpAllowNode::add);
        return node;
    }

    private JsonNode limitsToJson() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("maxDurationMs", limits.maxDurationMs());
        node.put("maxConcurrency", limits.maxConcurrency());
        if (limits.wasmMemoryMb() != null) node.put("wasmMemoryMb", limits.wasmMemoryMb());
        return node;
    }

    private static JsonNode endpointToJson(Endpoint endpoint) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("path", endpoint.path().value());
        node.put("auth", endpoint.auth().wireValue());
        if (!endpoint.methods().isEmpty()) {
            ArrayNode methodsNode = node.putArray("methods");
            endpoint.methods().forEach(m -> methodsNode.add(m.name()));
        }
        if (endpoint.cors() != null) node.set("cors", corsToJson(endpoint.cors()));
        node.put("maxBodyBytes", endpoint.maxBodyBytes());
        node.put("timeoutMs", endpoint.timeoutMs());
        return node;
    }

    private static JsonNode corsToJson(Cors cors) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        ArrayNode origins = node.putArray("origins");
        cors.origins().forEach(origins::add);
        ArrayNode methods = node.putArray("methods");
        cors.methods().forEach(methods::add);
        ArrayNode headers = node.putArray("headers");
        cors.headers().forEach(headers::add);
        node.put("allowCredentials", cors.allowCredentials());
        return node;
    }

    private static JsonNode subscriptionToJson(SubscriptionSpec spec) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("eventType", spec.eventType());
        node.put("path", spec.path().value());
        node.put("mode", spec.mode().wireValue());
        node.put("maxRetries", spec.maxRetries());
        node.put("timeoutSeconds", spec.timeoutSeconds());
        node.put("dataOnly", spec.dataOnly());
        return node;
    }

    private static JsonNode scheduleToJson(ScheduleSpec spec) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("cron", spec.cron());
        if (spec.timezone() != null) node.put("timezone", spec.timezone());
        node.put("path", spec.path().value());
        if (spec.payload() != null) node.set("payload", spec.payload());
        return node;
    }

    private static JsonNode publicRouteToJson(PublicRoute route) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("hostname", route.hostname().value());
        node.put("pathPrefix", route.pathPrefix().value());
        return node;
    }

    private static JsonNode dbRefToJson(DbRef ref) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("name", ref.name().value());
        node.put("secretRef", ref.secretRef());
        node.put("poolSize", ref.poolSize());
        return node;
    }
}
