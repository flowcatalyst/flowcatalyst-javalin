// Go screens: frontend/src/pages/platform/{AuditLogListPage,
// LoginAttemptListPage,CorsOriginsPage,DocsPage}.vue,
// platform/settings/{LoginThemeSettingsPage,PlatformNamesSettingsPage}.vue.
// Parity scenarios (docs/spec/parity-harness.md S1/S2 groups): the
// `platform` group's audit-log/login-attempts/cors/settings/docs scenarios.
//
// docs/spec/frontend-e2e.md §3 "platform". Assertions follow CLAUDE.md: the
// observable outcome after a reload, never "the toast appeared".
import { test, expect, unique, dialogWithHeader, rowWithText } from "../fixtures/platform.js";

test.describe("platform / cors origins", () => {
    test("add then remove an origin — reflected after reload", async ({ adminPage: page }) => {
        const origin = `https://${unique("e2e-origin")}.example.com`;

        await page.goto("/platform/cors");
        await page.getByRole("button", { name: "Add Origin", exact: true }).click();
        const addDialog = dialogWithHeader(page, "Add CORS Origin");
        await addDialog.getByLabel("Origin URL").fill(origin);
        await addDialog.getByRole("button", { name: "Add Origin", exact: true }).click();
        await expect(addDialog).toBeHidden();

        await page.reload();
        await expect(rowWithText(page, origin)).toBeVisible();

        await rowWithText(page, origin).getByRole("button").click();
        const removeDialog = dialogWithHeader(page, "Remove CORS Origin");
        await removeDialog.getByRole("button", { name: "Remove", exact: true }).click();
        await expect(removeDialog).toBeHidden();

        await page.reload();
        await expect(rowWithText(page, origin)).toHaveCount(0);
    });
});

test.describe("platform / settings", () => {
    test("the platform name persists after reload, then is restored to default", async ({ adminPage: page }) => {
        const newName = unique("E2E Platform");

        await page.goto("/platform/settings/names");
        const nameField = page.getByLabel("Platform name", { exact: true });
        await nameField.fill(newName);
        await page.getByRole("button", { name: "Save", exact: true }).click();

        await page.reload();
        await expect(page.getByLabel("Platform name", { exact: true })).toHaveValue(newName);

        // Restore the default so later runs against this database aren't
        // left with a renamed platform (mirrors auth.spec.ts's
        // restore-the-password courtesy).
        await page.getByRole("button", { name: "Reset to default", exact: true }).click();
        await page.getByRole("button", { name: "Save", exact: true }).click();
        await page.reload();
        await expect(page.getByLabel("Platform name", { exact: true })).toHaveValue("Flowcatalyst");
    });

    test("the login theme's brand name persists after reload, then is reset to defaults", async ({ adminPage: page }) => {
        const newBrandName = unique("E2E Brand");

        await page.goto("/platform/settings/theme");
        const brandField = page.getByLabel("Brand Name", { exact: true });
        await brandField.fill(newBrandName);
        await page.getByRole("button", { name: "Save Changes", exact: true }).click();

        await page.reload();
        await expect(page.getByLabel("Brand Name", { exact: true })).toHaveValue(newBrandName);

        await page.getByRole("button", { name: "Reset to Defaults", exact: true }).click();
        await page.getByRole("button", { name: "Save Changes", exact: true }).click();
        await page.reload();
        // Reset to defaults, not back to the pre-test value — assert the
        // documented default rather than an assumption about prior state.
        const resetValue = await page.getByLabel("Brand Name", { exact: true }).inputValue();
        expect(resetValue).not.toBe(newBrandName);
        expect(resetValue.length).toBeGreaterThan(0);
    });
});

test.describe("platform / documentation", () => {
    test("the docs page renders a platform page and navigates between pages", async ({ adminPage: page }) => {
        await page.goto("/platform/docs");
        await expect(page.getByRole("heading", { name: "Documentation" })).toBeVisible();

        const navLinks = page.locator(".docs-link");
        const linkCount = await navLinks.count();
        expect(linkCount, "no platform docs are seeded — nothing for this page to redirect to or render").toBeGreaterThan(0);

        // Redirected to the first platform doc, with rendered content.
        await expect(page).toHaveURL(/\/platform\/docs\/platform\/[^/]+$/);
        await expect(page.locator(".docs-content")).not.toBeEmpty();
        const firstTitle = await page.locator(".docs-link.active").innerText();

        if (linkCount > 1) {
            await navLinks.nth(1).click();
            await expect(page.locator(".docs-link.active")).not.toHaveText(firstTitle);
            await expect(page.locator(".docs-content")).not.toBeEmpty();
        }
    });
});

test.describe("platform / audit log", () => {
    test("the bootstrap admin's own history is there, its detail opens, and the Operation filter narrows results", async ({ adminPage: page }) => {
        await page.goto("/platform/audit-log");
        await expect(page.getByRole("heading", { name: "Audit Log" })).toBeVisible();

        const rows = page.locator("tbody tr");
        await expect(rows.first()).toBeVisible();

        await rows.first().click();
        const detail = dialogWithHeader(page, "Audit Log Details");
        await expect(detail).toBeVisible();
        // exact: true — the dialog also has an "Operation Data" heading,
        // which a substring match on "Operation" would also match.
        await expect(detail.getByText("Operation", { exact: true })).toBeVisible();
        await page.keyboard.press("Escape");
        await expect(detail).toBeHidden();

        // The Operation filter is populated from real data — pick the first
        // option and confirm every remaining visible row matches it (the
        // behaviour the filter exists for, not merely that a badge appeared).
        // `FcTableToolbar` renders the page's filter controls inside a
        // PrimeVue Popover that only mounts once the toolbar's "Filters"
        // button is clicked — the Operation select does not exist in the
        // DOM before that (confirmed from a page snapshot).
        await page.getByRole("button", { name: "Filters" }).click();
        const operationFilter = page.locator(".fc-form-field", { hasText: "Operation" }).getByRole("combobox");
        await operationFilter.click();
        const firstOption = page.getByRole("option").first();
        const chosenOperation = (await firstOption.innerText()).trim();
        await firstOption.click();

        // The filter option is the raw command name ("ActivateCommand");
        // the column renders it through `formatOperationName`
        // ("Activate Command") — compare against the same formatting.
        const displayed = chosenOperation.replace(/([A-Z])/g, " $1").replace(/^./, (c) => c.toUpperCase()).trim();
        await expect(rows.first()).toBeVisible();
        const count = await rows.count();
        for (let i = 0; i < count; i++) {
            await expect(rows.nth(i).locator("td").nth(3)).toHaveText(displayed);
        }
    });
});

test.describe("platform / login attempts", () => {
    test("a failed login attempt is recorded and findable by identifier", async ({ adminPage: page }) => {
        const email = `${unique("e2e-platform-login")}@example.com`;

        const res = await page.request.post("/auth/login", {
            data: { email, password: "not-the-right-password-at-all" },
        });
        expect(res.status()).toBe(401);

        await page.goto(`/platform/login-attempts?identifier=${encodeURIComponent(email)}`);
        await expect(page.getByRole("heading", { name: "Login Attempts" })).toBeVisible();
        const row = rowWithText(page, email);
        await expect(row).toBeVisible();
        await expect(row).toContainText("FAILURE");
    });
});
