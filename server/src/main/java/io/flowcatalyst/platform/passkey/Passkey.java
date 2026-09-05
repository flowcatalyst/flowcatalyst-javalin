package io.flowcatalyst.platform.passkey;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/// One row of `webauthn_credentials` (`docs/spec/auth-identity.md` §3.5,
/// §7.6): the authenticator-issued credential id, the COSE public key, the
/// signature counter (ruling I-Q13: persisted and checked), the user's
/// label. `passkey_data` keeps Go's `webauthn.Credential` JSON shape
/// verbatim so rows written by either implementation read on both; a
/// legacy `{"cred":…}` blob is reported, never read.
public record Passkey(String id, String principalId, byte[] credentialId, byte[] publicKeyCose, long signCount,
                      List<String> transports, String attestationType, byte[] aaguid, boolean userVerified,
                      boolean backupEligible, boolean backupState, String name, Instant createdAt, Instant lastUsedAt)
        implements HasId {

    public Passkey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(publicKeyCose, "publicKeyCose");
        transports = transports == null ? List.of() : List.copyOf(transports);
        attestationType = attestationType == null ? "none" : attestationType;
        aaguid = aaguid == null ? new byte[16] : aaguid;
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /// A freshly registered credential.
    public static Passkey register(String principalId, byte[] credentialId, byte[] publicKeyCose, long signCount,
                                   List<String> transports, byte[] aaguid, boolean userVerified, boolean backupEligible,
                                   boolean backupState, String name, Instant now) {
        return new Passkey(EntityType.WEBAUTHN_CREDENTIAL.generate(), principalId, credentialId, publicKeyCose, signCount,
                transports, "none", aaguid, userVerified, backupEligible, backupState, name, now, null);
    }

    /// A successful assertion: the counter moves forward and the use is stamped.
    public Passkey authenticated(long newSignCount, boolean userVerifiedNow, boolean backedUpNow, Instant now) {
        return new Passkey(id, principalId, credentialId, publicKeyCose, newSignCount, transports, attestationType, aaguid,
                userVerifiedNow, backupEligible, backedUpNow, name, createdAt, now);
    }

    /// A `{"cred":…}` blob from the previous library: no `id`, no `publicKey`.
    public static boolean isLegacy(JsonNode data) {
        return data != null && data.isObject() && data.has("cred") && !data.has("id") && !data.has("publicKey");
    }

    /// Go's `webauthn.Credential` JSON: byte fields as standard base64.
    public ObjectNode toStoredJson() {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("id", b64(credentialId));
        n.put("publicKey", b64(publicKeyCose));
        n.put("attestationType", attestationType);
        var t = n.putArray("transport");
        transports.forEach(t::add);
        ObjectNode flags = n.putObject("flags");
        flags.put("userPresent", true);
        flags.put("userVerified", userVerified);
        flags.put("backupEligible", backupEligible);
        flags.put("backupState", backupState);
        ObjectNode auth = n.putObject("authenticator");
        auth.put("AAGUID", b64(aaguid));
        auth.put("signCount", signCount);
        auth.put("cloneWarning", false);
        auth.put("attachment", "");
        return n;
    }

    /// The row back into an entity; `null` for a legacy blob.
    public static Passkey fromStoredJson(String id, String principalId, byte[] credentialIdColumn, JsonNode data, String name,
                                         Instant createdAt, Instant lastUsedAt) {
        if (isLegacy(data) || data == null || !data.has("publicKey")) {
            return null;
        }
        byte[] credId = data.has("id") && !data.get("id").asString().isEmpty() ? unb64(data.get("id").asString()) : credentialIdColumn;
        byte[] cose = unb64(data.get("publicKey").asString());
        JsonNode auth = data.path("authenticator");
        JsonNode flags = data.path("flags");
        var transports = new java.util.ArrayList<String>();
        if (data.has("transport") && data.get("transport").isArray()) {
            data.get("transport").forEach(x -> transports.add(x.asString()));
        }
        return new Passkey(id, principalId, credId, cose, auth.path("signCount").asLong(0), transports,
                data.path("attestationType").asString("none"),
                auth.has("AAGUID") && !auth.get("AAGUID").asString().isEmpty() ? unb64(auth.get("AAGUID").asString()) : null,
                flags.path("userVerified").asBoolean(false), flags.path("backupEligible").asBoolean(false),
                flags.path("backupState").asBoolean(false), name, createdAt, lastUsedAt);
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }
}
