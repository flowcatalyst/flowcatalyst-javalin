// Function-service-specific helpers for tests/functions.spec.ts (E1,
// docs/spec/function-ui.md §6 row E1 / §7 row H4). Generic UI helpers
// (bareInput, confirmedAction, rowWithText, drawer, …) live in
// fixtures/catalogue.ts and are imported directly by the spec file — this
// file only holds what the function flow itself needs: building the
// examples/function-hello manifest in-memory, polling out-of-band host
// state, and two documented workarounds for UI gaps H4 discovered (see the
// big comment below).
import type { Locator, Page } from "@playwright/test";
import {
    bareField,
    bareInput,
    choosePrimeOption,
    confirmWithHeader,
    dialogWithHeader,
    expect,
    submitDrawer,
} from "./catalogue.js";

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

/// Package J3's derived-hostname prefix this flow opts into (see
/// `helloManifestWithPublicRoute`'s `aliasPrefixes`) and its never-opted-in
/// counterpart, used to prove the negative (spec §8 row P3/E1: an
/// unopted-in prefix stays 404).
export const FN_ALIAS_PREFIX = "qa";
export const FN_ALIAS_HOSTNAME = `${FN_ALIAS_PREFIX}-${FN_DOMAIN_HOSTNAME}`;
export const FN_UNOPTED_PREFIX_HOSTNAME = `staging-${FN_DOMAIN_HOSTNAME}`;

/// `fcdev start`'s function-host public listener default (`--fn-public-port`,
/// docs/functions.md §6a / docs/spec/function-developer-surface.md §1) —
/// fixed at 8091 because `e2e/runner/side.ts` does not thread a
/// `--fn-public-port` flag through `startArgs()` (shared with the Go
/// launcher, whose CLI has no such flag at all, so adding it there would
/// break the Go side of `pnpm e2e:both`). Only one side ever runs a
/// function host, so the fixed default cannot collide.
export const FN_PUBLIC_PORT = 8091;

/// The Wasm/JS flow's own address (`docs/spec/function-wasm-platform-ui.md` §4) — a DIFFERENT
/// application code from [FN_APPLICATION_CODE] so the two flows' functions, applications and
/// domain claims never collide when both run against the same `fcdev` instance in one file.
export const FN_JS_APPLICATION_CODE = "hellojs";
export const FN_JS_SERVICE_NAME = "default";
export const FN_JS_NAME = "hello";
export const FN_JS_ADDRESS = `${FN_JS_APPLICATION_CODE}.${FN_JS_SERVICE_NAME}.${FN_JS_NAME}`;
export const FN_JS_DOMAIN_HOSTNAME = "hellojs.localhost";

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
        // `aliasPrefixes: ["qa"]` opts this route into package J3's derived
        // hostname (`qa-hello.localhost`) — the J4 e2e flow points the `qa`
        // alias at the published version and reaches it that way, and
        // checks `staging-hello.localhost` (never opted in) stays 404.
        public: [{ hostname: FN_DOMAIN_HOSTNAME, aliasPrefixes: ["qa"] }],
        config: ["GREETING"],
        secrets: ["API_KEY"],
    };
}

