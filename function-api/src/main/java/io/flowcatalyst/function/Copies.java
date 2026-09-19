package io.flowcatalyst.function;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Defensive-copy helpers shared by every value record in this package
/// (`docs/spec/function-host-core.md` §1: "values are values" — mutating an
/// array passed in, or one read out, never changes the record). Not public:
/// this is implementation plumbing, not part of the surface a function
/// author or the reflection test in `FunctionSignatureTest` sees.
final class Copies {

    private Copies() {
    }

    static byte[] bytes(byte[] value) {
        return value == null ? null : value.clone();
    }

    static Map<String, String> stringMap(Map<String, String> value) {
        if (value == null) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    static Map<String, List<String>> multiMap(Map<String, List<String>> value) {
        if (value == null) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : value.entrySet()) {
            copy.put(entry.getKey(), list(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    static List<String> list(List<String> value) {
        if (value == null) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(value));
    }

    static Set<String> set(Set<String> value) {
        if (value == null) return Set.of();
        return Collections.unmodifiableSet(new LinkedHashSet<>(value));
    }
}
