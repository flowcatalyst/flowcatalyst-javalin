// Go screens: frontend/src/pages/clients/{ClientListPage,ClientCreateDrawer,
// ClientDetailDrawer,ClientLoginThemePage}.vue, pages/users/{UserListPage,
// UserCreateDrawer,ClientUsersPage}.vue, components/PermissionDeniedModal.vue.
// Parity scenarios covering the same wire (docs/spec/parity-harness.md):
// the `clients` and `principal` groups' create/update/list scenarios.
//
// docs/spec/frontend-e2e.md §3 "tenancy". The confinement test is the one
// flow the brief marks as never to be skipped or weakened: a client-scoped
// user must see only its own client's rows, and a deliberate (not
// first-load) navigation to a page it lacks permission for must show the
// permission-denied modal, not just silently redirect.
import { test, expect } from "../fixtures/admin.js";
import { createClientScopedPrincipal, unique } from "../fixtures/clientScoped.js";

test.describe("tenancy", () => {
    test("creating, editing, and branding a client persists after reload", async ({ adminPage }) => {
        const identifier = unique("e2e-tenancy-client");
        const name = `E2E Tenancy Client ${identifier}`;

        await adminPage.goto("/clients");
        await adminPage.getByRole("button", { name: "Create Client" }).click();
        await expect(adminPage).toHaveURL(/\/clients\/new/);
        await adminPage.getByPlaceholder("Client display name").fill(name);
        await adminPage.getByPlaceholder("client-slug").fill(identifier);
        const created = adminPage.waitForResponse(
            (r) => r.url().endsWith("/api/clients") && r.request().method() === "POST",
        );
        // Scoped to the create drawer's footer — the list page behind it has
        // its own "Create Client" button with the same accessible name.
        await adminPage.locator(".entity-drawer-footer").getByRole("button", { name: "Create Client", exact: true }).click();
        const createdResponse = await created;
        expect(createdResponse.ok()).toBe(true);
        const client = (await createdResponse.json()) as { id: string };
        await expect(adminPage).toHaveURL(new RegExp(`/clients/${client.id}$`));

        // Edit the name.
        const newName = `${name} (edited)`;
        await adminPage.getByRole("button", { name: "Edit", exact: true }).click();
        await adminPage.getByLabel("Name").fill(newName);
        // Scoped to the drawer's footer — the always-visible "Applications"
        // picklist section has its own unrelated "Save" button.
        await adminPage.locator(".entity-drawer-footer").getByRole("button", { name: "Save", exact: true }).click();
        await expect(adminPage.getByRole("heading", { name: newName })).toBeVisible();

        // Reloaded proof: navigate away to the list and back into the row.
        await adminPage.goto("/clients");
        await adminPage.getByText(newName).click();
        await expect(adminPage).toHaveURL(new RegExp(`/clients/${client.id}$`));
        await expect(adminPage.getByRole("heading", { name: newName })).toBeVisible();

        // Login branding: enable it, set a brand name, save, and reload.
        await adminPage.getByRole("button", { name: "Edit Branding" }).click();
        await expect(adminPage).toHaveURL(new RegExp(`/clients/${client.id}/theme`));
        await adminPage.getByRole("switch").check();
        const brandName = `E2E Brand ${identifier}`;
        await adminPage.getByLabel("Brand Name").fill(brandName);
        const themeSaved = adminPage.waitForResponse(
            (r) => r.url().includes("/config/platform/login/theme") && r.request().method() === "PUT",
        );
        await adminPage.getByRole("button", { name: "Save Changes" }).click();
        expect((await themeSaved).ok()).toBe(true);

        await adminPage.reload();
        await expect(adminPage.getByRole("switch")).toBeChecked();
        await expect(adminPage.getByLabel("Brand Name")).toHaveValue(brandName);
    });

    test("creating a user through the admin UI links them to the picked client", async ({ adminPage }) => {
        const clientIdentifier = unique("e2e-tenancy-uclient");
        const clientName = `E2E Tenancy UClient ${clientIdentifier}`;
        const clientRes = await adminPage.request.post("/api/clients", {
            data: { name: clientName, identifier: clientIdentifier },
        });
        if (!clientRes.ok()) throw new Error(`POST /api/clients -> ${clientRes.status()} ${await clientRes.text()}`);

        const domain = `${unique("e2e-tenancy-domain")}.test`;
        const email = `${unique("e2e-tenancy-user")}@${domain}`;

        await adminPage.goto("/users/new");
        await adminPage.getByPlaceholder("e.g., John Smith").fill("E2E Tenancy User");
        const emailField = adminPage.getByPlaceholder("e.g., john.smith@example.com");
        await emailField.fill(email);
        await emailField.blur();

        // Unmapped domain -> CLIENT scope, a client picker is required.
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientName) }).click();

        const createUser = adminPage.waitForResponse(
            (r) => r.url().endsWith("/api/principals/users") && r.request().method() === "POST",
        );
        await adminPage.getByRole("button", { name: "Create User", exact: true }).click();
        const createUserResponse = await createUser;
        expect(createUserResponse.ok(), await createUserResponse.text()).toBe(true);

        // Reloaded proof: the platform Users list finds them by email, tied
        // to the client just picked.
        await adminPage.goto("/users");
        await adminPage.getByPlaceholder("Search by name or email...").fill(email);
        await expect(adminPage.getByText(email)).toBeVisible();
        await expect(adminPage.getByText(clientName)).toBeVisible();
    });

    // The mandatory flow (spec §3): must never be skipped or weakened.
    test("a client-scoped user sees only its own client's rows, and hits a permission-denied wall on an anchor-only screen", async ({ adminPage, browser }) => {
        const userA = await createClientScopedPrincipal(adminPage.request);
        const userB = await createClientScopedPrincipal(adminPage.request);
        expect(userA.clientId).not.toBe(userB.clientId);

        // Give A enough permission to reach the one list page a client
        // administrator can see — `platform:client-admin`, the seeded role
        // for exactly this (internal/platform/seed/roles.go).
        const rolesResponse = await adminPage.request.put(`/api/principals/${userA.id}/roles`, {
            data: { roles: ["platform:client-admin"] },
        });
        expect(rolesResponse.ok(), await rolesResponse.text()).toBe(true);

        const userContext = await browser.newContext();
        const userPage = await userContext.newPage();
        await userPage.goto("/auth/login");
        await userPage.getByLabel("Email address").fill(userA.email);
        await userPage.getByRole("button", { name: "Continue" }).click();
        await userPage.locator("#password input").fill(userA.password);
        await userPage.getByRole("button", { name: "Sign in", exact: true }).click();
        await expect(userPage).toHaveURL(/\/client-administration\/users/, { timeout: 15_000 });

        // Confinement: only A's own client's rows are listed, never B's —
        // checked once fresh and once after a hard reload, so it's the
        // server's filter proving this, not something cached client-side.
        const table = userPage.getByRole("table");
        for (let i = 0; i < 2; i++) {
            await expect(table.getByText(userA.email)).toBeVisible();
            await expect(table.getByText(userB.email)).toHaveCount(0);
            await userPage.reload();
        }
        // The last reload's app boot must finish (and vue-router's popstate
        // listener attach) before the history trick below has anything to
        // react to — wait for a stable post-hydration element rather than a
        // fixed delay.
        await expect(userPage.getByRole("heading", { name: "User Management", exact: true })).toBeVisible();

        // A deliberate in-app navigation (not a fresh page load, which the
        // guard treats as automatic landing and silently redirects — see
        // `router/guards.ts`'s `isAutomaticLanding`) to a screen this role
        // holds no permission for.
        // `globalThis`, not `window`: this callback runs in the page's
        // browser realm (Playwright serializes it there), but the project's
        // tsconfig has no "dom" lib, so `window`/`PopStateEvent` aren't
        // ambient types here — `globalThis` is, and casting through `any`
        // reaches the same object at runtime.
        await userPage.evaluate((path: string) => {
            const w = globalThis as any;
            w.history.pushState({}, "", path);
            w.dispatchEvent(new w.PopStateEvent("popstate"));
        }, "/clients");
        // The guard resolves (and redirects to /profile) before the modal is
        // shown — wait for that first so the dialog check isn't a race.
        await expect(userPage).toHaveURL(/\/profile/, { timeout: 10_000 });
        await expect(userPage.getByRole("dialog", { name: "Permission Denied" })).toBeVisible();
        await expect(userPage.getByText("platform:admin:client:view")).toBeVisible();
        // The missing row: the Clients screen never actually rendered.
        await expect(userPage.getByRole("heading", { name: "Clients", exact: true })).toHaveCount(0);

        await userContext.close();
    });
});
