import { test as base, expect, type Page } from "@playwright/test";

/// The session cookie both sides write (`SessionCookie.NAME` /
/// `platformmw.SessionCookieName`) — `fc_session`.
export const SESSION_COOKIE_NAME = "fc_session";

export const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL ?? "e2e-admin@example.com";
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? "Tromso-Nebula-7734!";

/// Fills and submits the SPA's two-step login form on whatever page the
/// browser is already on (`LoginPage.vue`: an email step that resolves
/// internal-vs-SSO, then a password step) — does **not** navigate first,
/// so it doesn't clobber a query string a redirect already put on the URL
/// (see the deep-link test, which needs `?redirect=…` to survive).
export async function fillLoginForm(page: Page, email: string, password: string): Promise<void> {
    await page.getByLabel("Email address").fill(email);
    await page.getByRole("button", { name: "Continue" }).click();
    // `LoginPage.vue`'s `<Password id="password">` puts `id` on PrimeVue's
    // wrapper element, not the inner `<input>` — so it never associates
    // with `<label for="password">` and `getByLabel` finds nothing (the
    // input's accessible name falls back to its placeholder instead).
    // `#password input` reaches the real control either way.
    await page.locator("#password input").fill(password);
    // `exact` — "Sign in with a passkey" also matches a loose "Sign in".
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
}

/// [fillLoginForm], after first navigating to a clean `/auth/login` — the
/// normal case, for a test that doesn't care about an existing query string.
export async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
    await page.goto("/auth/login");
    await fillLoginForm(page, email, password);
}

/// [loginViaUi] and wait for the landing page — the dashboard for a user
/// with roles (this admin), the profile otherwise (`guestGuard`'s
/// `landingPath`). Fails loudly if a challenge or error appeared instead,
/// so a caller that expected a clean login doesn't silently hang.
export async function loginAndLand(page: Page, email: string, password: string): Promise<void> {
    await loginViaUi(page, email, password);
    await expect(page).toHaveURL(/\/(dashboard|profile)(\?.*)?$/, { timeout: 15_000 });
}

/// Opens the sidebar profile popover and clicks "Sign Out" — the only UI
/// path to `/auth/logout` (`SidebarProfile.vue`'s `handleLogout`).
export async function signOutViaUi(page: Page): Promise<void> {
    await page.locator(".profile-trigger").click();
    const signOut = page.getByRole("button", { name: "Sign Out" });
    // PrimeVue's Popover slides/fades in (SidebarProfile.vue's `.15s`
    // transition); Playwright's own actionability wait sometimes never sees
    // two stable frames in a row while it's animating, and retries past its
    // own click timeout. Wait for the transition to actually settle first —
    // a flow that signs out and back in repeatedly (2FA, passkeys) opens
    // this popover often enough to make that reliably reproducible.
    await signOut.waitFor({ state: "visible" });
    await page.waitForTimeout(250);
    await signOut.click();
    await expect(page).toHaveURL(/\/auth\/login/);
}

/// A `page` already signed in as the bootstrap admin (`fcdev init`'s
/// anchor admin, ruling: `docs/spec/fcdev-commands.md` §1 step 2) — for
/// specs that need to start past the login screen. The auth group's own
/// tests exercise login/logout directly instead of using this.
export const test = base.extend<{ adminPage: Page }>({
    adminPage: async ({ page }, use) => {
        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await use(page);
    },
});

export { expect };
