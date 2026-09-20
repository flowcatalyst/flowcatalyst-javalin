package io.flowcatalyst.fnhost.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/// sha256 over a desired-state entry's sorted `config` + `secrets` maps
/// (spec `function-context.md` §2: "the reconciler fingerprints each loaded
/// version's settings ... a different fingerprint in a new document ⇒ load a
/// fresh `LoadedFunction`"). Deterministic regardless of the maps' own
/// iteration order (`CONVENTIONS.md` §8: "deterministic maps in wire-byte
/// tests" — here a hash, not a wire test, but the same discipline: sort
/// before hashing).
public final class SettingsFingerprint {

    private SettingsFingerprint() {
    }

    public static String of(Map<String, String> config, Map<String, String> secrets) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("config\n");
        appendSorted(canonical, config);
        canonical.append("secrets\n");
        appendSorted(canonical, secrets);
        return sha256Hex(canonical.toString());
    }

    private static void appendSorted(StringBuilder sb, Map<String, String> map) {
        for (var entry : new TreeMap<>(map).entrySet()) {
            sb.append(entry.getKey().length()).append(':').append(entry.getKey())
                    .append('=')
                    .append(entry.getValue().length()).append(':').append(entry.getValue())
                    .append('\n');
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
