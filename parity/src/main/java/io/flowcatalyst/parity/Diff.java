package io.flowcatalyst.parity;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/// A structural diff of two [Normalised] step records (parity-harness spec
/// §6): status, then the compared headers, then the body, one [DiffEntry]
/// per point of disagreement. Missing entirely on one side is marked
/// [#ABSENT] — distinct from a JSON `null`, which renders as the text
/// `"null"`.
public final class Diff {

    /// The sentinel for "this pointer resolved to nothing on this side" —
    /// never a value either side's JSON could actually contain (the guillemets
    /// are reserved, spec §5, for every normalisation sentinel).
    public static final String ABSENT = "«absent»";

    private Diff() {
    }

    public static List<DiffEntry> compare(Normalised go, Normalised javaSide) {
        List<DiffEntry> out = new ArrayList<>();
        if (go.status() != javaSide.status()) {
            out.add(new DiffEntry("/status", String.valueOf(go.status()), String.valueOf(javaSide.status())));
        }

        var headerNames = new TreeSet<String>();
        headerNames.addAll(go.headers().keySet());
        headerNames.addAll(javaSide.headers().keySet());
        for (String name : headerNames) {
            String g = go.headers().get(name);
            String j = javaSide.headers().get(name);
            if (!Objects.equals(g, j)) {
                out.add(new DiffEntry("/headers/" + name, g == null ? ABSENT : g, j == null ? ABSENT : j));
            }
        }

        walk(go.body(), javaSide.body(), "", out);
        return List.copyOf(out);
    }

    private static void walk(JsonNode a, JsonNode b, String pointer, List<DiffEntry> out) {
        boolean aMissing = a == null || a.isMissingNode();
        boolean bMissing = b == null || b.isMissingNode();
        if (!aMissing && !bMissing && a.equals(b)) return;

        if (!aMissing && !bMissing && a.isObject() && b.isObject()) {
            var keys = new TreeSet<String>();
            a.properties().forEach(e -> keys.add(e.getKey()));
            b.properties().forEach(e -> keys.add(e.getKey()));
            for (String key : keys) {
                JsonNode av = a.has(key) ? a.get(key) : MissingNode.getInstance();
                JsonNode bv = b.has(key) ? b.get(key) : MissingNode.getInstance();
                walk(av, bv, pointer + "/" + escape(key), out);
            }
            return;
        }
        if (!aMissing && !bMissing && a.isArray() && b.isArray()) {
            int n = Math.max(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                JsonNode av = i < a.size() ? a.get(i) : MissingNode.getInstance();
                JsonNode bv = i < b.size() ? b.get(i) : MissingNode.getInstance();
                walk(av, bv, pointer + "/" + i, out);
            }
            return;
        }
        out.add(new DiffEntry(pointer.isEmpty() ? "/" : pointer,
                aMissing ? ABSENT : render(a), bMissing ? ABSENT : render(b)));
    }

    private static String render(JsonNode n) {
        return n.isString() ? n.asString() : Json.write(n);
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }
}
