package io.flowcatalyst.parity;

import io.flowcatalyst.parity.model.Step;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/// The seven normalisation rules, applied in the spec's order (parity-harness
/// spec §5), to one side's raw [StepRecord] before it is diffed. A pure
/// function: the raw record is untouched (kept for the report), a fresh
/// [Normalised] is returned.
public final class Normaliser {

    /// Rule 3: RFC 3339, whole-string match only (microsecond or second
    /// precision, `Z` or a numeric offset — [io.flowcatalyst.platform.shared.json.Json]'s own shapes).
    private static final Pattern RFC3339 = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$");

    private static final List<String> JWT_TIME_CLAIMS = List.of("iat", "exp", "nbf", "auth_time");

    /// Rule 1's substring form applies only to captured values at least this
    /// long — shorter ones (a status word, a short code) would mask by accident.
    static final int MIN_SUBSTRING_CAPTURE = 8;

    /// The `Expires` cookie attribute (RFC 6265 §4.1.1, an RFC 1123 date —
    /// not RFC 3339, so rule 3 never touches it): matched case-insensitively,
    /// value is everything up to the next `;` or end of string.
    private static final Pattern EXPIRES_ATTR = Pattern.compile("(?i)(Expires=)[^;]*");

    private Normaliser() {
    }

    /// Applies rules 1–7 to `record`, using `vars`'s captures (rule 1) and
    /// `baseUrl` (rule 2), then `step`'s `unordered` (rule 6) and `ignore`
    /// (rule 7).
    public static Normalised normalise(StepRecord record, Vars vars, String baseUrl, Step step) {
        Map<String, String> headers = new LinkedHashMap<>();
        record.headers().forEach((name, value) -> headers.put(name, normaliseHeader(name, value, vars, baseUrl)));

        // A redirect's body and Content-Type are not part of any contract (Go's
        // net/http writes an HTML stub, Javalin a text one); Location and status are.
        if (record.status() >= 300 && record.status() < 400) {
            headers.remove(ComparedHeaders.CONTENT_TYPE);
        }
        JsonNode body = record.status() >= 300 && record.status() < 400
                ? Json.MAPPER.getNodeFactory().stringNode("«redirect»")
                : normaliseNode(bodyOf(record), vars, baseUrl);
        body = applyUnordered(body, step.unordered());
        body = applyIgnore(body, step.ignore());
        return new Normalised(record.status(), headers, body);
    }

    /// `Set-Cookie` is masked outright (rule 5); `Retry-After` collapses to
    /// presence (spec §4: "presence only"); everything else goes through
    /// rules 1–3 as a plain string.
    private static String normaliseHeader(String name, String value, Vars vars, String baseUrl) {
        if (ComparedHeaders.SET_COOKIE.equals(name)) return maskCookie(value);
        if (ComparedHeaders.RETRY_AFTER.equals(name)) return "«present»";
        return normaliseString(value, vars, baseUrl).asString();
    }

    /// Keeps the cookie's name and attributes, masks the value (rule 5) —
    /// the value is itself the JWT captured as `cookie:<name>` in the login
    /// step, compared there once it resurfaces (rule 4), not here — and
    /// masks an `Expires` attribute's value to `«time»` (it is a timestamp,
    /// just not an RFC 3339 one, so rule 3 never reaches it on its own).
    static String maskCookie(String setCookie) {
        int semi = setCookie.indexOf(';');
        String pair = semi < 0 ? setCookie : setCookie.substring(0, semi);
        String rest = semi < 0 ? "" : setCookie.substring(semi);
        int eq = pair.indexOf('=');
        String name = eq < 0 ? pair : pair.substring(0, eq);
        // Attributes compared as a set: RFC 6265 gives their order no meaning, and
        // the two servers' HTTP stacks emit them in different orders.
        List<String> attrs = new ArrayList<>();
        for (String a : rest.split(";")) {
            String t = a.strip();
            if (!t.isEmpty()) attrs.add(t);
        }
        attrs.sort(String.CASE_INSENSITIVE_ORDER);
        String masked = name + "=«cookie»" + (attrs.isEmpty() ? "" : "; " + String.join("; ", attrs));
        return EXPIRES_ATTR.matcher(masked).replaceAll("$1«time»");
    }

    private static JsonNode bodyOf(StepRecord record) {
        if (record.jsonBody() != null) return record.jsonBody();
        if (record.textBody() != null) return Json.MAPPER.getNodeFactory().stringNode(record.textBody());
        return Json.MAPPER.getNodeFactory().stringNode("sha256:" + record.sha256Body());
    }

