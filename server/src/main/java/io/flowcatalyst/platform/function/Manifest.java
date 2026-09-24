package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.sdk.result.Result;
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

    // Package-private (not `private`): `function-manifest-authoring.md` M1.5's drift test
    // (same package) walks these as "the parser's key set" for its object, level by level,
    // against `function-manifest.schema.json`'s own `properties`/`required`.
    static final Set<String> TOP_KEYS = Set.of(
            "runtime", "entrypoint", "pool", "warm", "limits", "endpoints", "subscriptions", "schedules", "public",
            "config", "secrets", "db", "httpAllow");
    static final Set<String> LIMITS_KEYS = Set.of("maxDurationMs", "maxConcurrency", "wasmMemoryMb");
    static final Set<String> ENDPOINT_KEYS =
            Set.of("path", "auth", "methods", "cors", "maxBodyBytes", "timeoutMs");
    static final Set<String> SUBSCRIPTION_KEYS =
            Set.of("eventType", "path", "mode", "maxRetries", "timeoutSeconds", "dataOnly");
    static final Set<String> SCHEDULE_KEYS = Set.of("cron", "timezone", "path", "payload");
    static final Set<String> PUBLIC_ROUTE_KEYS = Set.of("hostname", "pathPrefix", "aliasPrefixes");
    static final Set<String> CORS_KEYS = Set.of("origins", "methods", "headers", "allowCredentials");
    static final Set<String> DB_KEYS = Set.of("name", "secretRef", "poolSize");

    /// [#TOP_KEYS] plus the optional `$schema` escape hatch (spec
    /// `function-manifest-authoring.md` M1.2): an editor validates `manifest.json` against the
    /// published JSON Schema by pointing `$schema` at it. The parser accepts the key, type-checks
    /// it below, and otherwise ignores it — it is never a real manifest field, so [#TOP_KEYS]
    /// itself (what the schema drift test walks) stays exactly the parser's field set.
    private static final Set<String> TOP_KEYS_WITH_SCHEMA_ESCAPE;

    static {
        var withSchema = new HashSet<>(TOP_KEYS);
        withSchema.add("$schema");
        TOP_KEYS_WITH_SCHEMA_ESCAPE = Set.copyOf(withSchema);
    }

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
    ///
    /// @param aliasPrefixes opt-in alias name prefixes (spec
    ///                      `function-zones-and-aliases.md` §3): each a DNS
    ///                      label, never `live`, no duplicates; `[]` (the
    ///                      default) means exact-hostname match only —
    ///                      today's behaviour.
    public record PublicRoute(Hostname hostname, RoutePattern pathPrefix, List<String> aliasPrefixes) {

        /// `pathPrefix` when a public route entry does not name one.
        public static final RoutePattern DEFAULT_PATH_PREFIX = RoutePattern.parse("/");

        public PublicRoute {
            Objects.requireNonNull(hostname, "hostname");
            Objects.requireNonNull(pathPrefix, "pathPrefix");
            aliasPrefixes = List.copyOf(aliasPrefixes);
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

    // ── check — every independent problem, with a JSON Pointer ───────────────
    // spec `manifest-all-errors.md`: the parser reports every independent
    // problem in the document, not just the first. `pointer` is RFC 6901
    // (`""` = the document root); `code`/`message` are exactly what
    // `parseStrict` (below) throws for the first-encountered problem, so
    // publish's contract is unchanged.

    /// One independent problem `check` found (spec `manifest-all-errors.md`
    /// §1): `code`/`message` are the same pair `parseStrict` throws for the
    /// first problem in document order; `pointer` is an RFC 6901 JSON
    /// Pointer to where in the document it is (`""` = the document root).
    public record ManifestProblem(String code, String message, String pointer) {
        public ManifestProblem {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(pointer, "pointer");
        }
    }

    /// Every independent problem `check` found in one manifest document
    /// (spec §1) — never empty.
    public record ManifestRejected(List<ManifestProblem> problems) {
        public ManifestRejected {
            problems = List.copyOf(problems);
            if (problems.isEmpty()) {
                throw new IllegalArgumentException("problems must not be empty");
            }
        }
    }

    /// The mutable collector threaded through every `parse*` helper below
    /// (spec §1): a helper that fails records its problem(s) here and
    /// yields `Optional.empty()` — never throws, and never a placeholder
    /// value later code could mistake for real.
    private static final class Collector {
        private final List<ManifestProblem> problems = new ArrayList<>();

        void add(String code, String message, String pointer) {
            problems.add(new ManifestProblem(code, message, pointer));
        }

        boolean failed() {
            return !problems.isEmpty();
        }

        List<ManifestProblem> problems() {
            return problems;
        }
    }

    /// A field whose valid value may legitimately be `null` (`Cors`, a
    /// schedule's `timezone`) — distinguishes "absent, no error" (`ok=true,
    /// value=null`) from "present but invalid" (`ok=false`), which a plain
    /// `Optional` cannot (`Optional.empty()` would mean both).
    private record Field<T>(boolean ok, T value) {
        static <T> Field<T> of(T value) {
            return new Field<>(true, value);
        }

        static <T> Field<T> failed() {
            return new Field<>(false, null);
        }
    }

    /// One `endpoints[i]` entry's outcome: `path` is populated whenever the
    /// entry's `path` field itself parsed as a [RoutePattern], independently
    /// of whether the rest of the entry is valid; `endpoint` is populated
    /// only when the WHOLE entry parsed with no problems. The split is what
    /// lets a subscription/schedule whose path matches an entry that failed
    /// for some OTHER reason recognise that (spec §2) without re-parsing.
    private record EndpointOutcome(Optional<RoutePattern> path, Optional<Endpoint> endpoint) {
    }

    /// One candidate a literal subscription/schedule path can match against
    /// (spec §2): `path` the pattern attempted, `endpoint` the fully
    /// resolved endpoint if (and only if) that entry parsed cleanly.
    private record EndpointParse(RoutePattern path, Optional<Endpoint> endpoint) {
    }

    /// [#parseEndpointsField]'s result: `endpoints` (only the entries that
    /// parsed with no problems — what `ROUTE_AMBIGUOUS` and the final
    /// manifest see) and `attempts` (every entry whose `path` field itself
    /// parsed, valid or not — what a subscription/schedule's webhook-match
    /// check matches against, spec §2).
    private record EndpointsResult(List<Endpoint> endpoints, List<EndpointParse> attempts) {
    }

    /// The publish reader's collecting parser (spec `manifest-all-errors.md`
    /// §1): walks the whole document and returns every independent problem
    /// it found, each with a [ManifestProblem#pointer] to where it is, or
    /// the parsed [Manifest] when there were none. [#parseStrict] is the
    /// bridge to the envelope's exception contract, throwing the first
    /// problem in document order — the same code and message this returns
    /// as `problems().get(0)`.
    public static Result<Manifest, ManifestRejected> check(JsonNode root, Runtime functionRuntime,
                                                             FunctionLimits defaults, ClientCeilings ceilings) {
        Objects.requireNonNull(functionRuntime, "functionRuntime");
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(ceilings, "ceilings");
        if (root == null || !root.isObject()) {
            // spec §2: MANIFEST_REQUIRED is the only problem when it occurs.
            return Result.err(new ManifestRejected(List.of(
                    new ManifestProblem("MANIFEST_REQUIRED", "manifest is required and must be an object", ""))));
        }

        Collector c = new Collector();
        rejectUnknownAll(c, root, TOP_KEYS_WITH_SCHEMA_ESCAPE, "", "");
        checkSchemaField(c, root);

        Optional<Runtime> runtime = parseRuntimeField(c, root, functionRuntime);
        Optional<String> entrypoint = parseEntrypointField(c, root, runtime);
        Optional<DnsLabel> pool = parsePoolField(c, root);
        Optional<Boolean> warm = parseWarmField(c, root);
        Optional<Limits> limits = parseLimitsField(c, root, runtime, defaults, ceilings);
        EndpointsResult endpointsResult = parseEndpointsField(c, root, limits, ceilings);
        Optional<List<SubscriptionSpec>> subscriptions = parseSubscriptionsField(c, root, endpointsResult);
        Optional<List<ScheduleSpec>> schedules = parseSchedulesField(c, root, endpointsResult);
        Optional<List<PublicRoute>> publicRoutes = parsePublicField(c, root);
        Optional<List<DbRef>> db = parseDbField(c, root, defaults, ceilings);
        Optional<List<String>> config = parseSettingKeyList(c, root, "config");
        Optional<List<String>> secrets = parseSettingKeyList(c, root, "secrets");
        Optional<List<String>> httpAllow = parseSimpleStringList(c, root, "httpAllow");

        if (c.failed()) {
            return Result.err(new ManifestRejected(c.problems()));
        }
        return Result.ok(new Manifest(runtime.get(), entrypoint.get(), pool.get(), warm.get(), limits.get(),
                endpointsResult.endpoints(), subscriptions.get(), schedules.get(), publicRoutes.get(), config.get(),
                secrets.get(), db.get(), httpAllow.get()));
    }

    // ── parseStrict — the publish reader ─────────────────────────────────────

    /// The publish reader (spec `manifest-all-errors.md` §1): [#check] then,
    /// on a rejection, throws the FIRST problem in document order — same
    /// code, same message [#check] returns as `problems().get(0)`. Publish's
    /// contract is therefore unchanged from before this class collected
    /// every problem.
    ///
    /// @throws UseCaseException validation, one of the codes documented on this class
    public static Manifest parseStrict(JsonNode root, Runtime functionRuntime, FunctionLimits defaults,
                                        ClientCeilings ceilings) {
        return check(root, functionRuntime, defaults, ceilings).orElseThrow(rejected -> {
            ManifestProblem first = rejected.problems().get(0);
            return UseCaseException.validation(first.code(), first.message());
        });
    }

    /// `$schema` (spec `function-manifest-authoring.md` M1.2): an optional top-level string,
    /// present only so an editor can point at the published JSON Schema; any other JSON type is
    /// `MANIFEST_INVALID`. [#toJson] never writes it back.
    private static void checkSchemaField(Collector c, JsonNode root) {
        JsonNode schemaNode = root.path("$schema");
        if (schemaNode.isMissingNode() || schemaNode.isNull()) return;
        if (!schemaNode.isString()) {
            c.add("MANIFEST_INVALID", "$schema must be a string", "/$schema");
        }
    }

    private static Optional<Runtime> parseRuntimeField(Collector c, JsonNode root, Runtime functionRuntime) {
        Optional<Runtime> parsed = Runtime.tryParseStrict(root.path("runtime").asString());
        if (parsed.isEmpty()) {
            c.add("RUNTIME_INVALID", Runtime.INVALID_MESSAGE, "/runtime");
            return Optional.empty();
        }
        Runtime runtime = parsed.get();
        if (runtime != functionRuntime) {
            c.add("RUNTIME_MISMATCH", "manifest runtime '" + runtime.wireValue()
                    + "' does not match the function's runtime '" + functionRuntime.wireValue() + "'", "/runtime");
            return Optional.empty();
        }
        return Optional.of(runtime);
    }

    /// The pattern check is runtime-dependent (spec §2: skipped when
    /// `runtime` itself is invalid) — `entrypoint` still has a genuine value
    /// either way, so this never needs a placeholder.
    private static Optional<String> parseEntrypointField(Collector c, JsonNode root, Optional<Runtime> runtime) {
        String raw = root.path("entrypoint").asString();
        if (raw.isBlank()) {
            c.add("ENTRYPOINT_REQUIRED", "entrypoint is required", "/entrypoint");
            return Optional.empty();
        }
        if (runtime.isEmpty()) {
            return Optional.of(raw);
        }
        Pattern pattern = runtime.get() == Runtime.JVM ? JVM_ENTRYPOINT : WASM_ENTRYPOINT;
        if (!pattern.matcher(raw).matches()) {
            c.add("ENTRYPOINT_INVALID", runtime.get() == Runtime.JVM
                    ? "entrypoint must be a binary class name" : "entrypoint must be a wasm export name",
                    "/entrypoint");
            return Optional.empty();
        }
        return Optional.of(raw);
    }

    private static Optional<DnsLabel> parsePoolField(Collector c, JsonNode root) {
        JsonNode node = root.path("pool");
        if (node.isMissingNode() || node.isNull()) return Optional.of(DEFAULT_POOL);
        if (!node.isString() || !DnsLabel.isValid(node.asString())) {
            c.add("POOL_INVALID", "pool must be a DNS label", "/pool");
            return Optional.empty();
        }
        return Optional.of(new DnsLabel(node.asString()));
    }

    private static Optional<Boolean> parseWarmField(Collector c, JsonNode root) {
        JsonNode node = root.path("warm");
        if (node.isMissingNode() || node.isNull()) return Optional.of(false);
        if (!node.isBoolean()) {
            c.add("MANIFEST_INVALID", "warm must be a boolean", "/warm");
            return Optional.empty();
        }
        return Optional.of(node.asBoolean());
    }

    /// `maxDurationMs`/`maxConcurrency` are independent of `runtime` and
    /// always checked; `wasmMemoryMb` applicability is runtime-dependent and
    /// skipped when `runtime` is invalid (spec §2) — `wasmMemoryMb` is then
    /// simply left unresolved (`null`), never a guess, which is safe because
    /// a `runtime` problem already means this manifest is rejected.
    private static Optional<Limits> parseLimitsField(Collector c, JsonNode root, Optional<Runtime> runtime,
                                                      FunctionLimits defaults, ClientCeilings ceilings) {
        JsonNode node = root.path("limits");
        boolean present = !node.isMissingNode() && !node.isNull();
        if (present) {
            if (!node.isObject()) {
                c.add("LIMIT_INVALID", "limits must be an object", "/limits");
                return Optional.empty();
            }
            rejectUnknownAll(c, node, LIMITS_KEYS, "limits", "/limits");
        } else {
            node = Json.MAPPER.createObjectNode();
        }

        Optional<Integer> maxDurationMs =
                resolveLimit(c, node, "maxDurationMs", defaults.maxDurationMs(), ceilings.maxDurationMs());
        Optional<Integer> maxConcurrency =
                resolveLimit(c, node, "maxConcurrency", defaults.maxConcurrency(), ceilings.maxConcurrency());

        Integer wasmMemoryMb = null;
        boolean wasmOk = true;
        if (runtime.isPresent()) {
            JsonNode wasmNode = node.path("wasmMemoryMb");
            boolean wasmPresent = !wasmNode.isMissingNode() && !wasmNode.isNull();
            if (runtime.get() == Runtime.JVM) {
                if (wasmPresent) {
                    c.add("LIMIT_NOT_APPLICABLE", "wasmMemoryMb is not applicable to a jvm function",
                            "/limits/wasmMemoryMb");
                    wasmOk = false;
                }
            } else {
                Optional<Integer> resolved =
                        resolveLimit(c, node, "wasmMemoryMb", defaults.wasmMemoryMb(), ceilings.wasmMemoryMb());
                if (resolved.isEmpty()) {
                    wasmOk = false;
                } else {
                    wasmMemoryMb = resolved.get();
                }
            }
        }

        if (maxDurationMs.isEmpty() || maxConcurrency.isEmpty() || !wasmOk) return Optional.empty();
        return Optional.of(new Limits(maxDurationMs.get(), maxConcurrency.get(), wasmMemoryMb));
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
    private static Optional<Integer> resolveLimit(Collector c, JsonNode limitsNode, String key, int defaultValue,
                                                    int ceiling) {
        JsonNode node = limitsNode.path(key);
        if (node.isMissingNode() || node.isNull()) {
            return Optional.of(Math.min(defaultValue, ceiling));
        }
        if (!fitsInt(node) || node.asInt() <= 0) {
            c.add("LIMIT_INVALID", key + " must be a positive integer", "/limits/" + key);
            return Optional.empty();
        }
        int value = node.asInt();
        if (value > ceiling) {
            c.add("LIMIT_OVER_CEILING", key + " is " + value + ", which exceeds the ceiling of " + ceiling,
                    "/limits/" + key);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    // ── endpoints ─────────────────────────────────────────────────────────

    /// spec §2: `endpoints` only carries entries that parsed with no
    /// problems (what `ROUTE_AMBIGUOUS` and the final manifest see);
    /// `attempts` carries every entry whose `path` field itself parsed,
    /// valid or not, for a subscription/schedule's webhook-match check.
    private static EndpointsResult parseEndpointsField(Collector c, JsonNode root, Optional<Limits> limits,
                                                        ClientCeilings ceilings) {
        JsonNode node = root.path("endpoints");
        if (node.isMissingNode() || node.isNull()) return new EndpointsResult(List.of(), List.of());
        if (!node.isArray()) {
            c.add("ENDPOINT_INVALID", "endpoints must be an array", "/endpoints");
            return new EndpointsResult(List.of(), List.of());
        }

        List<Endpoint> endpoints = new ArrayList<>();
        List<EndpointParse> attempts = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            String dotted = "endpoints[" + i + "]";
            String pointer = "/endpoints/" + i;
            EndpointOutcome outcome = parseEndpoint(c, node.get(i), dotted, pointer, limits, ceilings);
            outcome.endpoint().ifPresent(endpoints::add);
            outcome.path().ifPresent(p -> attempts.add(new EndpointParse(p, outcome.endpoint())));
        }
        checkEndpointAmbiguity(c, endpoints);
        return new EndpointsResult(List.copyOf(endpoints), List.copyOf(attempts));
    }

    private static EndpointOutcome parseEndpoint(Collector c, JsonNode node, String dotted, String pointer,
                                                  Optional<Limits> limits, ClientCeilings ceilings) {
        if (!node.isObject()) {
            c.add("ENDPOINT_INVALID", dotted + " must be an object", pointer);
            return new EndpointOutcome(Optional.empty(), Optional.empty());
        }
        rejectUnknownAll(c, node, ENDPOINT_KEYS, dotted, pointer);

        Optional<RoutePattern> pattern = parseRoutePath(c, node, dotted, pointer);
        Optional<EndpointAuth> auth = parseEndpointAuth(c, node, dotted, pointer);
        Optional<List<HttpMethod>> methods = parseMethods(c, node, dotted, pointer);
        boolean methodsAuthOk = true;
        if (auth.isPresent() && methods.isPresent() && auth.get() == EndpointAuth.WEBHOOK
                && !methods.get().isEmpty()
                && !(methods.get().size() == 1 && methods.get().get(0) == HttpMethod.POST)) {
            c.add("ENDPOINT_INVALID",
                    dotted + ": a webhook endpoint's methods, if given, must be exactly [\"POST\"]", pointer);
            methodsAuthOk = false;
        }

        JsonNode corsNode = node.path("cors");
        Cors cors = null;
        boolean corsOk = true;
        if (!corsNode.isMissingNode() && !corsNode.isNull()) {
            Field<Cors> parsedCors = parseCors(c, corsNode, dotted, pointer);
            if (parsedCors.ok()) {
                cors = parsedCors.value();
            } else {
                corsOk = false;
            }
        }

        Optional<Integer> maxBodyBytes = parsePositiveOrDefault(c, node, "maxBodyBytes", dotted, pointer,
                Endpoint.DEFAULT_MAX_BODY_BYTES, "ENDPOINT_INVALID");
        Optional<Integer> timeoutMs = parseTimeoutMs(c, node, dotted, pointer, limits, ceilings);

        if (pattern.isEmpty() || auth.isEmpty() || methods.isEmpty() || !methodsAuthOk || !corsOk
                || maxBodyBytes.isEmpty() || timeoutMs.isEmpty()) {
            return new EndpointOutcome(pattern, Optional.empty());
        }
        Endpoint endpoint =
                new Endpoint(pattern.get(), auth.get(), methods.get(), cors, maxBodyBytes.get(), timeoutMs.get());
        return new EndpointOutcome(pattern, Optional.of(endpoint));
    }

    private static Optional<RoutePattern> parseRoutePath(Collector c, JsonNode node, String dotted, String pointer) {
        JsonNode pathNode = node.path("path");
        if (!pathNode.isString()) {
            c.add("ENDPOINT_INVALID", dotted + ".path is required", pointer + "/path");
            return Optional.empty();
        }
        Optional<RoutePattern> parsed = RoutePattern.tryParse(pathNode.asString());
        if (parsed.isEmpty()) {
            c.add("ENDPOINT_INVALID", dotted + ".path: " + RoutePattern.INVALID_MESSAGE, pointer + "/path");
        }
        return parsed;
    }

    /// `auth` is required — an absent value is `ENDPOINT_AUTH_REQUIRED`,
    /// distinct from an unrecognised one (`ENDPOINT_INVALID`, thrown by
    /// [EndpointAuth#parseStrict]) — spec §3: "no default".
    private static Optional<EndpointAuth> parseEndpointAuth(Collector c, JsonNode node, String dotted,
                                                              String pointer) {
        JsonNode authNode = node.path("auth");
        if (authNode.isMissingNode() || authNode.isNull()) {
            c.add("ENDPOINT_AUTH_REQUIRED", dotted + ".auth is required", pointer + "/auth");
            return Optional.empty();
        }
        if (!authNode.isString()) {
            c.add("ENDPOINT_INVALID", dotted + ".auth must be a string", pointer + "/auth");
            return Optional.empty();
        }
        Optional<EndpointAuth> parsed = EndpointAuth.tryParseStrict(authNode.asString());
        if (parsed.isEmpty()) {
            c.add("ENDPOINT_INVALID", EndpointAuth.INVALID_MESSAGE, pointer + "/auth");
        }
        return parsed;
    }

    /// `methods` absent/`null` ⇒ every method (spec §3); present ⇒ a
    /// non-empty array of distinct, recognised methods — every entry is
    /// checked independently (spec: "every independent problem").
    private static Optional<List<HttpMethod>> parseMethods(Collector c, JsonNode node, String dotted,
                                                             String pointer) {
        JsonNode methodsNode = node.path("methods");
        if (methodsNode.isMissingNode() || methodsNode.isNull()) return Optional.of(List.of());
        if (!methodsNode.isArray() || methodsNode.isEmpty()) {
            c.add("ENDPOINT_INVALID", dotted + ".methods, if given, must be a non-empty array",
                    pointer + "/methods");
            return Optional.empty();
        }
        List<HttpMethod> methods = new ArrayList<>();
        Set<HttpMethod> seen = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < methodsNode.size(); i++) {
            JsonNode entry = methodsNode.get(i);
            String entryPointer = pointer + "/methods/" + i;
            if (!entry.isString()) {
                c.add("ENDPOINT_INVALID", dotted + ".methods entries must be strings", entryPointer);
                ok = false;
                continue;
            }
            Optional<HttpMethod> method = HttpMethod.tryParseStrict(entry.asString());
            if (method.isEmpty()) {
                c.add("ENDPOINT_INVALID", dotted + ".methods has an unrecognised entry '" + entry.asString() + "'",
                        entryPointer);
                ok = false;
                continue;
            }
            if (!seen.add(method.get())) {
                c.add("ENDPOINT_INVALID", dotted + ".methods has a duplicate entry '" + method.get().name() + "'",
                        entryPointer);
                ok = false;
                continue;
            }
            methods.add(method.get());
        }
        return ok ? Optional.of(List.copyOf(methods)) : Optional.empty();
    }

    /// A publish-time origin: exactly `*`, or `scheme://host[:port]` with no
    /// path/query/fragment/userinfo (spec `function-public-routes.md` §4:
    /// "rejects an origin that is not `scheme://host[:port]` with no path").
    private static final Pattern ORIGIN_FORMAT =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/@?#\\s]+(:\\d+)?$");

    /// Called only once the caller has confirmed `corsNode` is present
    /// (spec: `cors` absent ⇒ `null`, no error — handled by the caller so
    /// `Field`'s `ok=true, value=null` never needs to mean two different
    /// things).
    private static Field<Cors> parseCors(Collector c, JsonNode corsNode, String dotted, String pointer) {
        if (!corsNode.isObject()) {
            c.add("ENDPOINT_INVALID", dotted + ".cors must be an object", pointer + "/cors");
            return Field.failed();
        }
        String corsDotted = dotted + ".cors";
        String corsPointer = pointer + "/cors";
        rejectUnknownAll(c, corsNode, CORS_KEYS, corsDotted, corsPointer);

        Optional<List<String>> origins = parseStringList(c, corsNode, "origins", corsDotted, corsPointer);
        boolean originsFormatOk = true;
        if (origins.isPresent()) {
            List<String> values = origins.get();
            for (int i = 0; i < values.size(); i++) {
                String origin = values.get(i);
                if (!"*".equals(origin) && !ORIGIN_FORMAT.matcher(origin).matches()) {
                    c.add("ENDPOINT_INVALID", corsDotted + ".origins entry '" + origin
                            + "' must be '*' or 'scheme://host[:port]' with no path",
                            corsPointer + "/origins/" + i);
                    originsFormatOk = false;
                }
            }
        }
        Optional<List<String>> methods = parseStringList(c, corsNode, "methods", corsDotted, corsPointer);
        Optional<List<String>> headers = parseStringList(c, corsNode, "headers", corsDotted, corsPointer);

        JsonNode credNode = corsNode.path("allowCredentials");
        Optional<Boolean> allowCredentials;
        if (credNode.isMissingNode() || credNode.isNull()) {
            allowCredentials = Optional.of(false);
        } else if (credNode.isBoolean()) {
            allowCredentials = Optional.of(credNode.asBoolean());
        } else {
            c.add("ENDPOINT_INVALID", corsDotted + ".allowCredentials must be a boolean",
                    corsPointer + "/allowCredentials");
            allowCredentials = Optional.empty();
        }

        // Spec §4: "*" with allowCredentials is rejected at publish — browsers refuse it,
        // and "reflect any origin with credentials" is the classic hole. Only meaningful
        // once both inputs resolved (spec §2: no cascades).
        boolean crossOk = true;
        if (origins.isPresent() && allowCredentials.isPresent() && allowCredentials.get()
                && origins.get().contains("*")) {
            c.add("ENDPOINT_INVALID", corsDotted + ": origins must not contain '*' when allowCredentials is true",
                    corsPointer);
            crossOk = false;
        }

        if (origins.isEmpty() || !originsFormatOk || methods.isEmpty() || headers.isEmpty()
                || allowCredentials.isEmpty() || !crossOk) {
            return Field.failed();
        }
        return Field.of(new Cors(origins.get(), methods.get(), headers.get(), allowCredentials.get()));
    }

    /// Every entry is checked independently (spec: "every independent
    /// problem") — a malformed entry does not stop the rest of the list
    /// from being checked.
    private static Optional<List<String>> parseStringList(Collector c, JsonNode node, String key, String dotted,
                                                            String pointer) {
        JsonNode listNode = node.path(key);
        if (listNode.isMissingNode() || listNode.isNull()) return Optional.of(List.of());
        if (!listNode.isArray()) {
            c.add("ENDPOINT_INVALID", dotted + "." + key + " must be an array", pointer + "/" + key);
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < listNode.size(); i++) {
            JsonNode entry = listNode.get(i);
            if (!entry.isString() || entry.asString().isBlank()) {
                c.add("ENDPOINT_INVALID", dotted + "." + key + " entries must be non-blank strings",
                        pointer + "/" + key + "/" + i);
                ok = false;
                continue;
            }
            values.add(entry.asString());
        }
        return ok ? Optional.of(List.copyOf(values)) : Optional.empty();
    }

    private static Optional<Integer> parsePositiveOrDefault(Collector c, JsonNode node, String key, String dotted,
                                                              String pointer, int defaultValue, String code) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return Optional.of(defaultValue);
        if (!fitsInt(value) || value.asInt() <= 0) {
            c.add(code, dotted + "." + key + " must be a positive integer", pointer + "/" + key);
            return Optional.empty();
        }
        return Optional.of(value.asInt());
    }

    /// Absent ⇒ defaults to the resolved `limits.maxDurationMs()` — spec §2:
    /// when `limits` itself is invalid, that default cannot be resolved, so
    /// this check is skipped entirely (empty, no NEW problem recorded); the
    /// endpoint is then simply left out of the result, same as any other
    /// endpoint that failed to build.
    private static Optional<Integer> parseTimeoutMs(Collector c, JsonNode node, String dotted, String pointer,
                                                      Optional<Limits> limits, ClientCeilings ceilings) {
        JsonNode value = node.path("timeoutMs");
        if (value.isMissingNode() || value.isNull()) {
            return limits.map(Limits::maxDurationMs);
        }
        if (!fitsInt(value) || value.asInt() <= 0) {
            c.add("ENDPOINT_INVALID", dotted + ".timeoutMs must be a positive integer", pointer + "/timeoutMs");
            return Optional.empty();
        }
        int timeoutMs = value.asInt();
        if (timeoutMs > ceilings.maxDurationMs()) {
            c.add("LIMIT_OVER_CEILING",
                    "timeoutMs is " + timeoutMs + ", which exceeds the ceiling of " + ceilings.maxDurationMs(),
                    pointer + "/timeoutMs");
            return Optional.empty();
        }
        return Optional.of(timeoutMs);
    }

    /// Two endpoints that share a method (absent/empty `methods` on either
    /// side means "every method", so it always shares) and whose patterns
    /// are ambiguous (spec `function-registry.md` §5.3) are `ROUTE_AMBIGUOUS`
    /// — every ambiguous pair is reported (spec: "every independent
    /// problem"), not just the first.
    private static void checkEndpointAmbiguity(Collector c, List<Endpoint> endpoints) {
        for (int i = 0; i < endpoints.size(); i++) {
            for (int j = i + 1; j < endpoints.size(); j++) {
                Endpoint a = endpoints.get(i);
                Endpoint b = endpoints.get(j);
                if (shareMethod(a, b) && a.path().ambiguousWith(b.path())) {
                    c.add("ROUTE_AMBIGUOUS", "endpoint '" + a.path().value() + "' and endpoint '" + b.path().value()
                            + "' are ambiguous", "/endpoints");
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
    private static Optional<RoutePattern> parseLiteralPath(Collector c, JsonNode node, String key, String dotted,
                                                             String pointer, String code) {
        JsonNode pathNode = node.path(key);
        if (!pathNode.isString()) {
            c.add(code, dotted + "." + key + " is required", pointer + "/" + key);
            return Optional.empty();
        }
        Optional<RoutePattern> parsed = RoutePattern.tryParse(pathNode.asString());
        if (parsed.isEmpty()) {
            c.add(code, dotted + "." + key + ": " + RoutePattern.INVALID_MESSAGE, pointer + "/" + key);
            return Optional.empty();
        }
        for (RoutePattern.Segment segment : parsed.get().segments()) {
            if (!(segment instanceof RoutePattern.Literal)) {
                c.add(code, dotted + "." + key + " must be a literal path, not a pattern", pointer + "/" + key);
                return Optional.empty();
            }
        }
        return parsed;
    }

    /// Spec §3: the entry's `path` must match an endpoint whose `auth` is
    /// `webhook`; when several attempts match, the most specific
    /// ([RoutePattern#compareTo] order, the same order [RoutePattern#firstMatch]
    /// uses) decides. Spec §2: when the winning match is an endpoint entry
    /// that itself failed to parse, that is not ALSO reported here — the
    /// endpoint's own problem already names it.
    private static boolean requireWebhookMatch(Collector c, RoutePattern literalPath, EndpointsResult endpoints,
                                                String dotted, String pointer, String code) {
        Optional<EndpointParse> winner = endpoints.attempts().stream()
                .filter(a -> a.path().match(literalPath.value()).isPresent())
                .min(Comparator.comparing(EndpointParse::path));
        if (winner.isEmpty()) {
            c.add(code, dotted + ".path '" + literalPath.value() + "' does not match a webhook endpoint",
                    pointer + "/path");
            return false;
        }
        Optional<Endpoint> resolved = winner.get().endpoint();
        if (resolved.isEmpty()) {
            return true;
        }
        if (resolved.get().auth() != EndpointAuth.WEBHOOK) {
            c.add(code, dotted + ".path '" + literalPath.value() + "' does not match a webhook endpoint",
                    pointer + "/path");
            return false;
        }
        return true;
    }

    private static Optional<List<SubscriptionSpec>> parseSubscriptionsField(Collector c, JsonNode root,
                                                                              EndpointsResult endpoints) {
        JsonNode node = root.path("subscriptions");
        if (node.isMissingNode() || node.isNull()) return Optional.of(List.of());
        if (!node.isArray()) {
            c.add("SUBSCRIPTION_INVALID", "subscriptions must be an array", "/subscriptions");
            return Optional.empty();
        }
        List<SubscriptionSpec> specs = new ArrayList<>();
        Set<String> eventTypes = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < node.size(); i++) {
            String dotted = "subscriptions[" + i + "]";
            String pointer = "/subscriptions/" + i;
            Optional<SubscriptionSpec> spec = parseSubscriptionSpec(c, node.get(i), dotted, pointer, endpoints);
            if (spec.isEmpty()) {
                ok = false;
                continue;
            }
            if (!eventTypes.add(spec.get().eventType())) {
                c.add("SUBSCRIPTION_DUPLICATE",
                        "duplicate subscription for eventType '" + spec.get().eventType() + "'", pointer);
                ok = false;
                continue;
            }
            specs.add(spec.get());
        }
        return ok ? Optional.of(List.copyOf(specs)) : Optional.empty();
    }

    private static Optional<SubscriptionSpec> parseSubscriptionSpec(Collector c, JsonNode node, String dotted,
                                                                      String pointer, EndpointsResult endpoints) {
        if (!node.isObject()) {
            c.add("SUBSCRIPTION_INVALID", dotted + " must be an object", pointer);
            return Optional.empty();
        }
        rejectUnknownAll(c, node, SUBSCRIPTION_KEYS, dotted, pointer);

        JsonNode eventTypeNode = node.path("eventType");
        boolean eventTypeOk = true;
        String eventType = null;
        if (!eventTypeNode.isString() || eventTypeNode.asString().isBlank()) {
            c.add("SUBSCRIPTION_INVALID", dotted + ".eventType is required", pointer + "/eventType");
            eventTypeOk = false;
        } else {
            eventType = eventTypeNode.asString();
        }

        Optional<RoutePattern> subscriptionPath =
                parseLiteralPath(c, node, "path", dotted, pointer, "SUBSCRIPTION_INVALID");
        boolean webhookOk = subscriptionPath.isEmpty()
                || requireWebhookMatch(c, subscriptionPath.get(), endpoints, dotted, pointer,
                        "SUBSCRIPTION_PATH_NOT_WEBHOOK");

        Optional<DispatchMode> mode = parseSubscriptionMode(c, node, dotted, pointer);
        Optional<Integer> maxRetries = parsePositiveOrDefault(c, node, "maxRetries", dotted, pointer,
                Subscription.DEFAULT_MAX_RETRIES, "SUBSCRIPTION_INVALID");
        Optional<Integer> timeoutSeconds = parsePositiveOrDefault(c, node, "timeoutSeconds", dotted, pointer,
                Subscription.DEFAULT_TIMEOUT_SECONDS, "SUBSCRIPTION_INVALID");
        Optional<Boolean> dataOnly = parseBooleanOrDefault(c, node, "dataOnly", dotted, pointer,
                DEFAULT_SUBSCRIPTION_DATA_ONLY, "SUBSCRIPTION_INVALID");

        if (!eventTypeOk || subscriptionPath.isEmpty() || !webhookOk || mode.isEmpty() || maxRetries.isEmpty()
                || timeoutSeconds.isEmpty() || dataOnly.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SubscriptionSpec(eventType, subscriptionPath.get(), mode.get(), maxRetries.get(),
                timeoutSeconds.get(), dataOnly.get()));
    }

    /// Absent ⇒ [Manifest#DEFAULT_SUBSCRIPTION_MODE] (`IMMEDIATE`); present ⇒
    /// [DispatchMode#tryParseStrict], reported as `SUBSCRIPTION_INVALID`.
    private static Optional<DispatchMode> parseSubscriptionMode(Collector c, JsonNode node, String dotted,
                                                                  String pointer) {
        JsonNode modeNode = node.path("mode");
        if (modeNode.isMissingNode() || modeNode.isNull()) return Optional.of(DEFAULT_SUBSCRIPTION_MODE);
        if (!modeNode.isString()) {
            c.add("SUBSCRIPTION_INVALID", dotted + ".mode must be a string", pointer + "/mode");
            return Optional.empty();
        }
        Optional<DispatchMode> parsed = DispatchMode.tryParseStrict(modeNode.asString());
        if (parsed.isEmpty()) {
            c.add("SUBSCRIPTION_INVALID", dotted + ".mode: " + DispatchMode.INVALID_MESSAGE, pointer + "/mode");
        }
        return parsed;
    }

    private static Field<String> optionalText(Collector c, JsonNode node, String key, String dotted, String pointer,
                                               String code) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return Field.of(null);
        if (!value.isString()) {
            c.add(code, dotted + "." + key + " must be a string", pointer + "/" + key);
            return Field.failed();
        }
        return Field.of(value.asString());
    }

    private static Optional<Boolean> parseBooleanOrDefault(Collector c, JsonNode node, String key, String dotted,
                                                             String pointer, boolean defaultValue, String code) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return Optional.of(defaultValue);
        if (!value.isBoolean()) {
            c.add(code, dotted + "." + key + " must be a boolean", pointer + "/" + key);
            return Optional.empty();
        }
        return Optional.of(value.asBoolean());
    }

    private static Optional<List<ScheduleSpec>> parseSchedulesField(Collector c, JsonNode root,
                                                                      EndpointsResult endpoints) {
        JsonNode node = root.path("schedules");
        if (node.isMissingNode() || node.isNull()) return Optional.of(List.of());
        if (!node.isArray()) {
            c.add("SCHEDULE_INVALID", "schedules must be an array", "/schedules");
            return Optional.empty();
        }
        List<ScheduleSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < node.size(); i++) {
            String dotted = "schedules[" + i + "]";
            String pointer = "/schedules/" + i;
            Optional<ScheduleSpec> spec = parseScheduleSpec(c, node.get(i), dotted, pointer, endpoints);
            if (spec.isEmpty()) {
                ok = false;
                continue;
            }
            String key = spec.get().cron() + "\0" + (spec.get().timezone() == null ? "" : spec.get().timezone());
            if (!seen.add(key)) {
                c.add("SCHEDULE_DUPLICATE", "duplicate schedule for cron '" + spec.get().cron() + "' timezone '"
                        + spec.get().timezone() + "'", pointer);
                ok = false;
                continue;
            }
            specs.add(spec.get());
        }
        return ok ? Optional.of(List.copyOf(specs)) : Optional.empty();
    }

    private static Optional<ScheduleSpec> parseScheduleSpec(Collector c, JsonNode node, String dotted,
                                                              String pointer, EndpointsResult endpoints) {
        if (!node.isObject()) {
            c.add("SCHEDULE_INVALID", dotted + " must be an object", pointer);
            return Optional.empty();
        }
        rejectUnknownAll(c, node, SCHEDULE_KEYS, dotted, pointer);

        JsonNode cronNode = node.path("cron");
        boolean cronOk = true;
        String cron = null;
        if (!cronNode.isString() || cronNode.asString().isBlank()) {
            c.add("SCHEDULE_INVALID", dotted + ".cron is required", pointer + "/cron");
            cronOk = false;
        } else {
            cron = cronNode.asString();
        }
        Field<String> timezone = optionalText(c, node, "timezone", dotted, pointer, "SCHEDULE_INVALID");

        Optional<RoutePattern> schedulePath = parseLiteralPath(c, node, "path", dotted, pointer, "SCHEDULE_INVALID");
        boolean webhookOk = schedulePath.isEmpty()
                || requireWebhookMatch(c, schedulePath.get(), endpoints, dotted, pointer, "SCHEDULE_PATH_NOT_WEBHOOK");

        JsonNode payloadNode = node.path("payload");
        JsonNode payload = (payloadNode.isMissingNode() || payloadNode.isNull()) ? null : payloadNode;

        if (!cronOk || !timezone.ok() || schedulePath.isEmpty() || !webhookOk) {
            return Optional.empty();
        }
        return Optional.of(new ScheduleSpec(cron, timezone.value(), schedulePath.get(), payload));
    }

    // ── public routes ─────────────────────────────────────────────────────

    private static Optional<List<PublicRoute>> parsePublicField(Collector c, JsonNode root) {
        JsonNode node = root.path("public");
        if (node.isMissingNode() || node.isNull()) return Optional.of(List.of());
        if (!node.isArray()) {
            c.add("PUBLIC_ROUTE_INVALID", "public must be an array", "/public");
            return Optional.empty();
        }
        List<PublicRoute> routes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < node.size(); i++) {
            String dotted = "public[" + i + "]";
            String pointer = "/public/" + i;
            Optional<PublicRoute> route = parsePublicRoute(c, node.get(i), dotted, pointer);
            if (route.isEmpty()) {
                ok = false;
                continue;
            }
            String key = route.get().hostname().value() + "\0" + route.get().pathPrefix().value();
            if (!seen.add(key)) {
                c.add("PUBLIC_ROUTE_DUPLICATE", "duplicate public route for '" + route.get().hostname().value()
                        + route.get().pathPrefix().value() + "'", pointer);
                ok = false;
                continue;
            }
            routes.add(route.get());
        }
        return ok ? Optional.of(List.copyOf(routes)) : Optional.empty();
    }

    private static Optional<PublicRoute> parsePublicRoute(Collector c, JsonNode node, String dotted,
                                                            String pointer) {
        if (!node.isObject()) {
            c.add("PUBLIC_ROUTE_INVALID", dotted + " must be an object", pointer);
            return Optional.empty();
        }
        rejectUnknownAll(c, node, PUBLIC_ROUTE_KEYS, dotted, pointer);

        JsonNode hostnameNode = node.path("hostname");
        Optional<Hostname> hostname;
        if (!hostnameNode.isString()) {
            c.add("PUBLIC_ROUTE_INVALID", dotted + ".hostname is required", pointer + "/hostname");
            hostname = Optional.empty();
        } else {
            hostname = Hostname.tryParse(hostnameNode.asString());
            if (hostname.isEmpty()) {
                c.add("PUBLIC_ROUTE_INVALID", dotted + ".hostname: " + Hostname.INVALID_MESSAGE,
                        pointer + "/hostname");
            }
        }

        JsonNode prefixNode = node.path("pathPrefix");
        Optional<RoutePattern> pathPrefix = (prefixNode.isMissingNode() || prefixNode.isNull())
                ? Optional.of(PublicRoute.DEFAULT_PATH_PREFIX)
                : parseLiteralPath(c, node, "pathPrefix", dotted, pointer, "PUBLIC_ROUTE_INVALID");

        Optional<List<String>> aliasPrefixes = parseAliasPrefixesField(c, node, dotted, pointer);

        if (hostname.isEmpty() || pathPrefix.isEmpty() || aliasPrefixes.isEmpty()) return Optional.empty();
        return Optional.of(new PublicRoute(hostname.get(), pathPrefix.get(), aliasPrefixes.get()));
    }

    /// spec `function-zones-and-aliases.md` §3: each entry a DNS label
    /// (reusing [DnsLabel]'s own format/length rule — `^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`,
    /// ≤ 63 characters), never `live` (that name is reserved for the exact
    /// hostname match), no duplicates. Absent/empty ⇒ `[]` (exact match
    /// only). Every entry is checked independently.
    private static Optional<List<String>> parseAliasPrefixesField(Collector c, JsonNode node, String dotted,
                                                                    String pointer) {
        JsonNode aliasNode = node.path("aliasPrefixes");
        if (aliasNode.isMissingNode() || aliasNode.isNull()) return Optional.of(List.of());
        if (!aliasNode.isArray()) {
            c.add("PUBLIC_ROUTE_INVALID", dotted + ".aliasPrefixes must be an array", pointer + "/aliasPrefixes");
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < aliasNode.size(); i++) {
            JsonNode entry = aliasNode.get(i);
            String entryDotted = dotted + ".aliasPrefixes[" + i + "]";
            String entryPointer = pointer + "/aliasPrefixes/" + i;
            if (!entry.isString() || !DnsLabel.isValid(entry.asString())) {
                c.add("PUBLIC_ROUTE_INVALID", entryDotted + " must be a DNS label", entryPointer);
                ok = false;
                continue;
            }
            String value = entry.asString();
            if (Function.LIVE.equals(value)) {
                c.add("PUBLIC_ROUTE_INVALID", entryDotted + " must not be 'live'", entryPointer);
                ok = false;
                continue;
            }
            if (!seen.add(value)) {
                c.add("PUBLIC_ROUTE_INVALID", entryDotted + " is a duplicate", entryPointer);
                ok = false;
                continue;
            }
            out.add(value);
        }
        return ok ? Optional.of(List.copyOf(out)) : Optional.empty();
    }

    // ── db / config / secrets / httpAllow ────────────────────────────────────

    private static Optional<List<DbRef>> parseDbField(Collector c, JsonNode root, FunctionLimits defaults,
                                                        ClientCeilings ceilings) {
        JsonNode node = root.path("db");
        if (node.isMissingNode() || node.isNull()) return Optional.of(List.of());
        if (!node.isArray()) {
            c.add("DB_INVALID", "db must be an array", "/db");
            return Optional.empty();
        }

        List<DbRef> refs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < node.size(); i++) {
            String dotted = "db[" + i + "]";
            String pointer = "/db/" + i;
            JsonNode entry = node.get(i);
            if (!entry.isObject()) {
                c.add("DB_INVALID", dotted + " must be an object", pointer);
                ok = false;
                continue;
            }
            rejectUnknownAll(c, entry, DB_KEYS, dotted, pointer);

            JsonNode nameNode = entry.path("name");
            Optional<String> name = Optional.empty();
            if (!nameNode.isString() || !DnsLabel.isValid(nameNode.asString())) {
                c.add("DB_INVALID", dotted + ".name must be a DNS label", pointer + "/name");
            } else {
                String n = nameNode.asString();
                if (!names.add(n)) {
                    c.add("DB_INVALID", dotted + ".name '" + n + "' is duplicated", pointer + "/name");
                } else {
                    name = Optional.of(n);
                }
            }

            JsonNode secretRefNode = entry.path("secretRef");
            Optional<String> secretRef = Optional.empty();
            if (!secretRefNode.isString() || secretRefNode.asString().isBlank()) {
                c.add("DB_INVALID", dotted + ".secretRef is required", pointer + "/secretRef");
            } else if (!SettingKey.isValid(secretRefNode.asString())) {
                c.add("DB_INVALID", dotted + ".secretRef: " + SettingKey.invalidMessage(secretRefNode.asString()),
                        pointer + "/secretRef");
            } else {
                secretRef = Optional.of(secretRefNode.asString());
            }

            Optional<Integer> poolSize =
                    resolvePoolSize(c, entry, dotted, pointer, defaults.dbPoolSize(), ceilings.dbPoolSize());

            if (name.isEmpty() || secretRef.isEmpty() || poolSize.isEmpty()) {
                ok = false;
                continue;
            }
            refs.add(new DbRef(new DnsLabel(name.get()), secretRef.get(), poolSize.get()));
        }
        return ok ? Optional.of(List.copyOf(refs)) : Optional.empty();
    }

    private static Optional<Integer> resolvePoolSize(Collector c, JsonNode entry, String dotted, String pointer,
                                                       int defaultValue, int ceiling) {
        JsonNode node = entry.path("poolSize");
        if (node.isMissingNode() || node.isNull()) return Optional.of(Math.min(defaultValue, ceiling));
        if (!fitsInt(node) || node.asInt() <= 0) {
            c.add("DB_INVALID", dotted + ".poolSize must be a positive integer", pointer + "/poolSize");
            return Optional.empty();
        }
        int value = node.asInt();
        if (value > ceiling) {
            c.add("LIMIT_OVER_CEILING", dotted + ".poolSize is " + value + ", which exceeds the ceiling of "
                    + ceiling, pointer + "/poolSize");
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private static Optional<List<String>> parseSimpleStringList(Collector c, JsonNode root, String key) {
        JsonNode node = root.path(key);
        if (node.isMissingNode() || node.isNull()) return Optional.of(List.of());
        if (!node.isArray()) {
            c.add("CONFIG_INVALID", key + " must be an array", "/" + key);
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String pointer = "/" + key + "/" + i;
            if (!entry.isString() || entry.asString().isBlank()) {
                c.add("CONFIG_INVALID", key + " entries must be non-blank strings", pointer);
                ok = false;
                continue;
            }
            String value = entry.asString();
            if (!seen.add(value)) {
                c.add("CONFIG_INVALID", key + " has a duplicate entry '" + value + "'", pointer);
                ok = false;
                continue;
            }
            values.add(value);
        }
        return ok ? Optional.of(List.copyOf(values)) : Optional.empty();
    }

    /// `config`/`secrets`: [#parseSimpleStringList]'s shape check plus the
    /// [SettingKey] format rule (spec `function-context.md` §1: "the
    /// manifest's config/secrets/secretRef entries are held to the same
    /// rule") — the code stays `CONFIG_INVALID`, wrapping
    /// [SettingKey]'s own message via [SettingKey#invalidMessage]. Every
    /// entry is checked independently.
    private static Optional<List<String>> parseSettingKeyList(Collector c, JsonNode root, String key) {
        JsonNode node = root.path(key);
        if (node.isMissingNode() || node.isNull()) return Optional.of(List.of());
        if (!node.isArray()) {
            c.add("CONFIG_INVALID", key + " must be an array", "/" + key);
            return Optional.empty();
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean ok = true;
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String pointer = "/" + key + "/" + i;
            if (!entry.isString() || entry.asString().isBlank()) {
                c.add("CONFIG_INVALID", key + " entries must be non-blank strings", pointer);
                ok = false;
                continue;
            }
            String value = entry.asString();
            if (!SettingKey.isValid(value)) {
                c.add("CONFIG_INVALID", key + ": " + SettingKey.invalidMessage(value), pointer);
                ok = false;
                continue;
            }
            if (!seen.add(value)) {
                c.add("CONFIG_INVALID", key + " has a duplicate entry '" + value + "'", pointer);
                ok = false;
                continue;
            }
            values.add(value);
        }
        return ok ? Optional.of(List.copyOf(values)) : Optional.empty();
    }

    /// Checked for `node` as soon as it is entered, before any of its fields
    /// are validated for content; the message names the full JSON path
    /// (`limits.maxConcurency`, `endpoints[0].pth`) — every unknown key is
    /// reported (spec §2), and the known keys beside them are still parsed.
    private static void rejectUnknownAll(Collector c, JsonNode node, Set<String> allowed, String dottedPath,
                                          String pointerPath) {
        for (var entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                String fullDotted = dottedPath.isEmpty() ? entry.getKey() : dottedPath + "." + entry.getKey();
                String fullPointer = pointerPath + "/" + entry.getKey();
                c.add("MANIFEST_UNKNOWN_FIELD", fullDotted + " is not a recognised manifest field", fullPointer);
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
        List<String> config = readSettingKeyList(root, "config");
        List<String> secrets = readSettingKeyList(root, "secrets");
        List<String> httpAllow = readStringList(root, "httpAllow");

        return new Manifest(runtime, entrypoint, pool, warm, limits, endpoints, subscriptions, schedules,
                publicRoutes, config, secrets, db, httpAllow);
    }

    /// Best-effort `pool` for a manifest [#readStored] itself refused (`runtime`/
    /// `entrypoint` unreadable) — [#readPool] never looks at either field, so it is
    /// always safe to call even on an otherwise-corrupt manifest. This is the ONLY way
    /// `DesiredState`'s blast-radius fix (`function-registry.md` §4.2,
    /// `function-host-reconciler.md` §1.2 step 4) can tell whether a corrupt LIVE
    /// version would have appeared in a given pool's document without being able to
    /// read the rest of its manifest. Same fallback as [#readPool]: an absent/invalid
    /// `pool` field reads as [#DEFAULT_POOL], never `null` — `root` itself not being a
    /// JSON object is the one case genuinely unreadable.
    public static DnsLabel peekStoredPool(JsonNode root) {
        return root != null && root.isObject() ? readPool(root) : null;
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
        List<String> aliasPrefixes = readAliasPrefixes(node);
        return Optional.of(new PublicRoute(hostname, pathPrefix, aliasPrefixes));
    }

    /// Tolerant counterpart of [#parseAliasPrefixesField]: a malformed entry
    /// (not a valid DNS label, `live`, or a repeat) is simply dropped rather
    /// than failing the whole route (spec: `readStored` "never throws...
    /// a malformed ... entry is dropped").
    private static List<String> readAliasPrefixes(JsonNode node) {
        JsonNode aliasNode = node.path("aliasPrefixes");
        if (!aliasNode.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : aliasNode) {
            if (!entry.isString()) continue;
            String value = entry.asString();
            if (!DnsLabel.isValid(value) || Function.LIVE.equals(value)) continue;
            if (seen.add(value)) out.add(value);
        }
        return List.copyOf(out);
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
        if (!secretRefNode.isString() || !SettingKey.isValid(secretRefNode.asString())) return Optional.empty();
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

    /// [#readStringList] narrowed to valid [SettingKey]s (spec §1's tolerant
    /// reader: a malformed key is dropped, never fails the whole manifest).
    private static List<String> readSettingKeyList(JsonNode root, String key) {
        JsonNode node = root.path(key);
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry.isString() && SettingKey.isValid(entry.asString())) values.add(entry.asString());
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
        if (!route.aliasPrefixes().isEmpty()) {
            ArrayNode aliasNode = node.putArray("aliasPrefixes");
            route.aliasPrefixes().forEach(aliasNode::add);
        }
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