/// examples/function-hello-js/manifest.json, reproduced the same way
/// [helloManifestWithPublicRoute] reproduces the JVM sample's — the same two
/// adaptations: `warm` flipped to `true` (so the Hosts panel shows LOADED
/// right after promote) and a `public[]` route added under
/// [FN_JS_DOMAIN_HOSTNAME] so this test can reach the module exactly as the
/// JVM flow reaches its own function (docs/spec/function-wasm-platform-ui.md
/// §4). `subscriptions` is dropped: the sample's own manifest subscribes to
/// `hello:greeting:greeting:requested`, an event type owned by the OTHER
/// flow's application ("hello") — this flow never exercises that webhook
/// path (nothing here asserts an emitted event or a delivery outcome), so
/// carrying the subscription would only add an app/service-account/event-type
/// dependency on the JVM flow's own fixtures for zero assertion value, and
/// would make `FunctionTriggerSync.checkApplicationSigningSecret` apply
/// (it only runs when `subscriptions`/`schedules` is non-empty). `endpoints`
/// keeps only `/healthz` for the same reason — it is the only path this test
/// calls.
export function helloJsManifestWithPublicRoute(): Record<string, unknown> {
    return {
        runtime: "wasm",
        entrypoint: "handle",
        pool: "default",
        warm: true,
        limits: { maxDurationMs: 10000, maxConcurrency: 8, wasmMemoryMb: 64 },
        endpoints: [{ path: "/healthz", auth: "none", methods: ["GET"] }],
        public: [{ hostname: FN_JS_DOMAIN_HOSTNAME }],
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

/// Creates `hello.default.hello` (or, with `opts`, another address — the
/// Wasm/JS flow's own `hellojs.default.hello` at a `wasm` runtime) through
/// the real Create Function drawer (`/functions/new`) — the one
/// prerequisite every subsequent UI-driven step in this flow depends on.
/// Lands the browser on the new function's own detail drawer
/// (`FunctionCreateDrawer.vue`'s `replaceToDetail` on success), so the
/// caller has no further navigation to do. `opts.runtime` defaults to `jvm`
/// (the drawer's own default) — only the Wasm/JS flow passes `"wasm"`,
/// exercising `docs/spec/function-wasm-platform-ui.md` §1's enabled option.
export async function createFunctionViaUi(
    page: Page,
    opts?: {
        applicationCode?: string;
        serviceName?: string;
        name?: string;
        runtime?: "jvm" | "wasm";
    },
): Promise<void> {
    const applicationCode = opts?.applicationCode ?? FN_APPLICATION_CODE;
    const serviceName = opts?.serviceName ?? FN_SERVICE_NAME;
    const name = opts?.name ?? FN_NAME;
    const address = `${applicationCode}.${serviceName}.${name}`;

    await page.goto("/functions/new");
    await bareInput(page, "Application Code").fill(applicationCode);
    await bareInput(page, "Service").fill(serviceName);
    await bareInput(page, "Name").fill(name);
    if (opts?.runtime === "wasm") {
        await choosePrimeOption(page, bareField(page, "Runtime"), "WASM");
    }

    const created = await submitDrawer<{ address: string }>(
        page,
        "Create Function",
        "/api/functions",
    );
    expect(created.address).toBe(address);
    await expect(page).toHaveURL(new RegExp(`/functions/${address}$`));
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

// ─────────────────────────────────────────────────────────────────────────
// Package J2/J4: the Versions tab now renders TWO `<table>`s (versions, then
// Aliases — `FunctionVersionsTab.vue`), and once any promote has happened
// both can contain a row with the SAME text (e.g. "v1" is both a version
// row's own Version column AND an alias row's Version column). A bare
// `.versions-tab tr` locator, as this file's original code used for
// pre-J2/J4 assertions made before any alias existed, is a strict-mode
// violation once that second table has rows — confirmed against
// `frontend/tests/function-versions-tab.test.ts`'s own
// `wrapper.findAll("table")[0]` / `[...].length - 1]` scoping idiom, which
// these mirror. Every locator below is scoped to ONE of the two tables.
// ─────────────────────────────────────────────────────────────────────────

/// The Versions tab's OWN table (`.versions-tab`'s first `<table>`).
export function versionsTable(page: Page): Locator {
    return page.locator(".versions-tab table").first();
}

/// The Aliases table (`.versions-tab`'s second `<table>`, package J2) —
/// only present once at least one alias (at minimum `live`) exists.
export function aliasesTable(page: Page): Locator {
    return page.locator(".versions-tab table").last();
}

/// Promotes `version` to `alias` through the real Promote dialog
/// (`FunctionVersionsTab.vue`'s "Point Alias" `<Dialog>`, package J2) — a
/// plain PrimeVue `<Dialog>` (`role="dialog"`), NOT a `confirm.require`
/// popup, because it needs a text field (the alias name, which defaults to
/// `live`); this always types the wanted name explicitly so one helper
/// covers both the `live` promote and a named alias like `qa`. `address`
/// defaults to [FN_ADDRESS] (the JVM flow's own); the Wasm/JS flow passes
/// [FN_JS_ADDRESS] so the response wait matches ITS function, not the JVM
/// one's.
export async function promoteViaDialog(
    page: Page,
    version: number,
    alias: string,
    address: string = FN_ADDRESS,
): Promise<void> {
    const row = versionsTable(page).locator("tbody tr", { hasText: `v${version}` });
    await row.getByRole("button", { name: "Promote", exact: true }).click();

    const dialog = dialogWithHeader(page, "Point Alias");
    await expect(dialog).toBeVisible();
    const aliasInput = dialog.locator("#promoteAlias");
    await aliasInput.fill(alias);

    const promoteResponse = page.waitForResponse(
        (r) =>
            new URL(r.url()).pathname === `/api/functions/${address}/aliases/${alias}` &&
            r.request().method() === "PUT",
    );
    await dialog.getByRole("button", { name: "Promote", exact: true }).click();
    const promoteRes = await promoteResponse;
    const promoteResBody = await promoteRes.text().catch(() => "<body discarded by the browser after navigation>");
    expect(promoteRes.ok(), promoteResBody).toBe(true);
    await expect(dialog).toBeHidden();
}

/// Deletes a named alias through the Aliases table's own Delete button and
/// the `confirm.require(...)` popup it opens ("Remove Alias" header,
/// `FunctionVersionsTab.vue`'s `confirmDeleteAlias`) — scoped to the ONE
/// row for `alias` so a still-present, merely-disabled `live` row's own
/// Delete button (same label) can never collide with this click.
export async function deleteAliasViaUi(page: Page, alias: string): Promise<void> {
    const row = aliasesTable(page).locator("tbody tr", { hasText: alias });
    await row.getByRole("button", { name: "Delete", exact: true }).click();

    const dialog = confirmWithHeader(page, "Remove Alias");
    await expect(dialog).toBeVisible();

    const deleteResponse = page.waitForResponse(
        (r) =>
            new URL(r.url()).pathname === `/api/functions/${FN_ADDRESS}/aliases/${alias}` &&
            r.request().method() === "DELETE",
    );
    await dialog.getByRole("button", { name: "Remove", exact: true }).click();
    const deleteRes = await deleteResponse;
    expect(deleteRes.ok(), await deleteRes.text().catch(() => "<body discarded by the browser after navigation>")).toBe(true);
    await expect(dialog).toBeHidden();
}

/// Polls a request to the function host's PUBLIC listener until it returns
/// `expectedStatus`. Unlike [waitForVersionState]/[waitForHostState] (which
/// reload the SPA and re-read the DOM), this polls the actual HTTP
/// endpoint, because the alias-prefixed hostname (package J3) only becomes
/// reachable once the fcdev host's own reconcile loop (every 15 s,
/// docs/function-service-overview.md §7) has picked up the desired
/// document's updated `aliases` list for the pointed-at version — there is
/// no SPA-visible state to poll instead. Same bounded-retry idiom (`toPass`,
/// no fixed sleep) as the DOM-polling helpers above.
export async function waitForPublicRouteStatus(
    page: Page,
    url: string,
    expectedStatus: number,
    timeoutMs: number,
): Promise<void> {
    await expect(async () => {
        const res = await page.request.get(url);
        expect(res.status(), await res.text().catch(() => "")).toBe(expectedStatus);
    }).toPass({ timeout: timeoutMs, intervals: [1000, 2000, 3000, 5000] });
}
