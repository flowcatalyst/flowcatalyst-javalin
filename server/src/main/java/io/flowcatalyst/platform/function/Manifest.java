package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// A function version's manifest (spec `function-registry.md` §4) — the
/// declared shape of a deployable version: its entrypoint, resource limits,
/// triggers, config/secret names and outbound allow-list.
///
/// There are two readers (spec §4.2):
/// - [#parseStrict] — the publish reader. Rejects unknown keys at every
///   level and returns a manifest with every applicable limit filled in.
/// - [#readStored] — the repository's reader for `fn_versions.manifest`, a
///   foreign JSON shape (`CONVENTIONS.md` §8): tolerant of unknown keys and
///   missing optionals, never throws except when `runtime` or `entrypoint`
///   is unreadable.
///
/// Both read a Jackson tree through [Json#MAPPER]; [#toJson] writes the same
/// shape back, spellings exactly as §4.1 (lower-case `runtime`/`auth`,
/// upper-case methods) so `readStored(parseStrict(x).toJson())` round-trips
/// to an equal record.
///
/// @param runtime    the declared runtime, must match the function's own (§4.3 `RUNTIME_MISMATCH`)
/// @param entrypoint the class name (JVM) or export name (Wasm)
/// @param pool       the execution pool; `default` when absent (§4.3 `POOL_INVALID`)
/// @param warm       whether the function should be kept warm; `false` when absent
/// @param limits     the resolved resource limits (§4.6) — always fully populated
/// @param triggers   the triggers that invoke this version
/// @param config     required config variable names
/// @param secrets    required secret references
/// @param db         database connections this version needs
/// @param httpAllow  outbound hosts this version may call
public record Manifest(Runtime runtime, String entrypoint, DnsLabel pool, boolean warm, Limits limits,
                        List<Trigger> triggers, List<String> config, List<String> secrets, List<DbRef> db,
                        List<String> httpAllow) {

    /// The pool a manifest gets when it does not name one (spec §4.3 `POOL_INVALID`).
    public static final DnsLabel DEFAULT_POOL = new DnsLabel("default");

    private static final Pattern JVM_ENTRYPOINT = Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*$");
    private static final Pattern WASM_ENTRYPOINT = Pattern.compile("^[A-Za-z_]\\w*$");

    private static final Set<String> TOP_KEYS = Set.of(
            "runtime", "entrypoint", "pool", "warm", "limits", "triggers", "config", "secrets", "db", "httpAllow");
    private static final Set<String> LIMITS_KEYS = Set.of("maxDurationMs", "maxConcurrency", "wasmMemoryMb");
    private static final Set<String> EVENT_TRIGGER_KEYS = Set.of("type", "eventType", "messageGroupKey");
    private static final Set<String> SCHEDULE_TRIGGER_KEYS = Set.of("type", "cron", "timezone");
    private static final Set<String> HTTP_TRIGGER_KEYS = Set.of("type", "routes");
    private static final Set<String> HTTP_ROUTE_KEYS =
            Set.of("hostnames", "methods", "path", "auth", "cors", "maxBodyBytes", "timeoutMs");
    private static final Set<String> CORS_KEYS = Set.of("origins", "methods", "headers", "allowCredentials");
    private static final Set<String> DB_KEYS = Set.of("name", "secretRef", "poolSize");

    public Manifest {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(entrypoint, "entrypoint");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(limits, "limits");
        triggers = List.copyOf(triggers);
        config = List.copyOf(config);
        secrets = List.copyOf(secrets);
        db = List.copyOf(db);
        httpAllow = List.copyOf(httpAllow);
    }

    /// The resolved resource limits of one version (spec §4.6), frozen at
    /// publish: `wasmMemoryMb` is `null` for a JVM function (`LIMIT_NOT_APPLICABLE`
    /// forbids specifying it) and always resolved for a Wasm one.
    public record Limits(int maxDurationMs, int maxConcurrency, Integer wasmMemoryMb) {
        public Limits {
            if (maxDurationMs <= 0) throw new IllegalArgumentException("maxDurationMs must be > 0");
            if (maxConcurrency <= 0) throw new IllegalArgumentException("maxConcurrency must be > 0");
            if (wasmMemoryMb != null && wasmMemoryMb <= 0) {
                throw new IllegalArgumentException("wasmMemoryMb must be > 0");
            }
        }
    }

    /// What invokes this version (spec §4.1, §4.3 `TRIGGER_INVALID`/`TRIGGER_DUPLICATE`).
    public sealed interface Trigger {

        /// An event-type subscription.
        ///
        /// @param eventType      the pattern, required
        /// @param messageGroupKey optional ordering key; `null` when absent
        record Event(String eventType, String messageGroupKey) implements Trigger {
            public Event {
                Objects.requireNonNull(eventType, "eventType");
            }
        }

        /// A cron schedule. `cron`/`timezone` are carried as strings — whether
        /// the cron parses is TriggerSync's check (package B, spec §4.3).
        ///
        /// @param cron     the cron expression, required
        /// @param timezone optional IANA timezone name; `null` when absent
        record Schedule(String cron, String timezone) implements Trigger {
            public Schedule {
                Objects.requireNonNull(cron, "cron");
            }
        }

        /// An HTTP trigger: at least one route (`TRIGGER_INVALID` otherwise);
        /// at most one `http` trigger per manifest (`TRIGGER_DUPLICATE`).
        record Http(List<HttpRoute> routes) implements Trigger {
            public Http {
                routes = List.copyOf(routes);
            }
        }
    }

    /// One HTTP route of an `http` trigger (spec §4.1, §4.3 `ROUTE_INVALID`/`ROUTE_AMBIGUOUS`).
    ///
    /// @param hostnames    public hostnames; empty means private-only (§4.5)
    /// @param methods      accepted methods, at least one
    /// @param path         the route pattern (§5.2)
    /// @param auth         `BEARER` when absent — never public by omission (§4.5, §8 M6)
    /// @param cors         optional CORS policy; `null` when absent
    /// @param maxBodyBytes resolved; 1 MiB ([#DEFAULT_MAX_BODY_BYTES]) when absent
    /// @param timeoutMs    resolved; the function's `maxDurationMs` when absent
    public record HttpRoute(List<Hostname> hostnames, List<HttpMethod> methods, RoutePattern path, AuthMode auth,
                             Cors cors, int maxBodyBytes, int timeoutMs) {

        /// The default `maxBodyBytes` when a route does not name one (spec §4.5): 1 MiB.
        public static final int DEFAULT_MAX_BODY_BYTES = 1_048_576;

        public HttpRoute {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(auth, "auth");
            hostnames = List.copyOf(hostnames);
            methods = List.copyOf(methods);
            if (maxBodyBytes <= 0) throw new IllegalArgumentException("maxBodyBytes must be > 0");
            if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be > 0");
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

    /// A database connection this version needs (spec §4.3 `DB_INVALID`).
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

    // ── parseStrict — the publish reader (spec §4.2, §4.3) ──────────────────

    /// The publish reader: validates every rule of spec §4.3-§4.6 (first
    /// failure wins, in the table's order; an object's unknown-key check runs
    /// first, as it is entered) and rejects unknown keys at every level.
    ///
    /// @throws UseCaseException validation, one of the codes in spec §4.3's table
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
        List<Trigger> triggers = parseTriggersField(root, limits, ceilings);
        List<DbRef> db = parseDbField(root, defaults, ceilings);
        List<String> config = parseSimpleStringList(root, "config");
        List<String> secrets = parseSimpleStringList(root, "secrets");
        List<String> httpAllow = parseSimpleStringList(root, "httpAllow");

        return new Manifest(runtime, entrypoint, pool, warm, limits, triggers, config, secrets, db, httpAllow);
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
    /// (spec §4.3 `LIMIT_INVALID`: "fits an `int`" — a value like
    /// `5000000000` must be rejected, never silently truncated by
    /// `asInt()`). Shared by every integer field of the manifest.
    private static boolean fitsInt(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt();
    }

    /// Absent ⇒ `min(default, ceiling)` (spec §4.6); present ⇒ must be a
    /// positive integer (`LIMIT_INVALID`) not exceeding the ceiling
    /// (`LIMIT_OVER_CEILING`).
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

    private static List<Trigger> parseTriggersField(JsonNode root, Limits limits, ClientCeilings ceilings) {
        JsonNode node = root.path("triggers");
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw UseCaseException.validation("TRIGGER_INVALID", "triggers must be an array");

        List<Trigger> triggers = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            triggers.add(parseTrigger(node.get(i), "triggers[" + i + "]", limits, ceilings));
        }

        Set<String> eventTypes = new HashSet<>();
        int httpCount = 0;
        for (Trigger trigger : triggers) {
            switch (trigger) {
                case Trigger.Event event -> {
                    if (!eventTypes.add(event.eventType())) {
                        throw UseCaseException.validation("TRIGGER_DUPLICATE",
                                "duplicate event trigger for eventType '" + event.eventType() + "'");
                    }
                }
                case Trigger.Schedule ignored -> {
                }
                case Trigger.Http ignored -> {
                    httpCount++;
                    if (httpCount > 1) {
                        throw UseCaseException.validation("TRIGGER_DUPLICATE", "at most one http trigger is allowed");
                    }
                }
            }
        }

        List<HttpRoute> allRoutes = new ArrayList<>();
        for (Trigger trigger : triggers) {
            if (trigger instanceof Trigger.Http http) allRoutes.addAll(http.routes());
        }
        checkRouteAmbiguity(allRoutes);

        return List.copyOf(triggers);
    }

    /// `type` is read and validated first — absent, non-string, or unknown
    /// is `TRIGGER_INVALID` — before the unknown-key check runs against
    /// *that type's own* key set (spec §4.3 `MANIFEST_UNKNOWN_FIELD`: `cron`
    /// inside an `event` trigger is an unknown field, not an ignored one).
    private static Trigger parseTrigger(JsonNode node, String path, Limits limits, ClientCeilings ceilings) {
        if (!node.isObject()) throw UseCaseException.validation("TRIGGER_INVALID", path + " must be an object");
        JsonNode typeNode = node.path("type");
        if (!typeNode.isString()) {
            throw UseCaseException.validation("TRIGGER_INVALID", path + ".type must be event, schedule, or http");
        }
        String type = typeNode.asString();
        return switch (type) {
            case "event" -> {
                rejectUnknown(node, EVENT_TRIGGER_KEYS, path);
                yield parseEventTrigger(node, path);
            }
            case "schedule" -> {
                rejectUnknown(node, SCHEDULE_TRIGGER_KEYS, path);
                yield parseScheduleTrigger(node, path);
            }
            case "http" -> {
                rejectUnknown(node, HTTP_TRIGGER_KEYS, path);
                yield parseHttpTrigger(node, path, limits, ceilings);
            }
            default ->
                    throw UseCaseException.validation("TRIGGER_INVALID", path + ".type must be event, schedule, or http");
        };
    }

    private static Trigger.Event parseEventTrigger(JsonNode node, String path) {
        JsonNode eventTypeNode = node.path("eventType");
        if (!eventTypeNode.isString() || eventTypeNode.asString().isBlank()) {
            throw UseCaseException.validation("TRIGGER_INVALID", path + ".eventType is required for an event trigger");
        }
        String messageGroupKey = optionalText(node, "messageGroupKey", path);
        return new Trigger.Event(eventTypeNode.asString(), messageGroupKey);
    }

    private static Trigger.Schedule parseScheduleTrigger(JsonNode node, String path) {
        JsonNode cronNode = node.path("cron");
        if (!cronNode.isString() || cronNode.asString().isBlank()) {
            throw UseCaseException.validation("TRIGGER_INVALID", path + ".cron is required for a schedule trigger");
        }
        String timezone = optionalText(node, "timezone", path);
        return new Trigger.Schedule(cronNode.asString(), timezone);
    }

    private static String optionalText(JsonNode node, String key, String path) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) {
            throw UseCaseException.validation("TRIGGER_INVALID", path + "." + key + " must be a string");
        }
        return value.asString();
    }

    private static Trigger.Http parseHttpTrigger(JsonNode node, String path, Limits limits, ClientCeilings ceilings) {
        JsonNode routesNode = node.path("routes");
        if (!routesNode.isArray() || routesNode.isEmpty()) {
            throw UseCaseException.validation("TRIGGER_INVALID",
                    path + ".routes must be a non-empty array for an http trigger");
        }
        List<HttpRoute> routes = new ArrayList<>();
        for (int i = 0; i < routesNode.size(); i++) {
            routes.add(parseHttpRoute(routesNode.get(i), path + ".routes[" + i + "]", limits, ceilings));
        }
        return new Trigger.Http(List.copyOf(routes));
    }

    private static HttpRoute parseHttpRoute(JsonNode node, String path, Limits limits, ClientCeilings ceilings) {
        if (!node.isObject()) throw UseCaseException.validation("ROUTE_INVALID", path + " must be an object");
        rejectUnknown(node, HTTP_ROUTE_KEYS, path);

        List<Hostname> hostnames = parseHostnames(node, path);
        List<HttpMethod> methods = parseMethods(node, path);
        RoutePattern pattern = parseRoutePath(node, path);
        AuthMode auth = parseAuth(node, path);
        Cors cors = parseCors(node, path);
        int maxBodyBytes = parsePositiveOrDefault(node, "maxBodyBytes", path, HttpRoute.DEFAULT_MAX_BODY_BYTES);
        int timeoutMs = parseTimeoutMs(node, path, limits, ceilings);

        return new HttpRoute(hostnames, methods, pattern, auth, cors, maxBodyBytes, timeoutMs);
    }

    private static List<Hostname> parseHostnames(JsonNode node, String path) {
        JsonNode hostnamesNode = node.path("hostnames");
        if (hostnamesNode.isMissingNode() || hostnamesNode.isNull()) return List.of();
        if (!hostnamesNode.isArray()) {
            throw UseCaseException.validation("ROUTE_INVALID", path + ".hostnames must be an array");
        }
        List<Hostname> hostnames = new ArrayList<>();
        Set<Hostname> seen = new HashSet<>();
        for (JsonNode entry : hostnamesNode) {
            if (!entry.isString()) {
                throw UseCaseException.validation("ROUTE_INVALID", path + ".hostnames entries must be strings");
            }
            Hostname hostname;
            try {
                hostname = Hostname.parse(entry.asString());
            } catch (UseCaseException e) {
                throw UseCaseException.validation("ROUTE_INVALID",
                        path + ".hostnames: " + e.error().message());
            }
            if (!seen.add(hostname)) {
                throw UseCaseException.validation("ROUTE_INVALID",
                        path + ".hostnames has a duplicate entry '" + hostname.value() + "'");
            }
            hostnames.add(hostname);
        }
        return List.copyOf(hostnames);
    }

    private static List<HttpMethod> parseMethods(JsonNode node, String path) {
        JsonNode methodsNode = node.path("methods");
        if (!methodsNode.isArray() || methodsNode.isEmpty()) {
            throw UseCaseException.validation("ROUTE_INVALID", path + ".methods must be a non-empty array");
        }
        List<HttpMethod> methods = new ArrayList<>();
        Set<HttpMethod> seen = new HashSet<>();
        for (JsonNode entry : methodsNode) {
            if (!entry.isString()) {
                throw UseCaseException.validation("ROUTE_INVALID", path + ".methods entries must be strings");
            }
            HttpMethod method = HttpMethod.parseStrict(entry.asString());
            if (!seen.add(method)) {
                throw UseCaseException.validation("ROUTE_INVALID",
                        path + ".methods has a duplicate entry '" + method.name() + "'");
            }
            methods.add(method);
        }
        return List.copyOf(methods);
    }

    private static RoutePattern parseRoutePath(JsonNode node, String path) {
        JsonNode pathNode = node.path("path");
        if (!pathNode.isString()) {
            throw UseCaseException.validation("ROUTE_INVALID", path + ".path is required");
        }
        try {
            return RoutePattern.parse(pathNode.asString());
        } catch (UseCaseException e) {
            throw UseCaseException.validation("ROUTE_INVALID", path + ".path: " + e.error().message());
        }
    }

    private static AuthMode parseAuth(JsonNode node, String path) {
        JsonNode authNode = node.path("auth");
        if (authNode.isMissingNode() || authNode.isNull()) return AuthMode.BEARER;
        if (!authNode.isString()) {
            throw UseCaseException.validation("ROUTE_INVALID", path + ".auth must be a string");
        }
        return AuthMode.parseStrict(authNode.asString());
    }

    private static Cors parseCors(JsonNode node, String path) {
        JsonNode corsNode = node.path("cors");
        if (corsNode.isMissingNode() || corsNode.isNull()) return null;
        if (!corsNode.isObject()) throw UseCaseException.validation("ROUTE_INVALID", path + ".cors must be an object");
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
            throw UseCaseException.validation("ROUTE_INVALID", corsPath + ".allowCredentials must be a boolean");
        }
        return new Cors(origins, methods, headers, allowCredentials);
    }

    private static List<String> parseStringList(JsonNode node, String key, String path) {
        JsonNode listNode = node.path(key);
        if (listNode.isMissingNode() || listNode.isNull()) return List.of();
        if (!listNode.isArray()) {
            throw UseCaseException.validation("ROUTE_INVALID", path + "." + key + " must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode entry : listNode) {
            if (!entry.isString() || entry.asString().isBlank()) {
                throw UseCaseException.validation("ROUTE_INVALID", path + "." + key + " entries must be non-blank strings");
            }
            values.add(entry.asString());
        }
        return List.copyOf(values);
    }

    private static int parsePositiveOrDefault(JsonNode node, String key, String path, int defaultValue) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return defaultValue;
        if (!fitsInt(value) || value.asInt() <= 0) {
            throw UseCaseException.validation("ROUTE_INVALID", path + "." + key + " must be a positive integer");
        }
        return value.asInt();
    }

    private static int parseTimeoutMs(JsonNode node, String path, Limits limits, ClientCeilings ceilings) {
        JsonNode value = node.path("timeoutMs");
        if (value.isMissingNode() || value.isNull()) return limits.maxDurationMs();
        if (!fitsInt(value) || value.asInt() <= 0) {
            throw UseCaseException.validation("ROUTE_INVALID", path + ".timeoutMs must be a positive integer");
        }
        int timeoutMs = value.asInt();
        if (timeoutMs > ceilings.maxDurationMs()) {
            throw UseCaseException.validation("LIMIT_OVER_CEILING",
                    "timeoutMs is " + timeoutMs + ", which exceeds the ceiling of " + ceilings.maxDurationMs());
        }
        return timeoutMs;
    }

    /// Spec §4.3 `ROUTE_AMBIGUOUS`: two routes that share a method and whose
    /// patterns are ambiguous. Hostnames do **not** separate routes within
    /// one manifest: the private entry (`/fn/{address}/…`, design §4a)
    /// reaches a function by address with no hostname, so every route of
    /// the function is a candidate there regardless of which public
    /// hostnames it lists.
    private static void checkRouteAmbiguity(List<HttpRoute> routes) {
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                HttpRoute a = routes.get(i);
                HttpRoute b = routes.get(j);
                if (shareMethod(a, b) && a.path().ambiguousWith(b.path())) {
                    throw UseCaseException.validation("ROUTE_AMBIGUOUS",
                            "route '" + a.path().value() + "' and route '" + b.path().value()
                                    + "' are ambiguous");
                }
            }
        }
    }

    private static boolean shareMethod(HttpRoute a, HttpRoute b) {
        for (HttpMethod method : a.methods()) {
            if (b.methods().contains(method)) return true;
        }
        return false;
    }

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

    /// Spec §4.3 `MANIFEST_UNKNOWN_FIELD`: checked for `node` as soon as it
    /// is entered, before any of its fields are validated for content; the
    /// message names the full JSON path (`limits.maxConcurency`,
    /// `triggers[2].routes[0].pth`).
    private static void rejectUnknown(JsonNode node, Set<String> allowed, String path) {
        for (var entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                String fullPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                throw UseCaseException.validation("MANIFEST_UNKNOWN_FIELD", fullPath + " is not a recognised manifest field");
            }
        }
    }

    // ── readStored — the repository's reader (spec §4.2) ────────────────────

    /// The repository's reader for a stored `fn_versions.manifest` row.
    /// Ignores unknown keys, applies no ceilings, and never throws on a
    /// value a newer writer might add that it does not understand in an
    /// optional position — a malformed trigger, route or `db` entry is
    /// dropped rather than failing the whole manifest, mirroring
    /// `fn_hosts.loaded`'s tolerant reader (spec §6.3, §8 M14).
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
        List<Trigger> triggers = readTriggers(root);
        List<DbRef> db = readDb(root);
        List<String> config = readStringList(root, "config");
        List<String> secrets = readStringList(root, "secrets");
        List<String> httpAllow = readStringList(root, "httpAllow");

        return new Manifest(runtime, entrypoint, pool, warm, limits, triggers, config, secrets, db, httpAllow);
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

    private static List<Trigger> readTriggers(JsonNode root) {
        JsonNode node = root.path("triggers");
        if (!node.isArray()) return List.of();
        List<Trigger> triggers = new ArrayList<>();
        for (JsonNode entry : node) {
            readTrigger(entry).ifPresent(triggers::add);
        }
        return List.copyOf(triggers);
    }

    private static Optional<Trigger> readTrigger(JsonNode node) {
        if (!node.isObject()) return Optional.empty();
        String type = node.path("type").asString();
        return switch (type) {
            case "event" -> readEventTrigger(node);
            case "schedule" -> readScheduleTrigger(node);
            case "http" -> readHttpTrigger(node);
            default -> Optional.empty();
        };
    }

    private static Optional<Trigger> readEventTrigger(JsonNode node) {
        JsonNode eventTypeNode = node.path("eventType");
        if (!eventTypeNode.isString() || eventTypeNode.asString().isBlank()) return Optional.empty();
        return Optional.of(new Trigger.Event(eventTypeNode.asString(), readOptionalText(node, "messageGroupKey")));
    }

    private static Optional<Trigger> readScheduleTrigger(JsonNode node) {
        JsonNode cronNode = node.path("cron");
        if (!cronNode.isString() || cronNode.asString().isBlank()) return Optional.empty();
        return Optional.of(new Trigger.Schedule(cronNode.asString(), readOptionalText(node, "timezone")));
    }

    private static Optional<Trigger> readHttpTrigger(JsonNode node) {
        JsonNode routesNode = node.path("routes");
        if (!routesNode.isArray()) return Optional.empty();
        List<HttpRoute> routes = new ArrayList<>();
        for (JsonNode routeNode : routesNode) {
            readHttpRoute(routeNode).ifPresent(routes::add);
        }
        if (routes.isEmpty()) return Optional.empty();
        return Optional.of(new Trigger.Http(List.copyOf(routes)));
    }

    private static Optional<HttpRoute> readHttpRoute(JsonNode node) {
        if (!node.isObject()) return Optional.empty();
        JsonNode pathNode = node.path("path");
        if (!pathNode.isString()) return Optional.empty();
        RoutePattern pattern;
        try {
            pattern = RoutePattern.parse(pathNode.asString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        List<Hostname> hostnames = readHostnames(node);
        List<HttpMethod> methods = readMethods(node);
        AuthMode auth = readAuth(node);
        Cors cors = readCors(node);
        int maxBodyBytes = readPositiveInt(node, "maxBodyBytes", HttpRoute.DEFAULT_MAX_BODY_BYTES);
        int timeoutMs = readPositiveInt(node, "timeoutMs", FunctionLimits.DEFAULT_MAX_DURATION_MS);
        return Optional.of(new HttpRoute(hostnames, methods, pattern, auth, cors, maxBodyBytes, timeoutMs));
    }

    private static List<Hostname> readHostnames(JsonNode node) {
        JsonNode hostnamesNode = node.path("hostnames");
        if (!hostnamesNode.isArray()) return List.of();
        List<Hostname> hostnames = new ArrayList<>();
        for (JsonNode entry : hostnamesNode) {
            if (!entry.isString()) continue;
            try {
                hostnames.add(Hostname.parse(entry.asString()));
            } catch (RuntimeException ignored) {
                // a bad hostname is dropped, not fatal to the whole row (spec §6.3's pattern)
            }
        }
        return List.copyOf(hostnames);
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
                // dropped, see readHostnames
            }
        }
        return List.copyOf(methods);
    }

    private static AuthMode readAuth(JsonNode node) {
        JsonNode authNode = node.path("auth");
        if (!authNode.isString()) return AuthMode.BEARER;
        try {
            return AuthMode.parseStrict(authNode.asString());
        } catch (RuntimeException e) {
            return AuthMode.BEARER;
        }
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

    // ── toJson — the wire/stored shape (spec §4.1) ───────────────────────────

    /// Writes the manifest back to the shape of spec §4.1: lower-case
    /// `runtime`/`auth`, upper-case methods, optional strings omitted (never
    /// `""`) when absent.
    public JsonNode toJson() {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("runtime", runtime.wireValue());
        node.put("entrypoint", entrypoint);
        node.put("pool", pool.value());
        node.put("warm", warm);
        node.set("limits", limitsToJson());
        ArrayNode triggersNode = node.putArray("triggers");
        for (Trigger trigger : triggers) triggersNode.add(triggerToJson(trigger));
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

    private static JsonNode triggerToJson(Trigger trigger) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        switch (trigger) {
            case Trigger.Event event -> {
                node.put("type", "event");
                node.put("eventType", event.eventType());
                if (event.messageGroupKey() != null) node.put("messageGroupKey", event.messageGroupKey());
            }
            case Trigger.Schedule schedule -> {
                node.put("type", "schedule");
                node.put("cron", schedule.cron());
                if (schedule.timezone() != null) node.put("timezone", schedule.timezone());
            }
            case Trigger.Http http -> {
                node.put("type", "http");
                ArrayNode routesNode = node.putArray("routes");
                for (HttpRoute route : http.routes()) routesNode.add(routeToJson(route));
            }
        }
        return node;
    }

    private static JsonNode routeToJson(HttpRoute route) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        ArrayNode hostnamesNode = node.putArray("hostnames");
        route.hostnames().forEach(h -> hostnamesNode.add(h.value()));
        ArrayNode methodsNode = node.putArray("methods");
        route.methods().forEach(m -> methodsNode.add(m.name()));
        node.put("path", route.path().value());
        node.put("auth", route.auth().wireValue());
        if (route.cors() != null) node.set("cors", corsToJson(route.cors()));
        node.put("maxBodyBytes", route.maxBodyBytes());
        node.put("timeoutMs", route.timeoutMs());
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

    private static JsonNode dbRefToJson(DbRef ref) {
        ObjectNode node = Json.MAPPER.createObjectNode();
        node.put("name", ref.name().value());
        node.put("secretRef", ref.secretRef());
        node.put("poolSize", ref.poolSize());
        return node;
    }
}
