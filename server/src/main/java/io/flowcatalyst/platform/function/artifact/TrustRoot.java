package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/// The Sigstore certificate authorities and transparency-log keys
/// [SignatureVerifier] trusts, each with a validity window (spec
/// `function-artifacts.md` §3.3). Parsed from a Sigstore `trusted_root.json`
/// (`certificateAuthorities[].certChain`, `tlogs[].publicKey`); `ctlogs` and
/// `timestampAuthorities` are read past — this verifier does no SCT check
/// and needs no timestamp authority (spec §4). A constructor argument, not
/// a singleton, so tests bring their own.
public record TrustRoot(List<CertificateAuthority> cas, List<TransparencyLog> tlogs) {

    private static final String PUBLIC_GOOD_RESOURCE = "/function/sigstore-trusted-root.json";

    public TrustRoot {
        cas = List.copyOf(cas);
        tlogs = List.copyOf(tlogs);
    }

    /// One Fulcio certificate-authority generation: the chain from
    /// (excluding) the leaf up to and including the self-signed root, in DER,
    /// and the window it was the active issuer for.
    public record CertificateAuthority(List<byte[]> certChainDer, Instant validFrom, Instant validUntil) {
        public CertificateAuthority {
            Objects.requireNonNull(certChainDer, "certChainDer");
            if (certChainDer.isEmpty()) {
                throw new IllegalArgumentException("certChainDer must not be empty");
            }
            certChainDer = certChainDer.stream().map(byte[]::clone).toList();
            Objects.requireNonNull(validFrom, "validFrom");
        }

        /// Whether `time` falls within `[validFrom, validUntil]` — `validUntil`
        /// `null` means still active.
        public boolean containsAt(Instant time) {
            return !time.isBefore(validFrom) && (validUntil == null || !time.isAfter(validUntil));
        }
    }

    /// One Rekor transparency-log key: its DER SubjectPublicKeyInfo, the
    /// `logId.keyId` Sigstore already computed for it (the sha256 of that
    /// SPKI), and the window it was active for.
    public record TransparencyLog(byte[] keyId, byte[] publicKeyDer, Instant validFrom, Instant validUntil) {
        public TransparencyLog {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(publicKeyDer, "publicKeyDer");
            keyId = keyId.clone();
            publicKeyDer = publicKeyDer.clone();
            Objects.requireNonNull(validFrom, "validFrom");
        }

        @Override
        public byte[] keyId() {
            return keyId.clone();
        }

        @Override
        public byte[] publicKeyDer() {
            return publicKeyDer.clone();
        }

        /// Whether `time` falls within `[validFrom, validUntil]` — `validUntil`
        /// `null` means still active.
        public boolean containsAt(Instant time) {
            return !time.isBefore(validFrom) && (validUntil == null || !time.isAfter(validUntil));
        }
    }

    /// Reads a Sigstore `trusted_root.json` document. Throws unchecked on a
    /// malformed document — this reads the platform's own configuration at
    /// start-up, not attacker input, so failing fast is correct (contrast
    /// [SignatureVerifier#verify], a verification boundary that never throws).
    public static TrustRoot parse(String trustedRootJson) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(trustedRootJson);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("trusted_root.json is not valid JSON", e);
        }
        List<CertificateAuthority> cas = new ArrayList<>();
        for (JsonNode caNode : root.path("certificateAuthorities")) {
            List<byte[]> certs = new ArrayList<>();
            for (JsonNode certNode : caNode.path("certChain").path("certificates")) {
                certs.add(decode(certNode.path("rawBytes").asString()));
            }
            JsonNode validFor = caNode.path("validFor");
            Instant start = Instant.parse(validFor.path("start").asString());
            Instant end = validFor.path("end").isString() ? Instant.parse(validFor.path("end").asString()) : null;
            cas.add(new CertificateAuthority(certs, start, end));
        }
        List<TransparencyLog> tlogs = new ArrayList<>();
        for (JsonNode tlogNode : root.path("tlogs")) {
            byte[] keyId = decode(tlogNode.path("logId").path("keyId").asString());
            byte[] publicKeyDer = decode(tlogNode.path("publicKey").path("rawBytes").asString());
            JsonNode validFor = tlogNode.path("publicKey").path("validFor");
            Instant start = Instant.parse(validFor.path("start").asString());
            Instant end = validFor.path("end").isString() ? Instant.parse(validFor.path("end").asString()) : null;
            tlogs.add(new TransparencyLog(keyId, publicKeyDer, start, end));
        }
        return new TrustRoot(cas, tlogs);
    }

    /// The trust root for the Sigstore public-good instance, committed at
    /// `server/src/main/resources/function/sigstore-trusted-root.json`
    /// (see its adjacent `README.md` for the upstream commit and date).
    /// Refreshing that file is a manual, reviewed change (spec §3.3) — there
    /// is no TUF client here.
    public static TrustRoot sigstorePublicGood() {
        try (InputStream in = TrustRoot.class.getResourceAsStream(PUBLIC_GOOD_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + PUBLIC_GOOD_RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + PUBLIC_GOOD_RESOURCE, e);
        }
    }

    private static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
