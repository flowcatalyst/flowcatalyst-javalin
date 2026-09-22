// Function-service-specific helpers for tests/functions.spec.ts (E1,
// docs/spec/function-ui.md §6 row E1 / §7 row H4). Generic UI helpers
// (bareInput, confirmedAction, rowWithText, drawer, …) live in
// fixtures/catalogue.ts and are imported directly by the spec file — this
// file only holds what the function flow itself needs: building the
// examples/function-hello manifest in-memory, polling out-of-band host
// state, and two documented workarounds for UI gaps H4 discovered (see the
// big comment below).
import type { Page } from "@playwright/test";
import { bareInput, expect, submitDrawer } from "./catalogue.js";

/// The three-label address `applicationCode.serviceName.name` this flow
/// exercises — fixed, not `unique()`-suffixed, because it must match the
/// event type codes baked into examples/function-hello/manifest.json
/// (`hello:greeting:greeting:{requested,sent}` — the application segment
/// "hello" cannot be renamed without forking the sample). Spec E1 is
/// explicitly "(integration pin)", a single fixed scenario, not a fuzzed one.
export const FN_APPLICATION_CODE = "hello";
export const FN_SERVICE_NAME = "default";
export const FN_NAME = "hello";
export const FN_ADDRESS = `${FN_APPLICATION_CODE}.${FN_SERVICE_NAME}.${FN_NAME}`;

export const FN_DOMAIN_HOSTNAME = "hello.localhost";

/// `fcdev start`'s function-host public listener default (`--fn-public-port`,
/// docs/functions.md §6a / docs/spec/function-developer-surface.md §1) —
/// fixed at 8091 because `e2e/runner/side.ts` does not thread a
/// `--fn-public-port` flag through `startArgs()` (shared with the Go
/// launcher, whose CLI has no such flag at all, so adding it there would
/// break the Go side of `pnpm e2e:both`). Only one side ever runs a
/// function host, so the fixed default cannot collide.
export const FN_PUBLIC_PORT = 8091;

/// examples/function-hello/manifest.json, reproduced here (not read off
/// disk) so the flow can add the one field the sample's own manifest does
/// not carry — `public[]` — without writing a temp file to disk.
/// `JSON.stringify`d straight into an in-memory buffer for
/// `setInputFiles` (Playwright accepts `{name, mimeType, buffer}`, no
/// filesystem round-trip needed — task brief's "the flow can write a temp
/// manifest file" is satisfied in memory instead).
export function helloManifestWithPublicRoute(): Record<string, unknown> {
    return {
        runtime: "jvm",
        entrypoint: "io.flowcatalyst.example.hello.HelloFunction",
        pool: "default",
        // The sample's own manifest.json says `false` (lazy: loaded on
        // first call) — this flow flips it to `true` so the Hosts panel
        // shows LOADED right after promote, as task step 6 asks for,
        // rather than REGISTERED-until-first-invocation
        // (docs/function-service-overview.md §7: "load what is live (warm
        // now, lazy on first call)" — confirmed against a live run: with
        // `warm: false` the version reached READY (a host loaded and
        // verified the candidate) and promote succeeded, but the Hosts
        // panel never showed LOADED within a 63s poll budget, only after
        // this flag flipped).
        warm: true,
        limits: { maxDurationMs: 10000, maxConcurrency: 8 },
        endpoints: [
            { path: "/events/greeting-requested", auth: "webhook" },
            { path: "/api/hello/{name}", auth: "platform", methods: ["GET"] },
            { path: "/healthz", auth: "none", methods: ["GET"] },
        ],
        subscriptions: [
            {
                eventType: "hello:greeting:greeting:requested",
                path: "/events/greeting-requested",
                mode: "IMMEDIATE",
                maxRetries: 3,
                timeoutSeconds: 30,
                dataOnly: false,
            },
        ],
        // The one addition over the sample's own manifest.json (task step 4).
        public: [{ hostname: FN_DOMAIN_HOSTNAME }],
        config: ["GREETING"],
        secrets: ["API_KEY"],
    };
}

// ─────────────────────────────────────────────────────────────────────────
// Two real gaps this flow originally found in the admin SPA (commits
// 8fa6ac78 / c402c98b / 24721348, packages H1–H3) — both now closed
// (docs/functions.md §12): a create drawer at `/functions/new`
// (FunctionCreateDrawer.vue) and a Config & Secrets tab that derives its
// declared keys from the union of the live manifest and every non-retired
// version's own manifest, with an explicit "Add key" row on each table
// (FunctionConfigSecretsTab.vue). Every step of this flow now goes through
// the real UI (create, claim, publish, configure, promote, hosts, routes,
// healthz).
// ─────────────────────────────────────────────────────────────────────────

