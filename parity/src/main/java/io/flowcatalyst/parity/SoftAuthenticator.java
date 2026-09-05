package io.flowcatalyst.parity;

import com.upokecenter.cbor.CBORObject;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/// A minimal software authenticator for the `"authenticator": "register" |
/// "assert"` scenario steps (parity-harness spec §3): one ES256 key, a
/// random credential id, a signature counter, "none" attestation. It answers
/// a `navigator.credentials.create()` / `.get()` the way a platform
/// authenticator would, so the server's ceremonies run for real.
///
/// A byte-for-byte copy of `server/src/test/java/io/flowcatalyst/platform/passkey/SoftAuthenticator.java`
/// (CONVENTIONS.md: no cross-module test-scope dependency; the harness is not
/// `server`'s test suite). One instance per scenario per side.
final class SoftAuthenticator {

    final KeyPair key;
    final byte[] credentialId;
    long counter;
    /// Flip to sign with a key the server never registered.
    boolean rogue;

    SoftAuthenticator() {
        this(0);
    }

    SoftAuthenticator(long counter) {
        try {
            var gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            this.key = gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.credentialId = new byte[32];
        new SecureRandom().nextBytes(this.credentialId);
        this.counter = counter;
    }

    /// The browser's `PublicKeyCredential` JSON for a `create()` over `options`.
    JsonNode register(JsonNode options, String origin) throws Exception {
        JsonNode pk = options.get("publicKey");
        String challenge = pk.get("challenge").asString();
        String rpId = pk.get("rp").get("id").asString();
        byte[] clientData = clientData("webauthn.create", challenge, origin);
        byte[] authData = concat(sha256(rpId.getBytes(StandardCharsets.UTF_8)), new byte[] {(byte) 0x45},
                counterBytes(), new byte[16], new byte[] {0, (byte) credentialId.length}, credentialId, coseKey());
        CBORObject att = CBORObject.NewMap();
        att.Add("fmt", "none");
        att.Add("attStmt", CBORObject.NewMap());
        att.Add("authData", authData);
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("id", b64url(credentialId));
        out.put("rawId", b64url(credentialId));
        out.put("type", "public-key");
        ObjectNode response = out.putObject("response");
        response.put("clientDataJSON", b64url(clientData));
        response.put("attestationObject", b64url(att.EncodeToBytes()));
        response.putArray("transports").add("internal");
        out.putObject("clientExtensionResults");
        return out;
    }

    /// The browser's `PublicKeyCredential` JSON for a `get()` over `options`;
    /// the counter advances by one each time.
    JsonNode assertion(JsonNode options, String origin, String principalId) throws Exception {
        JsonNode pk = options.get("publicKey");
        String challenge = pk.get("challenge").asString();
        String rpId = pk.get("rpId").asString();
        counter++;
        byte[] clientData = clientData("webauthn.get", challenge, origin);
        byte[] authData = concat(sha256(rpId.getBytes(StandardCharsets.UTF_8)), new byte[] {(byte) 0x05}, counterBytes());
        byte[] toSign = concat(authData, sha256(clientData));
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(rogue ? rogueKey().getPrivate() : key.getPrivate());
        sig.update(toSign);
        byte[] signature = sig.sign();
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("id", b64url(credentialId));
        out.put("rawId", b64url(credentialId));
        out.put("type", "public-key");
        ObjectNode response = out.putObject("response");
        response.put("clientDataJSON", b64url(clientData));
        response.put("authenticatorData", b64url(authData));
        response.put("signature", b64url(signature));
        response.put("userHandle", b64url(principalId.getBytes(StandardCharsets.UTF_8)));
        out.putObject("clientExtensionResults");
        return out;
    }

    private static KeyPair rogueKey() throws Exception {
        var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    private byte[] coseKey() {
        ECPublicKey pub = (ECPublicKey) key.getPublic();
        CBORObject cose = CBORObject.NewMap();
        cose.Add(1, 2);      // kty: EC2
        cose.Add(3, -7);     // alg: ES256
        cose.Add(-1, 1);     // crv: P-256
        cose.Add(-2, fixed(pub.getW().getAffineX()));
        cose.Add(-3, fixed(pub.getW().getAffineY()));
        return cose.EncodeToBytes();
    }

    private byte[] counterBytes() {
        return new byte[] {(byte) (counter >>> 24), (byte) (counter >>> 16), (byte) (counter >>> 8), (byte) counter};
    }

    private static byte[] clientData(String type, String challenge, String origin) {
        ObjectNode cd = Json.MAPPER.createObjectNode();
        cd.put("type", type);
        cd.put("challenge", challenge);
        cd.put("origin", origin);
        cd.put("crossOrigin", false);
        return Json.write(cd).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fixed(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int start = Math.max(0, raw.length - 32);
        int len = raw.length - start;
        System.arraycopy(raw, start, out, 32 - len, len);
        return out;
    }

    static byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    static byte[] concat(byte[]... parts) {
        int n = Arrays.stream(parts).mapToInt(p -> p.length).sum();
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
