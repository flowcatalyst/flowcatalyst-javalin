import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { TOTP, Secret } from "otpauth";
import { unique } from "./clientScoped.js";

/// A plain internal-auth principal on an unmapped email domain: self-service
/// 2FA enrollment allows both TOTP and EMAIL_PIN (the backend's `allMethods`
/// fallback — `internal/platform/auth/login/twofactor_selfservice.go`), but
/// the domain has no email-domain-mapping row, so login-time "remember this
/// device" is never offered (`RememberDeviceEnabled` requires a mapping).
/// Good enough for enroll / challenge / recovery-code / admin-reset flows —
/// only the "trust this device" flow needs [createRememberDeviceUser] below.
export interface TwoFactorTestUser {
    id: string;
    email: string;
    password: string;
}

const PASSWORD = "Bergen-Compass-8814!";

export async function createTwoFactorTestUser(request: APIRequestContext): Promise<TwoFactorTestUser> {
    const email = `${unique("e2e-2fa")}@example.com`;
    const res = await request.post("/api/principals/users", {
        data: { email, name: "E2E 2FA User", password: PASSWORD, scope: "ANCHOR" },
    });
    if (!res.ok()) {
        throw new Error(`createTwoFactorTestUser: POST /api/principals/users -> ${res.status()} ${await res.text()}`);
    }
    const user = (await res.json()) as { id: string };
    return { id: user.id, email, password: PASSWORD };
}

/// A principal on a domain whose email-domain mapping has
/// `rememberDeviceEnabled: true` — the one setup a plain unmapped-domain user
/// can't exercise. Creates an INTERNAL identity provider + a mapping for a
/// fresh domain (`POST /api/identity-providers`, `POST
/// /api/email-domain-mappings` — the "authentication admin" group owns the
/// UI for these; this only needs the wire, as setup for the 2FA flow).
export async function createRememberDeviceUser(request: APIRequestContext): Promise<TwoFactorTestUser> {
    const idpCode = unique("e2e-2fa-idp");
    const idpRes = await request.post("/api/identity-providers", {
        data: { code: idpCode, name: `E2E 2FA IdP ${idpCode}`, type: "INTERNAL", oidcMultiTenant: false }, // required by the lockfile on both sides
    });
    if (!idpRes.ok()) {
        throw new Error(`createRememberDeviceUser: POST /api/identity-providers -> ${idpRes.status()} ${await idpRes.text()}`);
    }
    const idp = (await idpRes.json()) as { id: string };

    const domain = `${unique("e2e-2fa-remember")}.test`;
    const mappingRes = await request.post("/api/email-domain-mappings", {
        data: {
            emailDomain: domain,
            identityProviderId: idp.id,
            scopeType: "ANCHOR",
            require2fa: false,
            allowed2faMethods: ["TOTP"],
            rememberDeviceEnabled: true,
            rememberDeviceDays: 30,
        },
    });
    if (!mappingRes.ok()) {
        throw new Error(`createRememberDeviceUser: POST /api/email-domain-mappings -> ${mappingRes.status()} ${await mappingRes.text()}`);
    }

    const email = `${unique("e2e-user")}@${domain}`;
    const userRes = await request.post("/api/principals/users", {
        data: { email, name: "E2E Remember-Device User", password: PASSWORD, scope: "ANCHOR" },
    });
    if (!userRes.ok()) {
        throw new Error(`createRememberDeviceUser: POST /api/principals/users -> ${userRes.status()} ${await userRes.text()}`);
    }
    const user = (await userRes.json()) as { id: string };
    return { id: user.id, email, password: PASSWORD };
}

/// Drives `TwoFactorSection.vue` -> `TwoFactorSetup.vue` on an already-loaded
/// `/profile` page: opens "Add a method", picks the authenticator-app option,
/// reads the plain-text secret, computes and submits the current code, and
/// captures the one-time recovery codes before dismissing them. Leaves the
/// page back on the (refreshed) profile with TOTP enrolled.
export async function enrollTotpViaProfile(page: Page): Promise<{ secret: string; recoveryCodes: string[]; enrollCode: string }> {
    await page.goto("/profile");
    await page.getByRole("button", { name: "Add a method" }).click();
    await page.getByRole("button", { name: "Use an authenticator app" }).click();

    const secret = (await page.locator(".tfa-secret code").textContent())?.trim();
    if (!secret) throw new Error("enrollTotpViaProfile: no TOTP secret rendered");

    const enrollCode = totpCodeFor(secret);
    await page.locator(".tfa-setup .tfa-input").fill(enrollCode);
    await page.locator(".tfa-setup").getByRole("button", { name: "Verify" }).click();

    // The recovery-code list renders once the confirm round-trip resolves —
    // `allTextContents()` doesn't itself wait, so wait for the stage change
    // first or a same-tick empty read races the network call.
    await expect(page.getByRole("button", { name: "I've saved them — continue" })).toBeVisible();
    const recoveryCodes = await page.locator(".tfa-recovery li code").allTextContents();
    expect(recoveryCodes.length, "enrollTotpViaProfile: no recovery codes shown").toBeGreaterThan(0);

    await page.getByRole("button", { name: "I've saved them — continue" }).click();
    // TwoFactorSection's onAdded() refresh — wait for the enrolled row instead
    // of a fixed delay.
    await expect(page.locator(".tfa-row", { hasText: "Authenticator app" })).toBeVisible();

    return { secret, recoveryCodes, enrollCode };
}

/// Computes the current 6-digit TOTP code for a base32 secret as rendered by
/// `TwoFactorSetup.vue`'s `<code>{{ totp.secret }}</code>` — the same
/// algorithm (SHA1/6-digits/30s step) the server's TOTP issuer uses.
export function totpCodeFor(base32Secret: string): string {
    const totp = new TOTP({
        secret: Secret.fromBase32(base32Secret.replace(/\s+/g, "")),
        algorithm: "SHA1",
        digits: 6,
        period: 30,
    });
    return totp.generate();
}

/// A code for `secret` that differs from `avoidCode` — the server's replay
/// guard (`MfaRepository#confirm`/`advanceLastUsed` stamping `lastUsedAt` to
/// the step just accepted) rejects a second presentation of the same
/// 30-second step, so a challenge immediately following an enrollment or a
/// prior verify must wait for the step to roll over rather than reusing
/// whatever `totpCodeFor` returns right now.
export async function totpCodeAfter(secret: string, avoidCode: string): Promise<string> {
    const deadline = Date.now() + 35_000;
    for (;;) {
        const code = totpCodeFor(secret);
        if (code !== avoidCode) return code;
        if (Date.now() >= deadline) {
            throw new Error("totpCodeAfter: TOTP step never advanced past the code to avoid");
        }
        await new Promise((r) => setTimeout(r, 1_000));
    }
}
