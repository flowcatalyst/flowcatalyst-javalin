// Screens: frontend/src/pages/auth/{LoginPage,ResetPasswordPage}.vue (the
// SPA source lives in this repo since 2026-09-14). Application-managed
// invitations (docs/spec/app-managed-invitations.md) exist on both sides;
// Go carried the change in its working tree on f81fd5a, so the Go column of
// this flow is meaningful only once that is committed.
//
// docs/spec/frontend-e2e.md §3/§4: assertions are the observable outcome
// after a reload, never a transient banner; mail is read back from the
// server log via waitForMailTo/parseMailLog, never sent for real.
//
// Both tests create the principal on a fresh admin page/context (not the
// shared `page`) so the guest-side UI flow starts from a genuinely
// unauthenticated browser context — a `page` that ever carried the admin's
// session cookie would make "lands in without ever visiting /auth/login
// again" and "reload stays in" unprovable.
//
// docs/spec/app-managed-invitations.md §1: the admin's own domain
// (example.com here, ADMIN_EMAIL's domain) already carries the ANCHOR
// email-domain mapping `fcdev init` creates for the bootstrap admin, so a
// create-user call that omits `scope` defaults to CLIENT scope with no
// clientId and 400s `CLIENT_REQUIRED` (UserScopeDerivation's CLIENT branch
// only inherits a client from a CLIENT-type mapping, never an ANCHOR one).
// `scope: "ANCHOR"` is what makes `{email, name, sendInvitation: false}` on
// this domain create successfully; a role-less ANCHOR user lands on
// /profile exactly like a role-less CLIENT one (`isRoleless` in
// frontend/src/stores/permissions.ts doesn't look at scope), so the
// expected landing path is the same either way.
import path from "node:path";
import { readFile } from "node:fs/promises";
import type { Page } from "@playwright/test";
import { test, expect, loginAndLand, ADMIN_EMAIL, ADMIN_PASSWORD, SESSION_COOKIE_NAME } from "../fixtures/admin.js";
import { waitForMailTo, firstLink, parseMailLog } from "../runner/mail.js";

const MAIL_LOG_PATH = path.join(import.meta.dirname, "..", "test-results", `${process.env.E2E_SIDE ?? "unknown"}.log`);

function unique(prefix: string): string {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

async function messagesTo(address: string): Promise<{ to: string; subject: string; body: string }[]> {
    const text = await readFile(MAIL_LOG_PATH, "utf8");
    const wanted = address.trim().toLowerCase();
    return parseMailLog(text).filter((m) => m.to.trim().toLowerCase() === wanted);
}

/// Sets the invite/reset password twice, submits, and returns the parsed
/// `POST /auth/password-reset/confirm` JSON body — shared by both flows,
/// which differ only in how the token reaches this page.
async function setPasswordAndConfirm(page: Page, password: string): Promise<{ sessionEstablished?: boolean }> {
    await expect(page.getByRole("heading", { name: "Set your password" })).toBeVisible();
    await page.locator("#password input").fill(password);
    await page.locator("#confirmPassword input").fill(password);

    const confirmed = page.waitForResponse(
        (r) => r.url().includes("/auth/password-reset/confirm") && r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Set password" }).click();
    const confirmResponse = await confirmed;
    expect(confirmResponse.ok(), await confirmResponse.text()).toBe(true);
    return (await confirmResponse.json()) as { sessionEstablished?: boolean };
}

/// The observable proof of a successful sign-in from the invite flow: the
/// URL is the role-less landing page (never bounced back through
/// /auth/login), the session cookie is present, and it survives a reload.
async function assertLandedSignedIn(page: Page): Promise<void> {
    await expect(page).toHaveURL(/\/profile(\?.*)?$/, { timeout: 15_000 });
    const cookies = await page.context().cookies();
    expect(cookies.find((c) => c.name === SESSION_COOKIE_NAME)).toBeTruthy();

    await page.reload();
    await expect(page).toHaveURL(/\/profile(\?.*)?$/);
}

test.describe("invitations", () => {
    test("login-detected: sendInvitation:false mails nothing up front; check-domain detects it and the mailed link signs the user straight in", async ({ page, browser }) => {
        const email = `${unique("e2e-invite-login")}@example.com`;

        const admin = await browser.newContext().then((c) => c.newPage());
        await loginAndLand(admin, ADMIN_EMAIL, ADMIN_PASSWORD);
        const createRes = await admin.request.post("/api/principals/users", {
            data: { email, name: "E2E Invite Login", sendInvitation: false, scope: "ANCHOR" },
        });
        expect(createRes.ok(), await createRes.text()).toBe(true);
        const createdBody = (await createRes.json()) as Record<string, unknown>;
        expect("inviteLink" in createdBody).toBe(false);
        await admin.close();

        // Fresh, never-authenticated page from here on.
        await page.goto("/auth/login");
        await page.getByLabel("Email address").fill(email);
        await page.getByRole("button", { name: "Continue" }).click();
        // The observable proof check-domain answered passwordSetupRequired:
        // the SPA shows the "create a password" step, not the ordinary
        // password prompt.
        await expect(page.getByRole("heading", { name: "Create your password" })).toBeVisible();

        await page.getByRole("button", { name: "Email me a link" }).click();
        await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();

        const mail = await waitForMailTo(MAIL_LOG_PATH, email, 10_000, "Set your password");
        const link = firstLink(mail.body);
        expect(link, `no http(s) link found in the setup mail body: ${mail.body}`).not.toBeNull();

        await page.goto(link!);
        const confirmBody = await setPasswordAndConfirm(page, "Fremantle-Compass-8817!");
        expect(confirmBody.sessionEstablished).toBe(true);

        await assertLandedSignedIn(page);

        // Exactly one mail to this address ever: the setup mail just used.
        // If sendInvitation:false had leaked the platform's own invite (or a
        // welcome mail) on create, this would be 2.
        expect(await messagesTo(email)).toHaveLength(1);
    });

    test("embedded link: returnInviteLink:true returns a live set-password link and mails nothing", async ({ page, browser, baseURL }) => {
        const email = `${unique("e2e-invite-embed")}@example.com`;

        const admin = await browser.newContext().then((c) => c.newPage());
        await loginAndLand(admin, ADMIN_EMAIL, ADMIN_PASSWORD);
        const createRes = await admin.request.post("/api/principals/users", {
            data: { email, name: "E2E Invite Embed", returnInviteLink: true, scope: "ANCHOR" },
        });
        expect(createRes.ok(), await createRes.text()).toBe(true);
        const createdBody = (await createRes.json()) as { inviteLink?: string };
        expect(typeof createdBody.inviteLink).toBe("string");
        expect(createdBody.inviteLink!.startsWith(`${baseURL}/auth/set-password?token=`)).toBe(true);
        await admin.close();

        await page.goto(createdBody.inviteLink!);
        const confirmBody = await setPasswordAndConfirm(page, "Kyoto-Ferry-2291!");
        expect(confirmBody.sessionEstablished).toBe(true);

        await assertLandedSignedIn(page);

        // The platform mailed nothing at all for this address — the whole
        // point of returning the link instead of sending it.
        expect(await messagesTo(email)).toHaveLength(0);
    });
});
