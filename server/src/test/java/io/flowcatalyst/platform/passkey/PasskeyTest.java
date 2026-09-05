package io.flowcatalyst.platform.passkey;

import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/auth-identity.md` §3.5 and §7.6: the stored blob is Go's
/// `webauthn.Credential` JSON, byte fields as standard base64, and a
/// legacy `{"cred":…}` blob is recognised, never read.
class PasskeyTest {

    @Test
    void theStoredJsonIsGosShapeAndReadsBackToTheSameCredential() {
        byte[] credId = {1, 2, 3, 4};
        byte[] cose = {(byte) 0xA5, 1, 2, 3, 38};
        byte[] aaguid = new byte[16];
        aaguid[0] = 9;
        var p = Passkey.register("prn_1", credId, cose, 7, List.of("internal", "hybrid"), aaguid, true, true, false, "Laptop",
                Instant.parse("2026-09-05T12:00:00Z"));
        JsonNode j = p.toStoredJson();
        assertThat(j.get("id").asString()).isEqualTo(Base64.getEncoder().encodeToString(credId));
        assertThat(j.get("publicKey").asString()).isEqualTo(Base64.getEncoder().encodeToString(cose));
        assertThat(j.get("attestationType").asString()).isEqualTo("none");
        assertThat(j.get("transport").toString()).isEqualTo("[\"internal\",\"hybrid\"]");
        assertThat(j.get("flags").get("userPresent").asBoolean()).isTrue();
        assertThat(j.get("flags").get("backupEligible").asBoolean()).isTrue();
        assertThat(j.get("authenticator").get("signCount").asLong()).isEqualTo(7);
        assertThat(j.get("authenticator").get("AAGUID").asString()).isEqualTo(Base64.getEncoder().encodeToString(aaguid));
        assertThat(j.get("authenticator").get("cloneWarning").asBoolean()).isFalse();

        Passkey back = Passkey.fromStoredJson(p.id(), "prn_1", credId, j, "Laptop", p.createdAt(), null);
        assertThat(back).isNotNull();
        assertThat(back.credentialId()).isEqualTo(credId);
        assertThat(back.publicKeyCose()).isEqualTo(cose);
        assertThat(back.signCount()).isEqualTo(7);
        assertThat(back.transports()).containsExactly("internal", "hybrid");
        assertThat(back.aaguid()).isEqualTo(aaguid);
        assertThat(back.id()).startsWith("pkc_");
    }

    @Test
    void aGoWrittenBlobWithOnlyTheCoreFieldsReads() {
        // What go-webauthn writes for a "none" attestation with no transports.
        JsonNode go = Json.MAPPER.readTree("{\"id\":\"AQID\",\"publicKey\":\"pQEC\",\"attestationType\":\"none\",\"transport\":null,"
                + "\"flags\":{\"userPresent\":true,\"userVerified\":false,\"backupEligible\":false,\"backupState\":false},"
                + "\"authenticator\":{\"AAGUID\":\"AAAAAAAAAAAAAAAAAAAAAA==\",\"signCount\":3,\"cloneWarning\":false,\"attachment\":\"\"}}");
        Passkey p = Passkey.fromStoredJson("pkc_1", "prn_1", new byte[] {1, 2, 3}, go, null, Instant.now(), null);
        assertThat(p).isNotNull();
        assertThat(p.credentialId()).isEqualTo(new byte[] {1, 2, 3});
        assertThat(p.signCount()).isEqualTo(3);
        assertThat(p.transports()).isEmpty();
        assertThat(p.userVerified()).isFalse();
    }

    @Test
    void aLegacyBlobIsRecognisedAndNeverRead() {
        JsonNode legacy = Json.MAPPER.readTree("{\"cred\":{\"cred_id\":\"abc\",\"cred\":{\"type_\":\"ES256\"}},\"verified\":true}");
        assertThat(Passkey.isLegacy(legacy)).isTrue();
        assertThat(Passkey.fromStoredJson("pkc_2", "prn_1", new byte[0], legacy, null, Instant.now(), null)).isNull();
        JsonNode modern = Json.MAPPER.readTree("{\"id\":\"AQ==\",\"publicKey\":\"AQ==\"}");
        assertThat(Passkey.isLegacy(modern)).isFalse();
    }

    @Test
    void anAssertionMovesTheCounterAndStampsTheUse() {
        var p = Passkey.register("prn_1", new byte[] {1}, new byte[] {2}, 1, List.of(), null, false, false, false, null,
                Instant.parse("2026-09-05T12:00:00Z"));
        Instant t = Instant.parse("2026-09-05T13:00:00Z");
        var used = p.authenticated(5, true, true, t);
        assertThat(used.signCount()).isEqualTo(5);
        assertThat(used.lastUsedAt()).isEqualTo(t);
        assertThat(used.userVerified()).isTrue();
        assertThat(used.backupState()).isTrue();
        assertThat(used.id()).isEqualTo(p.id());
    }
}
