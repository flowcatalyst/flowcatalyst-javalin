// Go screens: frontend/src/pages/authentication/{OAuthClientListPage,
// OAuthClientCreateDrawer,OAuthClientDetailDrawer,ResetApprovalsPage}.vue,
// authentication/identity-providers/**, authentication/email-domains/**.
// Parity scenarios (docs/spec/parity-harness.md S1/S2 groups): the
// `authentication admin` group's oauth-client/identity-provider/
// email-domain-mapping/reset-approval scenarios.
//
// docs/spec/frontend-e2e.md §3 "authentication admin". Assertions follow
// CLAUDE.md: the observable outcome after a reload, never "the dialog
// closed".
import {
    test,
    expect,
    unique,
    bareField,
    addUri,
    dialogWithHeader,
    confirmedAction,
    rowWithText,
    choosePrimeOption,
    createInternalIdentityProvider, createInternalIdentityProviderViaApi,
    createClientViaApi,
} from "../fixtures/authenticationAdmin.js";

test.describe("authentication admin / oauth clients", () => {
    test("a CONFIDENTIAL client shows its secret once, then never again", async ({ adminPage: page }) => {
        const name = unique("E2E OAuth Confidential");

        await page.goto("/authentication/oauth-clients/new");
        await page.getByLabel("Client Name").fill(name);
        await choosePrimeOption(page, bareField(page, "Client Type", ".field"), "Confidential (Server)");
        await addUri(page, "Redirect URIs", "https://example.com/callback");

        // Take the row id from the create POST itself: a post-click URL
        // regex like /oauth-clients\/[^/]+$/ is also satisfied by the
        // `/oauth-clients/new` create route, so it proved nothing (a
        // reload after it landed back on the create drawer, confirmed from
        // a page snapshot).
        const created = page.waitForResponse((r) => r.url().includes("/api/oauth-clients") && r.request().method() === "POST");
        await page.getByRole("button", { name: "Create OAuth Client", exact: true }).click();
        const createResponse = await created;
        expect(createResponse.ok(), await createResponse.text()).toBe(true);
        const { client } = (await createResponse.json()) as { client: { id: string } };

        const secretDialog = dialogWithHeader(page, "Client Secret Generated");
        await expect(secretDialog).toBeVisible();
        const secret = (await secretDialog.locator(".secret-code").last().innerText()).trim();
        expect(secret.length).toBeGreaterThan(0);
        await secretDialog.getByRole("button", { name: "I've copied the secret" }).click();

        // Reloaded proof, on the detail page: the secret is gone from the
        // UI, and CONFIDENTIAL clients get a "Rotate Secret" affordance.
        await page.goto(`/authentication/oauth-clients/${client.id}`);
        await expect(page.getByText(secret)).toHaveCount(0);
        const rotateButton = page.getByRole("button", { name: "Rotate Secret", exact: true });
        await expect(rotateButton).toBeVisible();

        await rotateButton.click();
        await dialogWithHeader(page, "Rotate Client Secret").getByRole("button", { name: "Rotate Secret", exact: true }).click();
        const newSecretDialog = dialogWithHeader(page, "New Client Secret");
        await expect(newSecretDialog).toBeVisible();
        const newSecret = (await newSecretDialog.locator(".secret-code").innerText()).trim();
        expect(newSecret).not.toBe(secret);
        await newSecretDialog.getByRole("button", { name: "I've copied the secret" }).click();

        await page.reload();
        await expect(page.getByText(newSecret)).toHaveCount(0);
    });

    test("a PUBLIC client has no client-secret section, deactivates, then is deleted", async ({ adminPage: page }) => {
        const name = unique("E2E OAuth Public");

        await page.goto("/authentication/oauth-clients/new");
        await page.getByLabel("Client Name").fill(name);
        // Client Type defaults to Public — no secret dialog on submit.
        await addUri(page, "Redirect URIs", "https://example.com/callback");
        const created = page.waitForResponse((r) => r.url().includes("/api/oauth-clients") && r.request().method() === "POST");
        await page.getByRole("button", { name: "Create OAuth Client", exact: true }).click();
        const createResponse = await created;
        expect(createResponse.ok(), await createResponse.text()).toBe(true);
        const { client } = (await createResponse.json()) as { client: { id: string } };

        // On the detail page (navigated by id — see the CONFIDENTIAL test on
        // why a URL regex is not proof), PUBLIC clients have no secret
        // section at all.
        await page.goto(`/authentication/oauth-clients/${client.id}`);
        await expect(page.getByRole("button", { name: "Edit", exact: true })).toBeVisible();
        await expect(page.getByRole("heading", { name: "Client Secret" })).toHaveCount(0);
        await expect(page.getByRole("button", { name: "Rotate Secret", exact: true })).toHaveCount(0);

        await page.goto("/authentication/oauth-clients");
        const row = rowWithText(page, name);
        await expect(row).toBeVisible();
        await expect(row).toContainText("Active");

        await row.click();
        await page.getByRole("button", { name: "Deactivate", exact: true }).click();
        await page.reload();
        // .first() — the underlying list row (still mounted beneath the
        // open drawer) renders the same status Tag text a second time.
        await expect(page.getByText("Inactive").first()).toBeVisible();

        await page.goto("/authentication/oauth-clients");
        await rowWithText(page, name).locator("button:has(.pi-trash)").click();
        await dialogWithHeader(page, "Delete OAuth Client").getByRole("button", { name: "Delete", exact: true }).click();
        await page.reload();
        await expect(rowWithText(page, name)).toHaveCount(0);
    });
});