    private static JsonNode normaliseNode(JsonNode node, Vars vars, String baseUrl) {
        if (node == null || node.isNull() || node.isMissingNode()) return node == null ? Json.MAPPER.getNodeFactory().nullNode() : node;
        if (node.isObject()) {
            ObjectNode out = Json.MAPPER.createObjectNode();
            node.properties().forEach(e -> {
                // Rule 3, numeric form: an epoch-seconds member named like a JWT time
                // claim (introspection echoes exp/iat) is a time, not a value.
                if (JWT_TIME_CLAIMS.contains(e.getKey()) && e.getValue().isNumber()) {
                    out.set(e.getKey(), Json.MAPPER.getNodeFactory().stringNode("«time»"));
                } else {
                    out.set(e.getKey(), normaliseNode(e.getValue(), vars, baseUrl));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.MAPPER.createArrayNode();
            node.forEach(child -> out.add(normaliseNode(child, vars, baseUrl)));
            return out;
        }
        if (node.isString()) {
            return normaliseString(node.asString(), vars, baseUrl);
        }
        return node;
    }

    /// Rule 4 first (a JWS is structurally distinctive and must be compared
    /// as a structure, never collapsed to an opaque sentinel even when its
    /// raw text also happens to equal a captured value — spec §5's own
    /// rationale for rule 4). Otherwise rules 1, 2, 3 in order.
    private static JsonNode normaliseString(String s, Vars vars, String baseUrl) {
        JsonNode jws = tryDecodeJws(s, vars, baseUrl);
        if (jws != null) return jws;

        Map<String, String> labels = vars.labels();
        String whole = labels.get(s);
        if (whole != null) {
            return Json.MAPPER.getNodeFactory().stringNode("«" + whole + "»");
        }
        // Rule 1, substring form: an id or token embedded in a message ("EventType
        // not found: evt_…") or a derived id ("<principalId>-role-0"). Only values
        // long enough that an accidental hit is implausible (a TSID is 17 chars).
        String out = s;
        for (var label : labels.entrySet()) {
            String value = label.getKey();
            if (value.length() >= MIN_SUBSTRING_CAPTURE && out.contains(value)) {
                out = out.replace(value, "«" + label.getValue() + "»");
            }
        }
        out = baseUrl.isEmpty() ? out : out.replace(baseUrl, "«base»");
        if (RFC3339.matcher(out).matches()) {
            out = "«time»";
        }
        return Json.MAPPER.getNodeFactory().stringNode(out);
    }

    /// `{"«jwt»": {"header": …, "claims": …}}`, or `null` when `s` is not a
    /// JWS: three base64url segments, the header segment decoding to a JSON
    /// object carrying `alg`.
    private static JsonNode tryDecodeJws(String s, Vars vars, String baseUrl) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) return null;
        JsonNode header = decodeJwsSegment(parts[0]);
        if (header == null || !header.isObject() || header.get("alg") == null) return null;
        JsonNode claims = decodeJwsSegment(parts[1]);
        if (claims == null || !claims.isObject()) return null;

        JsonNode normHeader = normaliseNode(header, vars, baseUrl);
        ObjectNode normClaims = (ObjectNode) normaliseNode(claims, vars, baseUrl);
        for (String field : JWT_TIME_CLAIMS) {
            if (normClaims.has(field)) normClaims.set(field, Json.MAPPER.getNodeFactory().stringNode("«time»"));
        }
        if (normClaims.has("jti")) normClaims.set("jti", Json.MAPPER.getNodeFactory().stringNode("«id»"));

        ObjectNode result = Json.MAPPER.createObjectNode();
        ObjectNode inner = result.putObject("«jwt»");
        inner.set("header", normHeader);
        inner.set("claims", normClaims);
        return result;
    }

    private static JsonNode decodeJwsSegment(String segment) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(pad(segment));
            JsonNode node = Json.MAPPER.readTree(bytes);
            return node.isObject() ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String pad(String base64Url) {
        int mod = base64Url.length() % 4;
        return mod == 0 ? base64Url : base64Url + "=".repeat(4 - mod);
    }

    /// Rule 6: each `unordered` pointer's array, sorted by its elements'
    /// normalised JSON text (`Map.of` iteration order is unspecified, so this
    /// sorts on the rendered text, not object identity or insertion order).
    private static JsonNode applyUnordered(JsonNode body, List<String> pointers) {
        for (String pointer : pointers) {
            JsonNode at = body.at(pointer);
            if (at instanceof ArrayNode array) {
                List<JsonNode> items = new ArrayList<>();
                array.forEach(items::add);
                items.sort(Comparator.comparing(Json::write));
                array.removeAll();
                items.forEach(array::add);
            }
        }
        return body;
    }

    /// Rule 7: each `ignore` pointer removed from the body. A `*` path
    /// segment means "every array element" (`/items/*/createdBy`), which
    /// plain RFC 6901 has no syntax for; every other segment is a literal
    /// object key or array index.
    private static JsonNode applyIgnore(JsonNode body, List<Step.Ignore> ignores) {
        for (Step.Ignore ignore : ignores) {
            String pointer = ignore.pointer();
            if (pointer == null || pointer.isEmpty() || "/".equals(pointer)) continue;
            List<String> segments = List.of(pointer.substring(1).split("/", -1));
            removeAt(body, segments, 0);
        }
        return body;
    }

    private static void removeAt(JsonNode node, List<String> segments, int index) {
        if (node == null || node.isMissingNode()) return;
        String segment = unescape(segments.get(index));
        boolean last = index == segments.size() - 1;
        if ("*".equals(segment) && node.isArray()) {
            if (last) return; // "*" naming the leaf itself has nothing to remove
            node.forEach(child -> removeAt(child, segments, index + 1));
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (last) {
                obj.remove(segment);
            } else {
                removeAt(obj.get(segment), segments, index + 1);
            }
        } else if (node.isArray()) {
            try {
                int i = Integer.parseInt(segment);
                if (!last) removeAt(node.get(i), segments, index + 1);
            } catch (NumberFormatException ignored) {
                // not an index into this array — nothing to remove
            }
        }
    }

    private static String unescape(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }
}
