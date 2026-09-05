package io.flowcatalyst.parity;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// `${…}` substitution over a scenario step's request tree (parity-harness
/// spec §3): path, query values, header values and every string in the JSON
/// body. Produces fresh values; never mutates the scenario's own [JsonNode]
/// tree, which is shared across both sides' runs of the same step.
public final class Substitution {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private Substitution() {
    }

    /// Replaces every `${name}` in `template` with `vars.resolve(name)`.
    /// `null` passes through unchanged (an absent header/path is not this
    /// class's problem).
    ///
    /// @throws SubstitutionException a `${name}` is not resolvable
    public static String resolve(String template, Vars vars) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(template, last, m.start());
            out.append(vars.resolve(m.group(1)));
            last = m.end();
        }
        out.append(template, last, template.length());
        return out.toString();
    }

    /// [#resolve(String, Vars)] over every value of `map`, keys untouched.
    public static Map<String, String> resolve(Map<String, String> map, Vars vars) {
        if (map.isEmpty()) return map;
        var out = new LinkedHashMap<String, String>();
        map.forEach((k, v) -> out.put(k, resolve(v, vars)));
        return out;
    }

    /// [#resolve(String, Vars)] over every string leaf in a JSON tree,
    /// object/array keys untouched. Returns a fresh tree; scalars other than
    /// strings (numbers, booleans, `null`) are returned as-is since they are
    /// immutable and carry nothing to substitute.
    public static JsonNode resolve(JsonNode node, Vars vars) {
        if (node == null) return null;
        if (node.isString()) {
            return Json.MAPPER.getNodeFactory().stringNode(resolve(node.asString(), vars));
        }
        if (node.isObject()) {
            ObjectNode out = Json.MAPPER.createObjectNode();
            node.properties().forEach(e -> out.set(e.getKey(), resolve(e.getValue(), vars)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.MAPPER.createArrayNode();
            node.forEach(child -> out.add(resolve(child, vars)));
            return out;
        }
        return node;
    }
}