test.describe("authentication admin / identity providers", () => {
    test("create, edit, then delete — reflected after reload", async ({ adminPage: page }) => {
        // The create drawer omits `oidcMultiTenant` for an INTERNAL provider while the
        // lockfile requires it, so both servers answer 400 VALIDATION and the SPA
        // never leaves the drawer (docs/backlog.md). `test.fail` keeps the flow running;
        // it flips to an unexpected pass when the SPA or the contract is fixed.
        test.fail(true, "SPA/contract defect on both sides: INTERNAL identity-provider create omits the required oidcMultiTenant");
        const { name } = await createInternalIdentityProvider(page);

        await page.goto("/authentication/identity-providers");
        await expect(rowWithText(page, name)).toBeVisible();

        await rowWithText(page, name).click();
        const newName = unique("E2E Renamed IdP");
        await page.getByRole("button", { name: "Edit", exact: true }).click();
        await page.getByLabel("Name").fill(newName);
        await page.getByRole("button", { name: "Save Changes", exact: true }).click();
        await page.reload();
        // Not a bare getByText(newName) — the underlying list row (still
        // mounted beneath the open drawer) renders the same name a second
        // time; the drawer's own title heading is unique to it.
        await expect(page.getByRole("heading", { name: newName })).toBeVisible();

        await confirmedAction(page, "Delete", "Delete Identity Provider", "Delete");
        await expect(page).toHaveURL(/\/authentication\/identity-providers$/);
        await page.reload();
        await expect(rowWithText(page, newName)).toHaveCount(0);
    });
});

test.describe("authentication admin / email domain mappings", () => {
    test("an ANCHOR-scope mapping (the design's 'anchor domains') is created and deleted", async ({ adminPage: page }) => {
        const { name: idpName } = await createInternalIdentityProviderViaApi(page);
        const domain = `${unique("e2e-anchor")}.example.com`;

        await page.goto("/authentication/email-domain-mappings/new");
        await page.getByLabel("Email Domain").fill(domain);
        await choosePrimeOption(page, page.locator(".fc-form-field", { hasText: "Identity Provider" }), idpName);
        await choosePrimeOption(page, page.locator(".fc-form-field", { hasText: "Scope Type" }), "Anchor");
        await page.getByRole("button", { name: "Create Mapping", exact: true }).click();
        await expect(page).toHaveURL(/\/authentication\/email-domain-mappings\/[^/]+$/);

        await page.goto("/authentication/email-domain-mappings");
        const row = rowWithText(page, domain);
        await expect(row).toBeVisible();
        await expect(row).toContainText("ANCHOR");

        await row.locator("button:has(.pi-trash)").click();
        await dialogWithHeader(page, "Delete Email Domain Mapping").getByRole("button", { name: "Delete", exact: true }).click();
        await page.reload();
        await expect(rowWithText(page, domain)).toHaveCount(0);
    });

    test("a CLIENT-scope mapping requires and records a primary client", async ({ adminPage: page }) => {
        const { name: idpName } = await createInternalIdentityProviderViaApi(page);
        const client = await createClientViaApi(page.request);
        const domain = `${unique("e2e-client-domain")}.example.com`;

        await page.goto("/authentication/email-domain-mappings/new");
        await page.getByLabel("Email Domain").fill(domain);
        await choosePrimeOption(page, page.locator(".fc-form-field", { hasText: "Identity Provider" }), idpName);
        // Scope Type defaults to "Client" already.
        const createButton = page.getByRole("button", { name: "Create Mapping", exact: true });
        await expect(createButton).toBeDisabled(); // refused: no primary client chosen yet

        const clientField = page.locator(".fc-form-field", { hasText: "Primary Client" });
        await clientField.getByRole("combobox").fill(client.name);
        await page.getByRole("option", { name: client.name }).first().click();
        await expect(createButton).toBeEnabled();
        await createButton.click();
        await expect(page).toHaveURL(/\/authentication\/email-domain-mappings\/[^/]+$/);

        await page.goto("/authentication/email-domain-mappings");
        const row = rowWithText(page, domain);
        await expect(row).toBeVisible();
        await expect(row).toContainText("CLIENT");
        await expect(row).toContainText(client.id);
    });
});

test.describe("authentication admin / reset approvals", () => {
    test("the queue is empty by design (Java ruling I-Q19 keeps the approval trigger off)", async ({ adminPage: page }) => {
        // docs/backlog.md "Batch A auth rulings": requireStrongFactorForReset
        // is permanently false on Java, so no self-service reset ever queues
        // an approval request here, and there is no admin-facing endpoint to
        // create one directly — see this file group's report for detail.
        await page.goto("/authentication/reset-approvals");
        await expect(page.getByRole("heading", { name: "Password reset approvals" })).toBeVisible();
        await expect(page.getByText("No pending reset requests.")).toBeVisible();
    });
});
