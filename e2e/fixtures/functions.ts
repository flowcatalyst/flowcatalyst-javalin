// Function-service-specific helpers for tests/functions.spec.ts (E1,
// docs/spec/function-ui.md §6 row E1 / §7 row H4). Generic UI helpers
// (bareInput, confirmedAction, rowWithText, drawer, …) live in
// fixtures/catalogue.ts and are imported directly by the spec file — this
// file only holds what the function flow itself needs: building the
// examples/function-hello manifest in-memory, polling out-of-band host
// state, and two documented workarounds for UI gaps H4 discovered (see the
// big comment below).
import type { APIRequestContext, Page } from "@playwright/test";
import { expect } from "@playwright/test";

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
// Two documented workarounds for real gaps this flow found in the admin
// SPA as merged (commits 8fa6ac78 / c402c98b / 24721348, packages H1–H3).
// Both are cited precisely in the H4 report; neither is "the intended
// shape" of this test — they exist because the step they perform has
// literally no UI path today. Every OTHER step of this flow goes through
// the real UI (claim, publish, promote, hosts, routes, healthz).
// ─────────────────────────────────────────────────────────────────────────

/// **Gap 1 — there is no "Create Function" action anywhere in the SPA.**
/// `frontend/src/router/index.ts`'s `/functions` route has only a
/// `:address` child (no `new`); `FunctionListPage.vue`'s header has no
/// create button; `FunctionDetailDrawer.vue` renders nothing but
/// `loadError = "Function not found"` when `GET /api/functions/{address}`
/// 404s, with no affordance to proceed from there. `functionsApi.create`
/// (wrapping `POST /api/functions`) is exported from `api/functions.ts`
/// but is never called from any page — confirmed by grepping the whole
/// `frontend/src` tree. A brand-new function cannot be created through the
/// admin UI at all; this call is the one prerequisite every subsequent
/// UI-driven step in this flow depends on. Takes `page.request` (which
/// shares the page's session cookie — `frontend/src/api/client.ts` uses
/// `credentials: "include"`, no CSRF header — Playwright's `page.request`
/// is the same `APIRequestContext` the browser's own cookie jar backs).
export async function createFunctionViaApi(request: APIRequestContext): Promise<void> {
    const res = await request.post("/api/functions", {
        data: {
            applicationCode: FN_APPLICATION_CODE,
            serviceName: FN_SERVICE_NAME,
            name: FN_NAME,
            runtime: "jvm",
            description: "E2E function-hello",
        },
    });
    expect(res.ok(), await res.text()).toBe(true);
}

/// **Gap 2 — the Config & Secrets tab cannot set a key before the function
/// has a LIVE version.** `FunctionApi.java`'s `declaredConfig`/
/// `declaredSecrets` (used by both `GET .../config` and `GET .../secrets`)
/// read `liveVersionOf(s, f)` and return `List.of()` when there is none —
/// so before any promote, `FunctionConfigSecretsTab.vue`'s `configRows`/
/// `secretRows` computeds (the union of `declared` and whatever is already
/// in `values`/`keys`) are always empty, and the tab offers no "add a new
/// key" action — only edit/replace buttons on rows that already exist.
/// Meanwhile `PromoteVersion.requireSettingsPresent` (spec
/// `function-context.md` §1) checks the CANDIDATE version's OWN manifest,
/// not the live one — so the real requirement (GREETING/API_KEY set before
/// promoting the function's first version) is impossible to satisfy
/// through the tab: promote's own SETTINGS_MISSING check depends on a
/// value only a UI row driven off the (not-yet-existing) live manifest
/// could ever offer to set. `docs/functions.md`'s own CLI walkthrough
/// sidesteps this by calling `fn secret set`/`fn config set` directly
/// against the API between publish and promote — this does the same over
/// HTTP, since the SPA has no equivalent. Also answers the task's open
/// question: this is NOT the `encrypt:`-prefix CLI convention —
/// `SetFunctionSecret`/`SecretValue` (server) never special-case a prefix;
/// the admin route always stores whatever raw value is PUT, encrypted at
/// rest. `encrypt:` is purely a local `fn secret set` CLI convenience for
/// `db[].secretRef`-style values (secrets-manager references vs. a literal
/// DSN) — irrelevant to this route.
export async function setConfigAndSecretViaApi(
    request: APIRequestContext,
    address: string,
    greeting: string,
    apiKey: string,
): Promise<void> {
    const configRes = await request.put(`/api/functions/${address}/config`, {
        data: { values: { GREETING: greeting } },
    });
    expect(configRes.ok(), await configRes.text()).toBe(true);

    const secretRes = await request.put(`/api/functions/${address}/secrets/API_KEY`, {
        data: { value: apiKey },
    });
    expect(secretRes.ok(), await secretRes.text()).toBe(true);
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
        const row = page.locator("tr", { hasText: `v${version}` });
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
