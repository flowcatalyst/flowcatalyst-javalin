// Go screens: frontend/src/pages/ProfilePage.vue (TwoFactorSection.vue,
// TwoFactorSetup.vue), pages/auth/LoginPage.vue (TwoFactorChallenge.vue),
// pages/users/UserDetailBody.vue (admin "Reset 2FA" action).
// Parity scenarios covering the same wire (docs/spec/parity-harness.md):
// the `auth` group's 2FA enroll/challenge/recovery/trusted-device scenarios.
//
// docs/spec/frontend-e2e.md §3 "2fa". The TOTP secret is shown as plain text
// on the setup screen (`TwoFactorSetup.vue`'s `.tfa-secret code`) — codes are
// computed here with `otpauth`, never faked. Every test uses its own
// throwaway principal (created via the admin API) rather than the shared
// e2e-admin account, so enrolling/resetting 2FA here can never leave a
// stray challenge in front of a plain `loginAndLand(ADMIN_EMAIL, ...)` used
// by every other spec file sharing this run's database.
import { test, expect, loginAndLand, loginViaUi, signOutViaUi, ADMIN_EMAIL, ADMIN_PASSWORD } from "../fixtures/admin.js";
import {
    createTwoFactorTestUser,
    createRememberDeviceUser,
    enrollTotpViaProfile,
    totpCodeAfter,
} from "../fixtures/twoFactor.js";

test.describe("2fa", () => {
    test("enrolling TOTP survives a reload; the login challenge accepts a confirmed code, and a recovery code is single-use", async ({ page, browser }) => {
        const admin = await browser.newContext().then((c) => c.newPage());
        await loginAndLand(admin, ADMIN_EMAIL, ADMIN_PASSWORD);
        const user = await createTwoFactorTestUser(admin.request);
        await admin.close();

        await loginAndLand(page, user.email, user.password);
        const { secret, recoveryCodes, enrollCode } = await enrollTotpViaProfile(page);

        // Reloaded proof of enrollment: a fresh GET (not client-cached state).
        await page.reload();
        await expect(page.locator(".tfa-row", { hasText: "Authenticator app" })).toBeVisible();

        // Logging out and back in now shows the 2FA challenge, and a freshly
        // computed code passes it. The server's replay guard rejects the
        // exact code just used to confirm enrollment, so wait for the TOTP
        // step to roll over rather than resubmitting it.
        await signOutViaUi(page);
        await loginViaUi(page, user.email, user.password);
        await expect(page.locator(".tfa-challenge")).toBeVisible();
        const challengeCode = await totpCodeAfter(secret, enrollCode);
        await page.locator(".tfa-challenge .tfa-input").fill(challengeCode);
        await page.locator(".tfa-challenge").getByRole("button", { name: "Verify" }).click();
        await expect(page).toHaveURL(/\/(dashboard|profile)(\?.*)?$/, { timeout: 15_000 });

        // A recovery code signs in once...
        await signOutViaUi(page);
        await loginViaUi(page, user.email, user.password);
        await page.getByText("Use a recovery code").click();
        const code = recoveryCodes[0]!;
        await page.locator(".tfa-challenge .tfa-input").fill(code);
        await page.locator(".tfa-challenge").getByRole("button", { name: "Verify" }).click();
        await expect(page).toHaveURL(/\/(dashboard|profile)(\?.*)?$/, { timeout: 15_000 });

        // ...and refuses a second use: the observable proof is that no
        // session gets established, not just an error string.
        await signOutViaUi(page);
        await loginViaUi(page, user.email, user.password);
        await page.getByText("Use a recovery code").click();
        await page.locator(".tfa-challenge .tfa-input").fill(code);
        await page.locator(".tfa-challenge").getByRole("button", { name: "Verify" }).click();
        await expect(page.locator(".tfa-error")).toHaveText(/invalid or expired code/i);
        const me = await page.request.get("/auth/me");
        expect(me.status()).toBe(401);
    });

    test("trusting a device on login skips the challenge next time", async ({ page, browser }) => {
        const admin = await browser.newContext().then((c) => c.newPage());
        await loginAndLand(admin, ADMIN_EMAIL, ADMIN_PASSWORD);
        const user = await createRememberDeviceUser(admin.request);
        await admin.close();

        await loginAndLand(page, user.email, user.password);
        const { secret, enrollCode } = await enrollTotpViaProfile(page);
        await signOutViaUi(page);

        // First challenge after enrollment: check "remember this device".
        // Same replay-guard note as the enroll/challenge test above.
        await loginViaUi(page, user.email, user.password);
        await expect(page.locator(".tfa-challenge")).toBeVisible();
        const challengeCode = await totpCodeAfter(secret, enrollCode);
        await page.locator(".tfa-challenge .tfa-input").fill(challengeCode);
        await page.getByLabel("Remember this device for 30 days").check();
        await page.locator(".tfa-challenge").getByRole("button", { name: "Verify" }).click();
        await expect(page).toHaveURL(/\/(dashboard|profile)(\?.*)?$/, { timeout: 15_000 });

        // Same browser context (the trusted-device cookie travels with it):
        // logging out and back in with just the password never shows the
        // challenge at all.
        await signOutViaUi(page);
        await loginViaUi(page, user.email, user.password);
        await expect(page).toHaveURL(/\/(dashboard|profile)(\?.*)?$/, { timeout: 15_000 });
        await expect(page.locator(".tfa-challenge")).toHaveCount(0);
    });

    test("an admin resetting a user's 2FA clears the challenge at their next login", async ({ adminPage, browser }) => {
        const user = await createTwoFactorTestUser(adminPage.request);

        const userContext = await browser.newContext();
        const userPage = await userContext.newPage();
        await loginAndLand(userPage, user.email, user.password);
        await enrollTotpViaProfile(userPage);
        await signOutViaUi(userPage);

        await adminPage.goto(`/users/${user.id}`);
        const resetButton = adminPage.getByRole("button", { name: "Reset 2FA" });
        await expect(resetButton).toBeEnabled();
        await resetButton.click();
        const resetResponse = adminPage.waitForResponse(
            (r) => r.url().includes(`/principals/${user.id}/reset-2fa`) && r.request().method() === "POST",
        );
        await adminPage.getByRole("alertdialog").getByRole("button", { name: "Reset 2FA" }).click();
        expect((await resetResponse).ok()).toBe(true);

        // Reloaded proof on the admin side: navigate away and back, the
        // account shows no enrolled factor.
        await adminPage.goto("/users");
        await adminPage.goto(`/users/${user.id}`);
        await expect(adminPage.getByRole("button", { name: "Reset 2FA" })).toBeDisabled();

        // The real behavioural proof: the user's next login never shows the
        // challenge — the same context that was mid-challenge is proof the
        // reset landed on the server, not just the admin's screen.
        await loginAndLand(userPage, user.email, user.password);
        await expect(userPage).toHaveURL(/\/(dashboard|profile)(\?.*)?$/, { timeout: 15_000 });
        await expect(userPage.locator(".tfa-challenge")).toHaveCount(0);

        await userContext.close();
    });
});
