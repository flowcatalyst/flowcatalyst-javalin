import { randomBytes } from "node:crypto";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import path from "node:path";

const execFileAsync = promisify(execFile);

/// One fresh RSA-2048 PKCS#8 PEM per run (brief §"Starting a side"):
/// `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` writes
/// PKCS#8 by default. Returns the path it wrote to.
export async function generateJwtSigningKey(scratchDir: string): Promise<string> {
    const out = path.join(scratchDir, "jwt-signing-key.pem");
    await execFileAsync("openssl", [
        "genpkey",
        "-algorithm",
        "RSA",
        "-pkeyopt",
        "rsa_keygen_bits:2048",
        "-out",
        out,
    ]);
    return out;
}

/// 32 random bytes, standard base64 with padding — `FLOWCATALYST_APP_KEY`'s
/// wire shape (`Encryption.generateKey()`'s reading, mirrored here so the
/// e2e run doesn't depend on either binary to mint one).
export function generateAppKey(): string {
    return randomBytes(32).toString("base64");
}

export interface SideEnv {
    apiPort: number;
    metricsPort: number;
    embeddedDbPort: number;
    baseUrl: string;
    scratchDir: string;
    jwtSigningKeyPath: string;
    appKey: string;
    adminEmail: string;
    adminPassword: string;
}

/// The shared env both `fcdev`s start with (spec §2): a per-run signing
/// key and app key, WebAuthn bound to this side's own origin, no SMTP (so
/// mail is logged, not sent — §4), and `FC_JWT_ISSUER` pointed at this
/// side's own base URL so a minted password-reset link lands on the SPA
/// this side actually serves.
export function sharedEnv(e: SideEnv): NodeJS.ProcessEnv {
    return {
        FC_JWT_SIGNING_KEY_PATH: e.jwtSigningKeyPath,
        FLOWCATALYST_APP_KEY: e.appKey,
        FC_WEBAUTHN_RP_ID: "localhost",
        FC_WEBAUTHN_ORIGINS: e.baseUrl,
        FC_JWT_ISSUER: e.baseUrl,
        FC_API_PORT: String(e.apiPort),
        FC_METRICS_PORT: String(e.metricsPort),
        // `fcdev start`'s own Seeder run creates the anchor admin on a
        // truly fresh database (`DevBootstrap.seedAdminDefaults` /
        // `setEnvDefault(seed.EnvBootstrapEmail, ...)`) — before `fcdev
        // init` ever runs. `init`'s own `--admin-email`/`--admin-password`
        // (env alias `FC_BOOTSTRAP_ADMIN_EMAIL`, a *different* variable —
        // see `docs/spec/fcdev-commands.md` §1) only takes effect when no
        // anchor exists yet, so on our reset-then-start-then-init sequence
        // it always finds one already there and skips creation
        // (`InitCommand`'s own doc comment says as much). Setting these
        // two — the Seeder's actual variable name — makes `start`'s own
        // seeding mint our chosen identity directly, so the two admins
        // never diverge.
        FLOWCATALYST_BOOTSTRAP_ADMIN_EMAIL: e.adminEmail,
        FLOWCATALYST_BOOTSTRAP_ADMIN_PASSWORD: e.adminPassword,
    };
}
