import type { APIRequestContext, CDPSession, Page } from "@playwright/test";
import { unique } from "./clientScoped.js";

/// A plain internal-auth principal for the passkeys group — its own file per
/// the brief (`docs/process/briefs/2026-09-06-frontend-e2e-groups.md`) so
/// this agent's fixture additions don't collide with the other agent's.
export interface PasskeyTestUser {
    id: string;
    email: string;
    password: string;
}

export async function createPasskeyTestUser(request: APIRequestContext): Promise<PasskeyTestUser> {
    const email = `${unique("e2e-passkey")}@example.com`;
    const password = "Reykjavik-Fjord-2291!";
    const res = await request.post("/api/principals/users", {
        data: { email, name: "E2E Passkey User", password, scope: "ANCHOR" },
    });
    if (!res.ok()) {
        throw new Error(`createPasskeyTestUser: POST /api/principals/users -> ${res.status()} ${await res.text()}`);
    }
    const user = (await res.json()) as { id: string };
    return { id: user.id, email, password };
}

/// Enables a CTAP2 virtual authenticator on `page`'s CDP session — spec §3
/// "passkeys": `WebAuthn.enable` + `addVirtualAuthenticator` with resident
/// keys and user verification, so `@simplewebauthn/browser`'s
/// `navigator.credentials.create()/.get()` calls resolve without a real
/// device. Returns the CDP session so a test can remove the authenticator
/// (simulating "no passkey on this device") if it needs to.
export async function addVirtualAuthenticator(page: Page): Promise<{ client: CDPSession; authenticatorId: string }> {
    const client = await page.context().newCDPSession(page);
    await client.send("WebAuthn.enable");
    const { authenticatorId } = await client.send("WebAuthn.addVirtualAuthenticator", {
        options: {
            protocol: "ctap2",
            transport: "internal",
            hasResidentKey: true,
            hasUserVerification: true,
            isUserVerified: true,
        },
    });
    return { client, authenticatorId };
}
