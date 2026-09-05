import { test as base, expect, type APIRequestContext, type Page } from "@playwright/test";
import { loginAndLand, loginViaUi } from "./admin.js";
import { ADMIN_EMAIL, ADMIN_PASSWORD } from "./admin.js";

/// A client and a `CLIENT`-scoped user under it, created through the admin
/// API (`POST /api/clients`, `POST /api/principals/users` —
/// `docs/spec/frontend-e2e.md` §3's tenancy group; scaffolded here so this
/// unit's file layout matches the brief, not exercised by the auth spec).
export interface ClientScopedPrincipal {
    clientId: string;
    clientIdentifier: string;
    email: string;
    password: string;
}

/// A short, run-unique suffix so repeated runs against the same fresh
/// database (there's exactly one per run, but a retried test reuses it)
/// never collide on `identifier`/`email` uniqueness constraints.
function unique(prefix: string): string {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/// Creates the client + user as the admin, via `request` (a context that
/// already carries the admin's session cookie — see [adminApiContext]).
export async function createClientScopedPrincipal(request: APIRequestContext): Promise<ClientScopedPrincipal> {
    const identifier = unique("e2e-client");
    const clientRes = await request.post("/api/clients", {
        data: { name: `E2E Client ${identifier}`, identifier },
    });
    if (!clientRes.ok()) {
        throw new Error(`createClientScopedPrincipal: POST /api/clients -> ${clientRes.status()} ${await clientRes.text()}`);
    }
    const client = (await clientRes.json()) as { id: string; identifier: string };

    const email = `${unique("e2e-user")}@example.com`;
    const password = "Kolkata-Ferry-4471!";
    const userRes = await request.post("/api/principals/users", {
        data: { email, name: "E2E Client User", password, scope: "CLIENT", clientId: client.id },
    });
    if (!userRes.ok()) {
        throw new Error(`createClientScopedPrincipal: POST /api/principals/users -> ${userRes.status()} ${await userRes.text()}`);
    }

    return { clientId: client.id, clientIdentifier: client.identifier, email, password };
}

/// Extends the admin fixture with `clientScoped`: the admin session used to
/// mint the client/user (via `adminPage.request`, which shares the logged-in
/// browser context's cookies with the API), and `clientScopedPage` — a
/// separate, freshly-authenticated page signed in as that client-scoped
/// user, for the confinement assertions the tenancy group makes.
export const test = base.extend<{ clientScoped: ClientScopedPrincipal; clientScopedPage: Page }>({
    clientScoped: async ({ page }, use) => {
        await loginAndLand(page, ADMIN_EMAIL, ADMIN_PASSWORD);
        const principal = await createClientScopedPrincipal(page.request);
        await use(principal);
    },
    clientScopedPage: async ({ page, clientScoped }, use) => {
        await loginViaUi(page, clientScoped.email, clientScoped.password);
        await use(page);
    },
});

export { expect };
