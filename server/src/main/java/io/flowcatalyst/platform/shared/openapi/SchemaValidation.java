package io.flowcatalyst.platform.shared.openapi;

import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Request-schema validation in huma's shape (`docs/spec/request-schema-validation.md`):
/// a `before` filter, registered right after the authenticator and before
/// every handler (spec §3), that validates an incoming request against the
/// lockfile operation it matches and answers the huma-shaped `VALIDATION`
/// envelope (spec §1) on the first failure it finds, or does nothing for a
/// route the lockfile does not cover.
///
/// [#build] resolves the whole lockfile into a `(METHOD, path template) →
/// {bodySchema?, parameters[]}` table exactly once, at startup, and walks
/// every reachable schema node to confirm it uses only the keywords
/// [SchemaValidator] implements — see that class's doc for the exact set and
/// for the three places this implementation corrects the written spec
/// against the real `huma`/`httpcompat` source. A lockfile bump that
/// introduces an unimplemented keyword fails the server at startup rather
/// than silently validating nothing for the new field.
///
/// Path matching reproduces `parity.Coverage#matchesTemplate` (`{id}` matches
/// exactly one path segment) rather than importing it: `parity` depends on
/// `server`, so the dependency cannot run the other way.
public final class SchemaValidation implements Handler {

    private record Route(String method, String pathTemplate, JsonNode bodySchema, List<Param> parameters) {
    }

    private record Param(String name, String in, boolean required, JsonNode schema) {
    }

    private final Lockfile lockfile;
    private final List<Route> table;

    private SchemaValidation(Lockfile lockfile, List<Route> table) {
        this.lockfile = lockfile;
        this.table = table;
    }

    /// Builds the operation table and validates every reachable schema
    /// keyword eagerly. Call once at startup ([io.flowcatalyst.server.Platform#register]);
    /// throws [IllegalStateException] if the lockfile uses a schema keyword
    /// [SchemaValidator] does not implement.
    public static SchemaValidation build(Lockfile lockfile) {
        Objects.requireNonNull(lockfile, "lockfile");
        var visitedRefs = new HashSet<String>();
        var table = new ArrayList<Route>();
        for (var op : lockfile.operations()) {
            var opNode = lockfile.operationNode(op.method(), op.path());
            var bodySchema = opNode.path("requestBody").path("content").path("application/json").path("schema");
            if (bodySchema.isMissingNode()) {
                bodySchema = null;
            } else {
                SchemaValidator.checkKeywords(lockfile, bodySchema, visitedRefs);
            }
            var parameters = new ArrayList<Param>();
            for (var paramNode : opNode.path("parameters")) {
                var in = paramNode.path("in").stringValue();
                if (!"query".equals(in) && !"path".equals(in)) {
                    continue; // spec §2: only query/path parameters are schema-validated
                }
                if (!paramNode.path("schema").isObject()) {
                    continue;
                }
                var schema = paramNode.path("schema");
                SchemaValidator.checkKeywords(lockfile, schema, visitedRefs);
                parameters.add(new Param(paramNode.path("name").stringValue(), in,
                        paramNode.path("required").asBoolean(false), schema));
            }
            table.add(new Route(op.method(), op.path(), bodySchema, List.copyOf(parameters)));
        }
        return new SchemaValidation(lockfile, List.copyOf(table));
    }

    @Override
    public void handle(Context ctx) {
        var method = ctx.method().name();
        var path = ctx.path();
        Route match = null;
        for (var route : table) {
            if (route.method().equals(method) && matchesTemplate(route.pathTemplate(), path)) {
                match = route;
                break;
            }
        }
        if (match == null) {
            return; // not a lockfile operation — untouched (spec §3)
        }

        var errors = new ArrayList<Map<String, Object>>();

        // Go validates parameters before the body (huma.go: `inputParams.Every`
        // runs, then "Read input body if defined").
        for (var param : match.parameters()) {
            validateParam(ctx, match.pathTemplate(), param, errors);
        }

        if (match.bodySchema() != null) {
            var parsed = tryParse(ctx.body());
            if (parsed != null) {
                var bodySchema = match.pathTemplate().endsWith("/sync")
                        ? syncRouteBodySchema(lockfile, match.bodySchema())
                        : match.bodySchema();
                SchemaValidator.validateValue(lockfile, bodySchema, "body", parsed, errors);
            }
            // A parse failure is left alone — the handler's own INVALID_JSON
            // path stays as it is (spec §2, design constraint: "on a parse
            // failure does nothing").
        }

        if (!errors.isEmpty()) {
            HttpError.writeValidation(ctx, errors);
            ctx.skipRemainingHandlers();
        }
    }

