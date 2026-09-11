// Go screens: frontend/src/pages/service-accounts/{ServiceAccountListPage,
// ServiceAccountCreateDrawer,ServiceAccountDetailDrawer}.vue,
// pages/portal/{PortalAppsPage,PortalUsersPage}.vue,
// pages/developer/DeveloperUsersListPage.vue.
// Parity scenarios covering the same wire (docs/spec/parity-harness.md):
// the `service-account`, `portal-apps`, `portal-users`, `portal`, and
// `principal` (developer-role) groups' create/list/grant/revoke scenarios;
// `platform/profile-only.json` for the role-less gate (docs/spec/portal-apps.md §6, §9.9).
//
// docs/spec/frontend-e2e.md §3 "identity". The re-synced SPA (Go frontend
// `373fe93`) removed the "Invite Portal User" button — portal users are now
// seeded only by the portal app itself (POST /api/portal-users) — so the
// Portal Users flow below seeds through the API and exercises the list/
// search/revoke surface instead of an invite dialog.
import { test, expect, loginAndLand } from "../fixtures/admin.js";
import { createClientScopedPrincipal, unique } from "../fixtures/clientScoped.js";

test.describe("identity", () => {
    test("creating a service account shows its credentials once, then lists and persists it after reload", async ({ adminPage }) => {
        await adminPage.goto("/identity/service-accounts");
        await adminPage.getByRole("button", { name: "Add Service Account" }).click();

        const code = unique("e2e-sa");
        const name = `E2E Service Account ${code}`;
        const nameField = adminPage.getByPlaceholder("My Service Account");
        await nameField.fill(name);
        // The Name field auto-fills Code from a slugified name on blur
        // (`generateCode()`) — blur it explicitly and wait for that before
        // typing the real code, or the two writes race and concatenate.
        await nameField.blur();
        const codeField = adminPage.getByPlaceholder("my-service-account");
        await expect(codeField).not.toHaveValue("");
        await codeField.fill(code);

        const created = adminPage.waitForResponse(
            (r) => r.url().endsWith("/api/service-accounts") && r.request().method() === "POST",
        );
        await adminPage.getByRole("button", { name: "Create Service Account", exact: true }).click();
        const createdResponse = await created;
        expect(createdResponse.ok(), await createdResponse.text()).toBe(true);

        // The one-time credentials dialog: a real secret, shown exactly once.
        const credentialsDialog = adminPage.getByRole("dialog", { name: "Service Account Created" });
        await expect(credentialsDialog).toBeVisible();
        const clientSecret = await credentialsDialog.locator(".credential-value code").nth(1).textContent();
        expect(clientSecret, "no client secret shown on creation").toBeTruthy();
        await credentialsDialog.getByRole("button", { name: "I've Copied the Credentials" }).click();

        // Reloaded proof: navigate away to the list and back.
        await adminPage.goto("/identity/service-accounts");
        await adminPage.getByText(name).click();
        // Exact match: `name` itself contains `code` as a substring, so a
        // loose text match would also hit the name span.
        await expect(adminPage.getByText(code, { exact: true })).toBeVisible();
        // The secret is never shown again — the detail view only offers to
        // regenerate it, never displays the one just created.
        await expect(adminPage.getByText(clientSecret!)).toHaveCount(0);
    });

    test("creating a portal app with a callback shows its credentials once, lists it with its OAuth client id after reload, then deleting removes it", async ({ adminPage }) => {
        const { clientIdentifier } = await createClientScopedPrincipal(adminPage.request);

        await adminPage.goto("/identity/portal-apps");
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientIdentifier) }).click();

        // The "New Portal App" button renders only for a holder of
        // portal-user:manage — it never rendered for anyone until Go d6b215b
        // (fix list P1: the page read a permissions store nothing filled).

        const code = unique("e2e-portal-app");
        const name = `E2E Portal App ${code}`;
        await adminPage.getByRole("button", { name: "New Portal App" }).click();
        const createDialog = adminPage.getByRole("dialog", { name: "New Portal App" });
        await createDialog.getByLabel("Name").fill(name);
        await createDialog.getByLabel("Code").fill(code);
        await createDialog.getByLabel("Callback URL(s)").fill(`https://${code}.example.com/callback`);

        const created = adminPage.waitForResponse(
            (r) => r.url().endsWith("/api/portal-apps") && r.request().method() === "POST",
        );
        await createDialog.getByRole("button", { name: "Create", exact: true }).click();
        const createdResponse = await created;
        expect(createdResponse.ok(), await createdResponse.text()).toBe(true);
        const createdBody = (await createdResponse.json()) as { oauthClientId: string; clientSecret: string };

        // The one-time credentials dialog: app code, client id, and a secret
        // (CONFIDENTIAL — the create dialog's default client type).
        const credentialsDialog = adminPage.getByRole("dialog", { name: "Portal app created" });
        await expect(credentialsDialog).toBeVisible();
        await expect(credentialsDialog.getByText(code, { exact: true })).toBeVisible();
        await expect(credentialsDialog.getByText(createdBody.oauthClientId, { exact: true })).toBeVisible();
        expect(createdBody.clientSecret, "no client secret shown on creation").toBeTruthy();
        await expect(credentialsDialog.getByText(createdBody.clientSecret, { exact: true })).toBeVisible();
        await credentialsDialog.getByRole("button", { name: "Done" }).click();

        // Reloaded proof: navigate away and back, refetching.
        await adminPage.goto("/identity/portal-apps");
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientIdentifier) }).click();
        const row = adminPage.getByRole("row", { name: new RegExp(name) });
        await expect(row).toBeVisible();
        await expect(row.getByText(createdBody.oauthClientId, { exact: true })).toBeVisible();

        // Delete it — confirmed by its absence after a reload, not the
        // dialog closing. The row's icon-only buttons carry no accessible
        // name (the `title` prop doesn't reach Chromium's accessibility
        // tree here), so it's targeted by its icon class.
        await row.locator("button:has(.pi-trash)").click();
        await adminPage.getByRole("alertdialog").getByRole("button", { name: "Delete", exact: true }).click();
        await adminPage.reload();
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientIdentifier) }).click();
        await expect(adminPage.getByRole("row", { name: new RegExp(name) })).toHaveCount(0);
    });

    test("a portal user seeded through the API lists as Invited with its app chip, server search narrows the table, and revoking the chip removes it after reload", async ({ adminPage }) => {
        const { clientIdentifier, clientId } = await createClientScopedPrincipal(adminPage.request);

        // Seed the app and the user through the API — there is deliberately
        // no invite button any more (portal-apps.md §7): invites are
        // initiated by the portal app itself.
        const appCode = unique("e2e-portal-app-for-users");
        const appName = `E2E Portal App For Users ${appCode}`;
        const appRes = await adminPage.request.post("/api/portal-apps", {
            data: { clientId, code: appCode, name: appName, redirectUris: [`https://${appCode}.example.com/callback`] },
        });
        expect(appRes.ok(), await appRes.text()).toBe(true);

        const email = `${unique("e2e-portal-user")}@example.com`;
        const ensureRes = await adminPage.request.post("/api/portal-users", {
            data: { clientId, email, portalAppCode: appCode, returnInviteLink: true },
        });
        expect(ensureRes.ok(), await ensureRes.text()).toBe(true);

        await adminPage.goto("/identity/portal-users");
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientIdentifier) }).click();

        const row = adminPage.getByRole("row", { name: new RegExp(email) });
        await expect(row).toBeVisible();
        await expect(row.getByText("Invited", { exact: true })).toBeVisible();
        const chip = row.locator(".p-chip", { hasText: appName });
        await expect(chip).toBeVisible();

        // Server-side search (debounced 300ms): a term that cannot match
        // anything empties the table; the seeded email's own prefix narrows
        // it back to (at least) this one row — proving the request actually
        // reached the server's filter, not a client-side guess.
        const searchField = adminPage.getByPlaceholder("Search email or name (starts with)");
        const searchedNothing = adminPage.waitForResponse(
            (r) => r.url().includes("/api/portal-users?") && r.request().method() === "GET",
        );
        await searchField.fill("zzz-no-such-prefix");
        await searchedNothing;
        await expect(adminPage.getByText("No portal users match.")).toBeVisible();
        await expect(row).toHaveCount(0);

        const searchedMatch = adminPage.waitForResponse(
            (r) => r.url().includes("/api/portal-users?") && r.request().method() === "GET",
        );
        await searchField.fill(email.split("@")[0]);
        await searchedMatch;
        await expect(row).toBeVisible();

        // Revoke the chip — confirmed by its absence from the row after a
        // reload, not the confirm dialog closing.
        await chip.locator(".p-chip-remove-icon").click();
        await adminPage.getByRole("alertdialog").getByRole("button", { name: "Remove", exact: true }).click();
        await adminPage.reload();
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientIdentifier) }).click();
        await searchField.fill(email.split("@")[0]);
        await expect(row).toBeVisible();
        await expect(row.locator(".p-chip", { hasText: appName })).toHaveCount(0);
    });

    test("granting and revoking the developer role changes what the Developer Users page lists", async ({ adminPage }) => {
        const user = await createClientScopedPrincipal(adminPage.request);

        await adminPage.goto("/identity/developer-users");
        await expect(adminPage.getByText(user.email)).toHaveCount(0);

        // Scoped to the header — the empty-state table body has its own
        // inline "Grant Developer Role" link with the same accessible name.
        await adminPage.locator("header").getByRole("button", { name: "Grant Developer Role" }).click();
        const grantDialog = adminPage.getByRole("dialog", { name: "Grant Developer Role" });
        await grantDialog.getByPlaceholder("Search by name or email...").fill(user.email);
        // The suggestion overlay teleports outside the dialog's DOM subtree,
        // so it's found page-wide rather than scoped to `grantDialog`.
        await adminPage.getByText(user.email).click();
        await expect(adminPage.getByText("can now hold a developer API credential")).toBeVisible();

        // Reloaded proof of the grant.
        await adminPage.reload();
        await expect(adminPage.getByText(user.email)).toBeVisible();

        // Set (rotate) their credential — shown once. These row actions are
        // icon-only buttons whose tooltip is a PrimeVue `v-tooltip` directive
        // (no accessible name), so they're targeted by their icon class.
        const row = adminPage.getByRole("row", { name: new RegExp(user.email) });
        await row.locator("button:has(.pi-key)").click();
        const secretDialog = adminPage.getByRole("dialog", { name: "Developer Client Secret" });
        await expect(secretDialog).toBeVisible();
        const secret = await secretDialog.locator(".credential-value code").textContent();
        expect(secret).toBeTruthy();
        await secretDialog.getByRole("button", { name: "Done" }).click();
        await adminPage.reload();
        await expect(row.getByText("Set")).toBeVisible();

        // Revoke the role — the reloaded proof is the row's absence, not a
        // toast.
        await row.locator("button:has(.pi-user-minus)").click();
        // No `acceptLabel` override here — PrimeVue's default is "Yes".
        await adminPage.getByRole("alertdialog").getByRole("button", { name: "Yes", exact: true }).click();
        await adminPage.reload();
        await expect(adminPage.getByText(user.email)).toHaveCount(0);
    });

    // portal-apps.md §6/§9.9: a USER principal with no roles and no
    // permissions may reach only its own profile — the profile-only gate,
    // enforced both server-side (`ProfileOnlyGate`) and by the SPA's own
    // route guard (`router/guards.ts`'s `landingPath`/`canAccessPath`).
    test("a role-less user lands on /profile, any other route redirects there, and the sidebar is empty", async ({ adminPage, browser }) => {
        // `createClientScopedPrincipal` creates a CLIENT-scoped USER with no
        // roles assigned — role-less by construction, the same shape the
        // parity harness's `platform/profile-only.json` uses.
        const user = await createClientScopedPrincipal(adminPage.request);

        const userContext = await browser.newContext();
        const userPage = await userContext.newPage();
        await loginAndLand(userPage, user.email, user.password);
        // `loginAndLand` accepts either landing page; a role-less user must
        // land on /profile specifically, never /dashboard.
        await expect(userPage).toHaveURL(/\/profile(\?.*)?$/);

        // Empty sidebar: no nav group survives the permission filter.
        await expect(userPage.locator(".sidebar-nav .nav-group")).toHaveCount(0);

        // A cold load (a typed URL or bookmark — the only way a role-less
        // user reaches another route, the sidebar being empty) must still
        // bounce to /profile. It did not until Go d6b215b (fix list P2: the
        // global guard ran before the session was hydrated).

        // Any other route — mapped or not — redirects to /profile. A fresh
        // page load is an "automatic landing" (`router/guards.ts`), so this
        // is a silent redirect, not the permission-denied modal.
        await userPage.goto("/dashboard");
        await expect(userPage).toHaveURL(/\/profile(\?.*)?$/);

        await userPage.goto("/identity/portal-apps");
        await expect(userPage).toHaveURL(/\/profile(\?.*)?$/);

        await userPage.goto("/clients");
        await expect(userPage).toHaveURL(/\/profile(\?.*)?$/);

        await userContext.close();
    });
});
