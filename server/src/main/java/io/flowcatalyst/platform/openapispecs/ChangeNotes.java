package io.flowcatalyst.platform.openapispecs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/// The structural diff stamped onto an archived spec (spec §3 "Change
/// notes"): set differences of `paths` keys, `components.schemas` keys and
/// the verbs dropped from surviving paths; any removal is breaking. Every
/// list is sorted. Persisted as JSONB with these field names; empty lists
/// are omitted on write and tolerated when absent on read (the column is a
/// foreign shape).
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ChangeNotes(
        List<String> addedPaths,
        List<String> removedPaths,
        List<String> addedSchemas,
        List<String> removedSchemas,
        List<String> removedOperations,
        boolean hasBreaking) {

    /// The OpenAPI path-item keys that are operations; anything else
    /// (`parameters`, `summary`, `$ref`) is not a verb and does not count.
    static final List<String> HTTP_VERBS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static final int SAMPLE = 5;

    public ChangeNotes {
        addedPaths = addedPaths == null ? List.of() : List.copyOf(addedPaths);
        removedPaths = removedPaths == null ? List.of() : List.copyOf(removedPaths);
        addedSchemas = addedSchemas == null ? List.of() : List.copyOf(addedSchemas);
        removedSchemas = removedSchemas == null ? List.of() : List.copyOf(removedSchemas);
        removedOperations = removedOperations == null ? List.of() : List.copyOf(removedOperations);
    }

    /// The diff from `prior` to `current` (either may be any JSON; a missing
    /// or non-object `paths` / `components.schemas` counts as no keys).
    public static ChangeNotes diff(JsonNode prior, JsonNode current) {
        Set<String> priorPaths = keys(nested(prior, "paths"));
        Set<String> currentPaths = keys(nested(current, "paths"));
        Set<String> priorSchemas = keys(nested(prior, "components", "schemas"));
        Set<String> currentSchemas = keys(nested(current, "components", "schemas"));

        var removedOperations = new TreeSet<String>();
        for (String path : priorPaths) {
            if (!currentPaths.contains(path)) continue;
            Set<String> before = verbs(nested(prior, "paths", path));
            Set<String> after = verbs(nested(current, "paths", path));
            for (String verb : before) {
                if (!after.contains(verb)) removedOperations.add(verb.toUpperCase(Locale.ROOT) + " " + path);
            }
        }
        List<String> removedPaths = difference(priorPaths, currentPaths);
        List<String> removedSchemas = difference(priorSchemas, currentSchemas);
        boolean breaking = !removedPaths.isEmpty() || !removedSchemas.isEmpty() || !removedOperations.isEmpty();
        return new ChangeNotes(difference(currentPaths, priorPaths), removedPaths,
                difference(currentSchemas, priorSchemas), removedSchemas, List.copyOf(removedOperations), breaking);
    }

    /// No structural change at all.
    public boolean isEmpty() {
        return addedPaths.isEmpty() && removedPaths.isEmpty() && addedSchemas.isEmpty()
                && removedSchemas.isEmpty() && removedOperations.isEmpty();
    }

    /// The human summary for listings (spec §3): counts in a fixed order, a
    /// breaking-changes sentence, then up to five samples of each removal kind.
    public String summary() {
        if (isEmpty()) return "No structural changes (descriptions or examples may differ).";
        var parts = new ArrayList<String>();
        if (!addedPaths.isEmpty()) parts.add("Added " + addedPaths.size() + " path(s)");
        if (!removedPaths.isEmpty()) parts.add("Removed " + removedPaths.size() + " path(s)");
        if (!removedOperations.isEmpty()) parts.add("Removed " + removedOperations.size() + " operation(s)");
        if (!addedSchemas.isEmpty()) parts.add("Added " + addedSchemas.size() + " schema(s)");
        if (!removedSchemas.isEmpty()) parts.add("Removed " + removedSchemas.size() + " schema(s)");
        var summary = new StringBuilder(String.join("; ", parts));
        summary.append(hasBreaking ? ". Contains breaking changes (removals)." : ".");
        var details = new ArrayList<String>();
        if (!removedPaths.isEmpty()) details.add("removed paths: " + sample(removedPaths));
        if (!removedOperations.isEmpty()) details.add("removed ops: " + sample(removedOperations));
        if (!removedSchemas.isEmpty()) details.add("removed schemas: " + sample(removedSchemas));
        if (!details.isEmpty()) summary.append(" — ").append(String.join("; ", details));
        return summary.toString();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String sample(List<String> items) {
        if (items.size() <= SAMPLE) return String.join(", ", items);
        return String.join(", ", items.subList(0, SAMPLE)) + ", … (+" + (items.size() - SAMPLE) + " more)";
    }

    /// `root.a.b…` when every step is an object, else `null`.
    private static JsonNode nested(JsonNode root, String... path) {
        JsonNode cur = root;
        for (String p : path) {
            if (cur == null || !cur.isObject()) return null;
            cur = cur.get(p);
        }
        return cur != null && cur.isObject() ? cur : null;
    }

    private static Set<String> keys(JsonNode object) {
        var out = new TreeSet<String>();
        if (object != null) object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static Set<String> verbs(JsonNode pathItem) {
        var out = new LinkedHashSet<String>();
        if (pathItem == null) return out;
        for (String verb : HTTP_VERBS) {
            if (pathItem.has(verb)) out.add(verb);
        }
        return out;
    }

    private static List<String> difference(Set<String> a, Set<String> b) {
        return a.stream().filter(x -> !b.contains(x)).sorted().toList();
    }
}
