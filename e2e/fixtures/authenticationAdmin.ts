import { test as base, expect, type APIRequestContext, type Locator, type Page } from "@playwright/test";
import { loginAndLand, ADMIN_EMAIL, ADMIN_PASSWORD } from "./admin.js";

/// A `page` already signed in as the bootstrap admin.
export const test = base.extend<{ adminPage: Page }>({
    adminPage: async ({ page }, use) => {
        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        await use(page);
    },
});

export { expect };

export function unique(prefix: string): string {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/// The OAuth Client create/edit forms label most fields with a real
/// `for`/`id` pair (`getByLabel` works) — EXCEPT the repeatable URI-list
/// fields (Redirect URIs, Post-Logout Redirect URIs, Allowed CORS Origins),
/// which use a bare `<label>` next to a "type + click plus" input pair.
/// This locates that bare field's own text input by its label text.
export function bareField(page: Page, label: string, containerClass = ".field"): Locator {
    // Anchored on the field's own `<label>` text (allowing the trailing
    // required "*"), not a substring match anywhere in the container — see
    // fixtures/catalogue.ts's bareField for the collision this prevents.
    const anchored = new RegExp(`^\\s*${label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*\\*?\\s*$`);
    return page.locator(containerClass).filter({ has: page.locator("label", { hasText: anchored }) }).first();
}

export function bareInput(page: Page, label: string, containerClass = ".field"): Locator {
    return bareField(page, label, containerClass).locator("input, textarea").first();
}

/// Types a URI into the given repeatable-list field's own input and clicks
/// its adjacent "+" button (every such field in this group follows this
/// exact shape: `OAuthClientCreateDrawer`'s Redirect URIs, Post-Logout
/// Redirect URIs and Allowed CORS Origins).
export async function addUri(page: Page, fieldLabel: string, uri: string): Promise<void> {
    const field = bareField(page, fieldLabel);
    await field.locator("input").fill(uri);
    await field.getByRole("button").first().click();
}

export function dialogWithHeader(page: Page, header: string | RegExp): Locator {
    return page.getByRole("dialog", { name: header });
}

export async function confirmedAction(
    page: Page,
    triggerName: string | RegExp,
    dialogHeader: string | RegExp,
    acceptName: string | RegExp,
): Promise<void> {
    await page.getByRole("button", { name: triggerName, exact: true }).click();
    const dialog = dialogWithHeader(page, dialogHeader);
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: acceptName, exact: true }).click();
    await expect(dialog).toBeHidden();
}

export function rowWithText(page: Page, text: string | RegExp): Locator {
    return page.locator("tr", { hasText: text });
}

export async function choosePrimeOption(page: Page, container: Locator, optionText: string | RegExp): Promise<void> {
    await container.getByRole("combobox").click();
    await page.getByRole("option", { name: optionText }).first().click();
}

/// Creates an INTERNAL identity provider (the simplest valid provider —
/// no OIDC fields required) via `/authentication/identity-providers/new`.
export async function createInternalIdentityProvider(page: Page, opts?: { code?: string; name?: string }): Promise<{ code: string; name: string; id: string }> {
    const code = opts?.code ?? unique("e2e-idp");
    const name = opts?.name ?? `E2E Identity Provider ${code}`;

    await page.goto("/authentication/identity-providers/new");
    await page.getByLabel("Code").fill(code);
    await page.getByLabel("Name").fill(name);
    // The Type select DEFAULTS TO "OIDC (External)" (`form.value.type =
    // "OIDC"`, verified in `IdentityProviderCreateDrawer.vue`) — the
    // drawer's own `isValid` then requires an Issuer URL and Client ID and
    // the submit button never enables without them. "Internal (Local)"
    // must be chosen explicitly for the code+name-only path this helper
    // promises.
    await choosePrimeOption(page, page.locator(".field", { hasText: "Type" }), "Internal (Local)");
    await page.getByRole("button", { name: "Create Identity Provider", exact: true }).click();
    await expect(page).toHaveURL(/\/authentication\/identity-providers\/[^/]+$/);

    const id = new URL(page.url()).pathname.split("/").pop()!;
    return { code, name, id };
}

/// Creates an INTERNAL identity provider through the admin API, with the
/// `oidcMultiTenant` member the lockfile requires on both sides — for flows
/// that only need a provider to exist (the email-domain-mapping drawer's
/// picker). The UI path above is pinned separately as an SPA defect: the
/// create drawer omits `oidcMultiTenant` for non-OIDC providers and both
/// servers refuse the body (`docs/backlog.md`).
export async function createInternalIdentityProviderViaApi(page: Page, opts?: { code?: string; name?: string }): Promise<{ code: string; name: string; id: string }> {
    const code = opts?.code ?? unique("e2e-idp");
    const name = opts?.name ?? `E2E Identity Provider ${code}`;
    const res = await page.request.post("/api/identity-providers", {
        data: { code, name, type: "INTERNAL", oidcMultiTenant: false },
    });
    if (res.status() !== 201) {
        throw new Error(`createInternalIdentityProviderViaApi: ${res.status()} ${await res.text()}`);
    }
    const body = (await res.json()) as { id: string };
    return { code, name, id: body.id };
}

/// Creates a client via the admin API — a prerequisite for a CLIENT-scoped
/// email domain mapping. Client CRUD itself is E2E-A's tenancy group; this
/// exists only to satisfy that one required field, not to exercise the
/// Clients screen.
export async function createClientViaApi(request: APIRequestContext): Promise<{ id: string; name: string }> {
    const identifier = unique("e2e-authadmin-client");
    const name = `E2E Auth Admin Client ${identifier}`;
    const res = await request.post("/api/clients", {
        data: { name, identifier },
    });
    if (!res.ok()) {
        throw new Error(`createClientViaApi: POST /api/clients -> ${res.status()} ${await res.text()}`);
    }
    // POST /api/clients answers with the `CreatedResponse` envelope — `{ id }`
    // only, no echo of the name — so the name comes from what was sent.
    const { id } = (await res.json()) as { id: string };
    return { id, name };
}
