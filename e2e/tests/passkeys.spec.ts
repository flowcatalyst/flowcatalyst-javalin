// Go screens: frontend/src/pages/ProfilePage.vue (PasskeysSection.vue),
// pages/auth/LoginPage.vue (the "Sign in with a passkey" button on the
// password step).
// Parity scenarios covering the same wire (docs/spec/parity-harness.md):
// the `auth` group's WebAuthn register/authenticate/revoke scenarios.
//
// docs/spec/frontend-e2e.md §3 "passkeys": Playwright's CDP virtual
// authenticator stands in for a real device (`ctap2`, `internal` transport,
// resident keys, user-verified — the brief's exact shape). Every test uses
// its own throwaway principal so a revoked/registered passkey here never
// touches the shared e2e-admin account other spec files log in as.
import { test, expect, loginAndLand, signOutViaUi, ADMIN_EMAIL, ADMIN_PASSWORD } from "../fixtures/admin.js";
import { createPasskeyTestUser, addVirtualAuthenticator } from "../fixtures/passkeys.js";

test.describe("passkeys", () => {
    test("registering, listing, signing in with, and revoking a passkey", async ({ page, browser }) => {
        const admin = await browser.newContext().then((c) => c.newPage());
        await loginAndLand(admin, ADMIN_EMAIL, ADMIN_PASSWORD);
        const user = await createPasskeyTestUser(admin.request);
        await admin.close();

        await addVirtualAuthenticator(page);
        await loginAndLand(page, user.email, user.password);

        // Register from the passkeys section.
        await page.goto("/profile");
        const passkeyName = "E2E Virtual Authenticator";
        await page.getByPlaceholder("Name (e.g. 'My Computer/Phone')").fill(passkeyName);
        await page.getByRole("button", { name: "Add a passkey" }).click();
        await expect(page.getByText("Passkey added.")).toBeVisible();

        // Listed, and it survives a reload (not just the just-created toast).
        await page.reload();
        await expect(page.locator(".passkey-item", { hasText: passkeyName })).toBeVisible();

        // Sign out, then sign in with the passkey instead of the password.
        await signOutViaUi(page);
        await page.goto("/auth/login");
        await page.getByLabel("Email address").fill(user.email);
        await page.getByRole("button", { name: "Continue" }).click();
        await page.getByRole("button", { name: "Sign in with a passkey" }).click();
        await expect(page).toHaveURL(/\/(dashboard|profile)(\?.*)?$/, { timeout: 15_000 });
        const me = await page.request.get("/auth/me");
        expect(me.ok()).toBe(true);
        expect((await me.json()).email).toBe(user.email);

        // Revoke it...
        await page.goto("/profile");
        await page.locator(".passkey-item", { hasText: passkeyName }).getByRole("button", { name: "Remove" }).click();
        await expect(page.getByText("Passkey removed.")).toBeVisible();
        await page.reload();
        await expect(page.getByText("No passkeys registered yet.")).toBeVisible();
        await expect(page.locator(".passkey-item")).toHaveCount(0);

        // ...and sign-in with it now fails: no session gets established, not
        // just an error string (the virtual authenticator still exists, so a
        // silently-still-working credential would otherwise pass this test).
        await signOutViaUi(page);
        await page.goto("/auth/login");
        await page.getByLabel("Email address").fill(user.email);
        await page.getByRole("button", { name: "Continue" }).click();
        await page.getByRole("button", { name: "Sign in with a passkey" }).click();
        await expect(page.locator(".error-message p")).toBeVisible({ timeout: 10_000 });
        await expect(page).toHaveURL(/\/auth\/login/);
        const meAfterRevoke = await page.request.get("/auth/me");
        expect(meAfterRevoke.status()).toBe(401);
    });
});
