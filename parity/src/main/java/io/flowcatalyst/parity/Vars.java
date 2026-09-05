package io.flowcatalyst.parity;

import io.flowcatalyst.platform.auth.mfa.Totp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// One side's captured values and built-ins (parity-harness spec §3). Built
/// once per side per scenario; every value a side's responses can be
/// compared "by role" instead of "by value" (spec §5 rule 1) lives here.
///
/// The built-ins named `${client.id}` / `${app.id}` / `${admin.id}` and
/// `${run}` are the same text on both sides (read from `seed` before the
/// database was cloned, or generated once per harness run); every captured
/// value is per side, since Go and Java mint their own ids/tokens.
public final class Vars {

    private final Map<String, String> captures = new LinkedHashMap<>();

    private final String adminEmail;
    private final String adminPassword;
    private final String run;
    private final String clientId;
    private final String appId;
    private final String adminId;
    private final String pkceVerifier;
    private final String pkceChallenge;

    /// Every value captured on this side across the whole run, value → the name
    /// it was captured under. Shared by every scenario's [Vars] of one side so
    /// rule 1 also masks an id a *previous* scenario created (the clones are
    /// mutated by every scenario in turn, and an unfiltered list shows them all).
    private final Map<String, String> runLabels;

    public Vars(String adminEmail, String adminPassword, String run, String clientId, String appId, String adminId) {
        this(adminEmail, adminPassword, run, clientId, appId, adminId, new LinkedHashMap<>());
    }

    public Vars(String adminEmail, String adminPassword, String run, String clientId, String appId, String adminId,
                Map<String, String> runLabels) {
        this.runLabels = Objects.requireNonNull(runLabels, "runLabels");
        this.adminEmail = Objects.requireNonNull(adminEmail, "adminEmail");
        this.adminPassword = Objects.requireNonNull(adminPassword, "adminPassword");
        this.run = Objects.requireNonNull(run, "run");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.appId = Objects.requireNonNull(appId, "appId");
        this.adminId = Objects.requireNonNull(adminId, "adminId");
        this.pkceVerifier = generateVerifier();
        this.pkceChallenge = s256(pkceVerifier);
    }

    /// Records a captured value under `name`, overwriting any earlier capture of the same name
    /// (a scenario that captures the same name twice wants the latest one).
    public void capture(String name, String value) {
        captures.put(name, Objects.requireNonNull(value, "value"));
        runLabels.put(value, name);
    }

    /// value → capture name, for normalisation rule 1: this scenario's own
    /// captures win, then anything captured earlier in the run on this side.
    public Map<String, String> labels() {
        Map<String, String> out = new LinkedHashMap<>(runLabels);
        captures.forEach((name, value) -> out.put(value, name));
        return out;
    }

    public Optional<String> captured(String name) {
        return Optional.ofNullable(captures.get(name));
    }

    /// Every value captured so far on this side — what normalisation rule 1
    /// (parity-harness spec §5) replaces with `«name»`.
    public Map<String, String> captures() {
        return Map.copyOf(captures);
    }

    /// Resolves one `${…}` name to its text. Built-ins first, then the
    /// dynamic forms (`totp:`, `pkce.`, `b64url:`), then a plain capture.
    ///
    /// @throws SubstitutionException `key` is none of the above
    public String resolve(String key) {
        return switch (key) {
            case "admin.email" -> adminEmail;
            case "admin.password" -> adminPassword;
            case "run" -> run;
            case "client.id" -> clientId;
            case "app.id" -> appId;
            case "admin.id" -> adminId;
            case "pkce.verifier" -> pkceVerifier;
            case "pkce.challenge" -> pkceChallenge;
            default -> resolveDynamic(key);
        };
    }

    private String resolveDynamic(String key) {
        if (key.startsWith("totp:")) {
            // `totp:<var>` or `totp:<var>:<step offset>` — the offset (-1, 0, +1) picks
            // an adjacent 30-second step, all inside the ±1 window both sides accept, so
            // two steps of one scenario can present two different codes without waiting.
            String rest = key.substring("totp:".length());
            int colon = rest.indexOf(':');
            String secretVar = colon < 0 ? rest : rest.substring(0, colon);
            long offset = colon < 0 ? 0 : Long.parseLong(rest.substring(colon + 1));
            String secret = captured(secretVar).orElseThrow(() -> new SubstitutionException(key));
            return Totp.code(secret, Totp.stepOf(Instant.now()) + offset);
        }
        if (key.startsWith("b64url:")) {
            String var = key.substring("b64url:".length());
            String value = captured(var).orElseThrow(() -> new SubstitutionException(key));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
        return captured(key).orElseThrow(() -> new SubstitutionException(key));
    }

    /// A fresh RFC 7636 code verifier: 43 characters from the unreserved
    /// alphabet, the minimum (and, here, only) length the spec allows.
    private static String generateVerifier() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        var random = new SecureRandom();
        var sb = new StringBuilder(43);
        for (int i = 0; i < 43; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /// RFC 7636 `S256`: `BASE64URL-ENCODE(SHA256(ASCII(verifier)))`, no padding.
    private static String s256(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