/// Creates `hello.default.hello` through the real Create Function drawer
/// (`/functions/new`) — the one prerequisite every subsequent UI-driven
/// step in this flow depends on. Lands the browser on the new function's
/// own detail drawer (`FunctionCreateDrawer.vue`'s `replaceToDetail` on
/// success), so the caller has no further navigation to do.
export async function createFunctionViaUi(page: Page): Promise<void> {
    await page.goto("/functions/new");
    await bareInput(page, "Application Code").fill(FN_APPLICATION_CODE);
    await bareInput(page, "Service").fill(FN_SERVICE_NAME);
    await bareInput(page, "Name").fill(FN_NAME);

    const created = await submitDrawer<{ address: string }>(
        page,
        "Create Function",
        "/api/functions",
    );
    expect(created.address).toBe(FN_ADDRESS);
    await expect(page).toHaveURL(new RegExp(`/functions/${FN_ADDRESS}$`));
}

/// Sets GREETING (config) and API_KEY (secret) through the Config & Secrets
/// tab's "Add key" rows — the affordance that lets a key be set even
/// before any manifest has been loaded (the exact case this flow is in
/// right after publish: the version is PUBLISHED, not yet READY, so
/// `FunctionConfigSecretsTab.vue`'s union already has GREETING/API_KEY as
/// declared-but-unset rows too, but this exercises the always-available
/// path). `page.getByTestId` — both rows carry explicit `data-testid`s
/// (`FunctionConfigSecretsTab.vue`: `add-config-key-input`/
/// `add-config-value-input`/`add-config-key-button`, and the `secret-`
/// equivalents). Also answers a question the original API-based workaround
/// raised: this is NOT the `encrypt:`-prefix CLI convention —
/// `SetFunctionSecret`/`SecretValue` (server) never special-case a prefix;
/// the admin route always stores whatever raw value is PUT, encrypted at
/// rest. `encrypt:` is purely a local `fn secret set` CLI convenience for
/// `db[].secretRef`-style values (secrets-manager references vs. a literal
/// DSN) — irrelevant to this route.
export async function setConfigAndSecretViaUi(
    page: Page,
    address: string,
    greeting: string,
    apiKey: string,
): Promise<void> {
    await page.getByRole("tab", { name: "Config & Secrets", exact: true }).click();

    const configResponse = page.waitForResponse(
        (r) =>
            new URL(r.url()).pathname === `/api/functions/${address}/config` &&
            r.request().method() === "PUT",
    );
    await page.getByTestId("add-config-key-input").fill("GREETING");
    await page.getByTestId("add-config-value-input").fill(greeting);
    await page.getByTestId("add-config-key-button").click();
    const configRes = await configResponse;
    const configResBody = await configRes.text().catch(() => "<body discarded by the browser after navigation>");
expect(configRes.ok(), configResBody).toBe(true);

    const secretResponse = page.waitForResponse(
        (r) =>
            new URL(r.url()).pathname === `/api/functions/${address}/secrets/API_KEY` &&
            r.request().method() === "PUT",
    );
    await page.getByTestId("add-secret-key-input").fill("API_KEY");
    await page.getByTestId("add-secret-value-input").fill(apiKey);
    await page.getByTestId("add-secret-key-button").click();
    const secretRes = await secretResponse;
    const secretResBody = await secretRes.text().catch(() => "<body discarded by the browser after navigation>");
expect(secretRes.ok(), secretResBody).toBe(true);
}

/// Polls (bounded by `timeoutMs`, derived by the caller from the runner's
/// own per-flow budget — never a fixed sleep) by reloading the Versions tab
/// until the given version's row shows the wanted state tag. The tab has no
/// live polling of its own (`FunctionVersionsTab.vue` only reloads on
/// address change or after an action), so this is the same "reload and look
/// at the DOM" idiom every other flow in this suite uses to observe state
/// that changes out from under the page — the in-process fcdev host's own
/// reconcile loop, here.
export async function waitForVersionState(
    page: Page,
    version: number,
    state: string,
    timeoutMs: number,
): Promise<void> {
    await expect(async () => {
        await page.reload();
        await page.getByRole("tab", { name: "Versions", exact: true }).click();
        // Scoped to the versions table: the Config & Secrets tab also names
        // versions ("v1 (ready)") in its source column, and the drawer keeps
        // inactive tab panels mounted.
        const row = page.locator(".versions-tab tr", { hasText: `v${version}` });
        await expect(row.getByText(state, { exact: true })).toBeVisible();
    }).toPass({ timeout: timeoutMs, intervals: [1000, 2000, 3000, 5000] });
}

/// Same idiom as [waitForVersionState], for the Overview tab's Hosts panel
/// (`FunctionDetailDrawer.vue`'s `.hosts-table`): polls until some host
/// reports `v{version} {state}` for this function.
export async function waitForHostState(
    page: Page,
    version: number,
    state: string,
    timeoutMs: number,
): Promise<void> {
    await expect(async () => {
        await page.reload();
        await page.getByRole("tab", { name: "Overview", exact: true }).click();
        await expect(page.getByText(`v${version} ${state}`, { exact: true })).toBeVisible();
    }).toPass({ timeout: timeoutMs, intervals: [1000, 2000, 3000, 5000] });
}
