// Go screens: frontend/src/pages/authorization/{RoleListPage,RoleDetailDrawer,
// RoleEditPage,PermissionListPage}.vue, pages/DashboardPage.vue (the
// "Platform Sync" > Roles card — the sync-platform button spec §3 lists
// under this group actually lives on the dashboard, not an authorization
// page).
// Parity scenarios covering the same wire (docs/spec/parity-harness.md):
// the `role` and `permission` BFF groups' list/create/update/sync scenarios.
//
// docs/spec/frontend-e2e.md §3 "authorization".
import { test, expect } from "../fixtures/admin.js";
import { unique } from "../fixtures/clientScoped.js";

test.describe("authorization", () => {
    test("creating, viewing, and editing a role persists its permissions after reload", async ({ adminPage }) => {
        await adminPage.goto("/authorization/roles");

        const roleName = unique("e2e-role");
        const displayName = `E2E Role ${roleName}`;
        // POST /bff/roles answers with only `{id}` (matches Go's
        // `createdResponse` — the frontend never reads more than that back),
        // so the role name is reconstructed from the application picked here
        // rather than read off the response.
        await adminPage.getByRole("button", { name: "Create Role" }).click();
        const createDialog = adminPage.getByRole("dialog", { name: "Create Role" });
        await createDialog.getByRole("combobox").click();
        await adminPage.getByRole("option", { name: "FlowCatalyst Platform" }).click();
        await createDialog.getByPlaceholder("e.g., admin, viewer, manager").fill(roleName);
        await createDialog.getByPlaceholder("e.g., Administrator").fill(displayName);
        const createResponse = adminPage.waitForResponse(
            (r) => r.url().endsWith("/bff/roles") && r.request().method() === "POST",
        );
        await createDialog.getByRole("button", { name: "Create Role", exact: true }).click();
        expect((await createResponse).ok()).toBe(true);
        const role = { name: `platform:${roleName}` };

        // Reloaded proof of creation: navigate away and back into the list.
        await adminPage.goto("/authorization/roles");
        await adminPage.getByText(displayName).click();
        await expect(adminPage).toHaveURL(new RegExp(`/authorization/roles/${encodeURIComponent(role.name)}$`));
        await expect(adminPage.getByText("This role has no permissions assigned")).toBeVisible();

        // Edit: turn on exactly one permission.
        await adminPage.getByRole("button", { name: "Edit", exact: true }).click();
        await expect(adminPage).toHaveURL(/\/edit$/);
        await adminPage.locator(".permission-item").first().click();
        const saveResponse = adminPage.waitForResponse(
            (r) => r.url().endsWith(`/bff/roles/${encodeURIComponent(role.name)}`) && r.request().method() === "PUT",
        );
        await adminPage.getByRole("button", { name: "Save Changes" }).click();
        expect((await saveResponse).ok()).toBe(true);

        // Reloaded proof of the edit: a fresh navigation to the detail
        // drawer, not the editor's own in-memory state.
        await adminPage.goto(`/authorization/roles/${encodeURIComponent(role.name)}`);
        await expect(adminPage.getByText("Permissions (1)")).toBeVisible();
    });

    test("the permissions catalogue grows with a newly created permission", async ({ adminPage }) => {
        await adminPage.goto("/authorization/permissions");
        await expect(adminPage.getByRole("table")).toBeVisible();

        const suffix = unique("e2ectx");
        await adminPage.getByRole("button", { name: "Create Permission" }).click();
        const dialog = adminPage.getByRole("dialog", { name: "Create Permission" });
        await dialog.getByPlaceholder("e.g. billing").fill(suffix);
        await dialog.getByPlaceholder("e.g. invoice").fill(suffix);
        await dialog.getByPlaceholder("e.g. approve").fill("view");
        const created = adminPage.waitForResponse(
            (r) => r.url().endsWith("/bff/roles/permissions") && r.request().method() === "POST",
        );
        await dialog.getByRole("button", { name: "Create Permission", exact: true }).click();
        expect((await created).ok()).toBe(true);

        // Reloaded proof: a fresh fetch of the catalogue finds it by its
        // exact permission code.
        await adminPage.reload();
        await adminPage.getByPlaceholder("Search permissions...").fill(suffix);
        await expect(adminPage.getByText(new RegExp(`:${suffix}:${suffix}:view$`))).toBeVisible();
    });

    test("the dashboard's Roles sync-platform button re-applies the code-defined catalogue", async ({ adminPage }) => {
        await adminPage.goto("/dashboard");
        const syncResponse = adminPage.waitForResponse(
            (r) => r.url().endsWith("/bff/roles/sync-platform") && r.request().method() === "POST",
        );
        await adminPage
            .locator(".sync-card", { hasText: "Roles" })
            .getByRole("button", { name: "Sync", exact: true })
            .click();
        const response = await syncResponse;
        expect(response.ok(), await response.text()).toBe(true);
        const body = (await response.json()) as { total: number };
        expect(typeof body.total).toBe("number");
        expect(body.total).toBeGreaterThan(0);
        await expect(adminPage.getByText("Platform Roles Synced")).toBeVisible();
    });
});
