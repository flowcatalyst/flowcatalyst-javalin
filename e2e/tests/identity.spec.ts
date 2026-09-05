// Go screens: frontend/src/pages/service-accounts/{ServiceAccountListPage,
// ServiceAccountCreateDrawer,ServiceAccountDetailDrawer}.vue,
// pages/portal/PortalUsersPage.vue, pages/developer/DeveloperUsersListPage.vue.
// Parity scenarios covering the same wire (docs/spec/parity-harness.md):
// the `service-account`, `portal-user`, and `principal` (developer-role)
// groups' create/list/rotate scenarios.
//
// docs/spec/frontend-e2e.md §3 "identity".
import { test, expect } from "../fixtures/admin.js";
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

    test("inviting a portal user lists it for its client, then deleting it removes the row", async ({ adminPage }) => {
        const { clientIdentifier } = await createClientScopedPrincipal(adminPage.request);

        await adminPage.goto("/identity/portal-users");
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientIdentifier) }).click();

        const email = `${unique("e2e-portal-user")}@example.com`;
        await adminPage.getByRole("button", { name: "Invite Portal User" }).click();
        const inviteDialog = adminPage.getByRole("dialog", { name: "Invite Portal User" });
        await inviteDialog.getByLabel("Email").fill(email);
        const ensured = adminPage.waitForResponse(
            (r) => r.url().includes("/api/portal-users") && r.request().method() === "POST",
        );
        await inviteDialog.getByRole("button", { name: "Send Invite" }).click();
        expect((await ensured).ok()).toBe(true);

        // Reloaded proof: switch away to another client and back, refetching.
        await adminPage.reload();
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientIdentifier) }).click();
        const row = adminPage.getByRole("row", { name: new RegExp(email) });
        await expect(row).toBeVisible();

        // Delete it — confirmed by its absence after a reload, not the
        // dialog closing. The row's icon-only buttons carry no accessible
        // name (the `title` prop doesn't reach Chromium's accessibility
        // tree here), so it's targeted by its icon class.
        await row.locator("button:has(.pi-trash)").click();
        await adminPage.getByRole("alertdialog").getByRole("button", { name: "Delete", exact: true }).click();
        await adminPage.reload();
        await adminPage.getByRole("combobox", { name: "Select a client" }).click();
        await adminPage.getByRole("option", { name: new RegExp(clientIdentifier) }).click();
        await expect(adminPage.getByRole("row", { name: new RegExp(email) })).toHaveCount(0);
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
});
