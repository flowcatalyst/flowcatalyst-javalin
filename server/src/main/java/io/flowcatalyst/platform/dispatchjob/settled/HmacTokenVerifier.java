package io.flowcatalyst.platform.dispatchjob.settled;

import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/// Signs and verifies the per-job HMAC bearer token the scheduler mints for
/// `/api/dispatch/process` and the router forwards for `/api/dispatch/settled`
/// (dispatch-seam spec §2 "`AuthToken`'s double duty", §6). Byte-identical to
/// Go's `scheduler.DispatchAuthService` (`internal/platform/scheduler/auth.go`):
///
///   - secret = HKDF-SHA256(ikm = `FLOWCATALYST_APP_KEY`, salt = none,
///     info = `"fc-dispatch-auth"`, length = 32) — Go's
///     `dispatchAuthSecret()` (`internal/server/subsystems.go:119-131`), so a
///     token minted by either side verifies on the other.
///   - token = lowercase-hex HMAC-SHA256(secret, jobId).
///   - [#verify] compares in constant time.
///
/// Uses the same `FLOWCATALYST_APP_KEY` env var Java's field encryption
/// already reads ([io.flowcatalyst.platform.shared.encryption.Encryption])
/// — no new env var, per this unit's ruling. `KDF`/`HKDFParameterSpec` are
/// the JDK 24+ HKDF API (JEP 478); no external crypto library needed.
public final class HmacTokenVerifier {

    private static final byte[] INFO = "fc-dispatch-auth".getBytes(StandardCharsets.UTF_8);
    private static final int SECRET_LENGTH = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    private HmacTokenVerifier(byte[] secret) {
        this.secret = secret;
    }

    /// Derives the verifier from `FLOWCATALYST_APP_KEY`'s raw value (the same
    /// string [io.flowcatalyst.server.Env#appKey()] carries — not
    /// base64-decoded first; Go's HKDF input is the raw env var bytes too).
    ///
    /// @throws IllegalArgumentException `appKey` is null or blank
    public static HmacTokenVerifier fromAppKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("FLOWCATALYST_APP_KEY is not set; cannot derive dispatch-auth secret");
        }
        try {
            KDF hkdf = KDF.getInstance("HKDF-SHA256");
            HKDFParameterSpec spec = HKDFParameterSpec.ofExtract()
                    .addIKM(appKey.getBytes(StandardCharsets.UTF_8))
                    .thenExpand(INFO, SECRET_LENGTH);
            return new HmacTokenVerifier(hkdf.deriveData(spec));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot derive dispatch-auth secret", e);
        }
    }

    /// The hex HMAC-SHA256(secret, jobId) — what the scheduler mints as `authToken`.
    public String sign(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(jobId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign dispatch-auth token", e);
        }
    }

    /// Constant-time check that `token` is [#sign]'s value for `jobId`.
    public boolean verify(String jobId, String token) {
        if (token == null) return false;
        byte[] expected = sign(jobId).getBytes(StandardCharsets.US_ASCII);
        byte[] given = token.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }
}