    /// docs/spec/sdksync.md §3 "Deviation (1)" (an owner-reviewed, deliberate
    /// improvement on Go, not a parity gap this feature should close): every
    /// `/…/sync` route treats its OWN top-level `required` list as
    /// informational, not enforced — "a body whose list field is absent
    /// (`{}`) is treated as an empty list… the lockfile's `required` is
    /// informational here" (the `SyncXCommand`s all coerce a `null` list to
    /// `List.of()`), and `/api/processes/sync`'s `applicationCode` gets the
    /// same pass ("absent/blank → 404 `Application_NOT_FOUND`… kept as 404,
    /// the lookup is the first thing that sees it").
    ///
    /// This is the one place request-schema-validation.md's general "schema
    /// first, always" rule must not apply verbatim, so it is scoped as
    /// narrowly as the deviation itself: a COPY of the top-level body schema
    /// with `required` cleared (nothing else — the lockfile object is never
    /// mutated, since it is shared across every request). Every NESTED
    /// schema — each sync array's own item shape (`SyncRoleInputRequest` and
    /// its siblings) — is untouched and keeps its own real `required`, and
    /// `additionalProperties` / type checks on whatever IS present in the
    /// top-level body still run exactly as usual.
    private static JsonNode syncRouteBodySchema(Lockfile lockfile, JsonNode bodySchema) {
        var resolved = lockfile.resolveRef(bodySchema);
        if (!resolved.has("required")) {
            return resolved;
        }
        var lenient = Json.MAPPER.createObjectNode();
        lenient.setAll((ObjectNode) resolved);
        lenient.remove("required");
        return lenient;
    }

    private static JsonNode tryParse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    // ── parameters (huma `huma.go` request binding — a different code path
    //    from body validation: the raw string is coerced first, with its own
    //    messages, and only the successfully-coerced value ever reaches
    //    SchemaValidator) ──────────────────────────────────────────────────

    private static final Set<String> TRUE_VALUES = Set.of("1", "t", "T", "TRUE", "true", "True");
    private static final Set<String> FALSE_VALUES = Set.of("0", "f", "F", "FALSE", "false", "False");

    private void validateParam(Context ctx, String pathTemplate, Param param, List<Map<String, Object>> errors) {
        // Deliberately NOT `ctx.pathParam(name)`: that call validates `name`
        // against the concrete Javalin route Jetty actually matched, which this
        // global `before` filter runs ahead of/independent from — it throws
        // `IllegalArgumentException` whenever no registered route happens to
        // declare that exact parameter (any lockfile operation with no handler
        // mounted yet, as several `*ApiTest`s intentionally exercise). The
        // lockfile path TEMPLATE this request already matched is authoritative
        // on its own; extracting the segment from it needs no registered route.
        String raw = switch (param.in()) {
            case "query" -> ctx.queryParam(param.name());
            case "path" -> extractPathSegment(pathTemplate, ctx.path(), param.name());
            default -> null;
        };
        var location = param.in() + "." + param.name();
        if (raw == null || raw.isEmpty()) {
            if (param.required()) {
                var e = new LinkedHashMap<String, Object>();
                e.put("location", location);
                e.put("message", "path".equals(param.in())
                        ? ValidationMessages.requiredPathParameterMissing()
                        : ValidationMessages.requiredQueryParameterMissing());
                e.put("value", "");
                errors.add(e);
            }
            return;
        }

        var type = param.schema().has("type") ? param.schema().path("type").stringValue() : "string";
        Object coerced;
        switch (type) {
            case "integer" -> {
                try {
                    coerced = Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    addParamError(errors, location, raw, ValidationMessages.invalidInteger());
                    return;
                }
            }
            case "boolean" -> {
                if (TRUE_VALUES.contains(raw)) {
                    coerced = Boolean.TRUE;
                } else if (FALSE_VALUES.contains(raw)) {
                    coerced = Boolean.FALSE;
                } else {
                    addParamError(errors, location, raw, ValidationMessages.invalidBoolean());
                    return;
                }
            }
            default -> coerced = raw;
        }

        // The remaining checks (minimum/maximum/enum/pattern/format) run on the
        // already-coerced value through the same engine the body uses; none of
        // this lockfile's parameters carry them today, but a lockfile bump that
        // adds one is caught here rather than silently skipped.
        SchemaValidator.validateValue(lockfile, param.schema(), location, Json.MAPPER.valueToTree(coerced), errors);
    }

    private static void addParamError(List<Map<String, Object>> errors, String location, String raw, String message) {
        var e = new LinkedHashMap<String, Object>();
        e.put("location", location);
        e.put("message", message);
        e.put("value", raw);
        errors.add(e);
    }

    /// The raw string a `{name}` segment of `template` bound to in
    /// `actualPath` — a plain split-and-compare over the two already-matched
    /// strings, not a Javalin route lookup (see [#validateParam]).
    private static String extractPathSegment(String template, String actualPath, String name) {
        var t = template.split("/", -1);
        var a = actualPath.split("/", -1);
        var wanted = "{" + name + "}";
        for (int i = 0; i < t.length && i < a.length; i++) {
            if (t[i].equals(wanted)) {
                return a[i];
            }
        }
        return null;
    }

    // ── path-template matching (mirrors parity.Coverage#matchesTemplate) ───

    static boolean matchesTemplate(String template, String actualPath) {
        var t = template.split("/", -1);
        var a = actualPath.split("/", -1);
        if (t.length != a.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            var seg = t[i];
            if (seg.startsWith("{") && seg.endsWith("}")) {
                continue;
            }
            if (!seg.equals(a[i])) {
                return false;
            }
        }
        return true;
    }
}
