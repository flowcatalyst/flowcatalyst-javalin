// Go screen: frontend/src/pages/auth/{LoginPage,ForgotPasswordPage,ResetPasswordPage}.vue,
// pages/ProfilePage.vue, components/layout/SidebarProfile.vue.
// Parity scenarios covering the same wire (docs/spec/parity-harness.md S1/S2
// groups): the `auth` group's login/logout/session/change-password/
// password-reset scenarios.
//
// docs/spec/frontend-e2e.md §3 "auth": one test per bullet. Every assertion
// is on reloaded state (CLAUDE.md) — never a toast, a disabled spinner, or
// any other transient element.
import path from "node:path";
import { test, expect, loginViaUi, loginAndLand, fillLoginForm, signOutViaUi, ADMIN_EMAIL, ADMIN_PASSWORD, SESSION_COOKIE_NAME } from "../fixtures/admin.js";
import { waitForMailTo, firstLink } from "../runner/mail.js";

const MAIL_LOG_PATH = path.join(import.meta.dirname, "..", "test-results", `${process.env.E2E_SIDE ?? "unknown"}.log`);

test.describe("auth", () => {
    // 1. wrong password → the error text is shown and GET /auth/me (via
    //    page.request) is 401.
    test("wrong password shows the error and leaves no session", async ({ page }) => {
        await loginViaUi(page, ADMIN_EMAIL, "not-the-right-password-at-all");

        await expect(page.locator(".error-message p")).toHaveText(/invalid credentials/i);

        const me = await page.request.get("/auth/me");
        expect(me.status()).toBe(401);
    });

    // 2. right password → lands on /dashboard; reload keeps the session.
    test("right password lands on the dashboard and survives a reload", async ({ page }) => {
        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await expect(page).toHaveURL(/\/dashboard/);

        await page.reload();

        // The observable proof a reload kept the session: still on the
        // dashboard (an unauthenticated reload would bounce to /auth/login),
        // and /auth/me — a fresh request, not anything cached client-side —
        // answers for the admin.
        await expect(page).toHaveURL(/\/dashboard/);
        const me = await page.request.get("/auth/me");
        expect(me.ok()).toBe(true);
        expect((await me.json()).email).toBe(ADMIN_EMAIL);
    });

    // 3. logout → /dashboard bounces to the login page; the cookie is gone.
    test("logout clears the cookie and dashboard bounces to login", async ({ page }) => {
        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);

        await signOutViaUi(page);

        const cookies = await page.context().cookies();
        expect(cookies.find((c) => c.name === SESSION_COOKIE_NAME)).toBeUndefined();

        await page.goto("/dashboard");
        await expect(page).toHaveURL(/\/auth\/login/);
    });

    // 4. deep link while logged out → after login the URL is the deep link.
    test("a deep link while logged out returns to that link after login", async ({ page }) => {
        await page.goto("/profile");
        await expect(page).toHaveURL(/\/auth\/login/);
        expect(new URL(page.url()).searchParams.get("redirect")).toBe("/profile");

        // Not loginViaUi: a fresh `goto("/auth/login")` would drop the
        // `?redirect=` query the guard just put on the URL.
        await fillLoginForm(page, ADMIN_EMAIL, ADMIN_PASSWORD);

        await expect(page).toHaveURL(/\/profile$/, { timeout: 15_000 });
    });

    // 5. profile page renders the admin's email.
    test("the profile page renders the admin's own email", async ({ adminPage }) => {
        await adminPage.goto("/profile");
        await expect(adminPage.getByText(ADMIN_EMAIL).first()).toBeVisible();
    });

    // 6. change password → logout → old password fails, new one works →
    //    change back (so the fixed admin credentials keep working for
    //    every test after this one, in this file and any future group that
    //    reuses this database).
    test("change password takes effect, then is changed back", async ({ page }) => {
        const NEW_PASSWORD = "Osaka-Lantern-6630!";

        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await page.goto("/profile");
        await page.getByRole("button", { name: "Change Password", exact: true }).click();

        const dialog = page.getByRole("dialog");
        await dialog.getByLabel("Current password").fill(ADMIN_PASSWORD);
        await dialog.getByLabel("New password", { exact: true }).fill(NEW_PASSWORD);
        await dialog.getByLabel("Confirm new password").fill(NEW_PASSWORD);

        const changed = page.waitForResponse(
            (r) => r.url().includes("/auth/change-password") && r.request().method() === "POST",
        );
        await dialog.getByRole("button", { name: "Change Password", exact: true }).click();
        const changeResponse = await changed;
        expect(changeResponse.ok()).toBe(true);

        await signOutViaUi(page);

        // Old password now fails.
        await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await expect(page.locator(".error-message p")).toHaveText(/invalid credentials/i);
        expect((await page.request.get("/auth/me")).status()).toBe(401);

        // New password works.
        await loginAndLand(page, ADMIN_EMAIL, NEW_PASSWORD);
        await expect(page).toHaveURL(/\/dashboard/);

        // Change back, so ADMIN_PASSWORD stays valid for every test after
        // this one — same UI path, just the two passwords swapped.
        await page.goto("/profile");
        await page.getByRole("button", { name: "Change Password", exact: true }).click();
        const dialog2 = page.getByRole("dialog");
        await dialog2.getByLabel("Current password").fill(NEW_PASSWORD);
        await dialog2.getByLabel("New password", { exact: true }).fill(ADMIN_PASSWORD);
        await dialog2.getByLabel("Confirm new password").fill(ADMIN_PASSWORD);
        const changedBack = page.waitForResponse(
            (r) => r.url().includes("/auth/change-password") && r.request().method() === "POST",
        );
        await dialog2.getByRole("button", { name: "Change Password", exact: true }).click();
        expect((await changedBack).ok()).toBe(true);

        // Reloaded proof the original password is live again, not just that
        // the dialog closed.
        await signOutViaUi(page);
        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await expect(page).toHaveURL(/\/dashboard/);
    });

    // 7. forgot password → the link from the server log (newest mail to
    //    that address, first http… in the body) → set a new password →
    //    login. Restores ADMIN_PASSWORD afterwards for the same reason as
    //    the change-password test.
    test("forgot password mails a working reset link", async ({ page }) => {
        const RESET_PASSWORD = "Valletta-Compass-2298!";

        await page.goto("/auth/forgot-password");
        await page.getByLabel("Email address").fill(ADMIN_EMAIL);
        await page.getByRole("button", { name: "Send reset link" }).click();
        await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();

        const mail = await waitForMailTo(MAIL_LOG_PATH, ADMIN_EMAIL);
        const link = firstLink(mail.body);
        expect(link, `no http(s) link found in the reset mail body: ${mail.body}`).not.toBeNull();

        await page.goto(link!);
        // ResetPasswordPage.vue has the same `id`-on-the-wrapper quirk as
        // the login page's password field (see fixtures/admin.ts) — `#id
        // input` reaches the real control, `getByLabel` doesn't.
        await page.locator("#password input").fill(RESET_PASSWORD);
        await page.locator("#confirmPassword input").fill(RESET_PASSWORD);
        const confirmed = page.waitForResponse(
            (r) => r.url().includes("/auth/password-reset/confirm") && r.request().method() === "POST",
        );
        await page.getByRole("button", { name: "Reset password" }).click();
        const confirmResponse = await confirmed;
        expect(confirmResponse.ok(), await confirmResponse.text()).toBe(true);

        // Observable proof of the reset: the OLD password no longer works...
        await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await expect(page.locator(".error-message p")).toHaveText(/invalid credentials/i);

        // ...and the new one lands on the dashboard.
        await loginAndLand(page, ADMIN_EMAIL, RESET_PASSWORD);
        await expect(page).toHaveURL(/\/dashboard/);

        // Restore ADMIN_PASSWORD via the (already-proven) change-password path.
        await page.goto("/profile");
        await page.getByRole("button", { name: "Change Password", exact: true }).click();
        const dialog = page.getByRole("dialog");
        await dialog.getByLabel("Current password").fill(RESET_PASSWORD);
        await dialog.getByLabel("New password", { exact: true }).fill(ADMIN_PASSWORD);
        await dialog.getByLabel("Confirm new password").fill(ADMIN_PASSWORD);
        const changedBack = page.waitForResponse(
            (r) => r.url().includes("/auth/change-password") && r.request().method() === "POST",
        );
        await dialog.getByRole("button", { name: "Change Password", exact: true }).click();
        expect((await changedBack).ok()).toBe(true);
    });
});
