package io.flowcatalyst.parity;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// The one environment map handed to both sides (parity-harness spec §2,
/// minus the per-side fields — `FC_DATABASE_URL`, `FC_API_PORT`,
/// `FC_JWT_ISSUER`/`FC_EXTERNAL_BASE_URL`, `FC_WEBAUTHN_ORIGINS` — which
/// [SubprocessSide] / [JavaSide] each fill in for themselves).
public final class ParityEnv {

    private ParityEnv() {
    }

    /// @param jwtKeyPath   the one RSA-2048 PKCS#8 PEM generated for this run ([RsaKeys])
    /// @param appKeyBase64 the one 32-byte app key generated for this run
    /// @param adminEmail   `FC_BOOTSTRAP_ADMIN_*` (Go's envcfg name) / `FLOWCATALYST_BOOTSTRAP_ADMIN_*`
    ///                      (`Seeder`'s Java name) — both set to the same value; the anchor already
    ///                      exists by the time either side's seeder runs (`fcdev init` created it), so
    ///                      this only matters if that ever changes
    public static Map<String, String> baseEnv(Path jwtKeyPath, String appKeyBase64, String adminEmail, String adminPassword) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FC_PLATFORM_ENABLED", "true");
        env.put("FC_ROUTER_ENABLED", "false");
        env.put("FC_SCHEDULER_ENABLED", "false");
        env.put("FC_SCHEDULED_JOB_ENABLED", "false");
        env.put("FC_STREAM_PROCESSOR_ENABLED", "false");
        env.put("FC_OUTBOX_ENABLED", "false");
        env.put("FC_MCP_ENABLED", "false");
        env.put("FC_STANDBY_ENABLED", "false");
        env.put("FC_ALB_ENABLED", "false");
        env.put("FC_JWT_SIGNING_KEY_PATH", jwtKeyPath.toString());
        env.put("FLOWCATALYST_APP_KEY", appKeyBase64);
        env.put("FC_WEBAUTHN_RP_ID", "127.0.0.1"); // the sides serve on 127.0.0.1; yubico requires the origin host to match the RP id
        env.put("FC_BOOTSTRAP_ADMIN_EMAIL", adminEmail);
        env.put("FC_BOOTSTRAP_ADMIN_PASSWORD", adminPassword);
        env.put("FLOWCATALYST_BOOTSTRAP_ADMIN_EMAIL", adminEmail);
        env.put("FLOWCATALYST_BOOTSTRAP_ADMIN_PASSWORD", adminPassword);
        return env;
    }
}
