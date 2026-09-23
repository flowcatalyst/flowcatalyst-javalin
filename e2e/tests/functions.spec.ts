// Screens: frontend/src/pages/functions/**, function-domains/**. Go screen:
// none — the function service has no Go counterpart
// (docs/function-service-overview.md §9 "Divergence from Go": "the function
// service has no Go counterpart; it is Java-first and post-cutover"). This
// flow is therefore Java-only: `test.skip(process.env.E2E_SIDE === "go", …)`
// is the first statement in the test body below. No existing mechanism in
// this runner marks a flow Java-only (grepped `e2e/` for `javaOnly`,
// `E2E_SIDE ===`, `test.skip` — nothing) — this is Playwright's own
// standard conditional-skip idiom (`test.skip(condition, reason)`), applied
// here for the first time in this suite. `scripts/run-both.ts`'s
// `buildRows` has been taught that a Go "skipped" status is never a
// cross-side "MISMATCH" (a skip means "not applicable to this side", not
// "differs") — see the comment there; a real Java failure on this flow
// still fails `pnpm e2e:both` via `anyJavaFailure`, independent of that.
//
// docs/spec/function-ui.md §6 row E1 / §7 row H4. As the bootstrap admin:
// application + service account + event types (prerequisites,
// examples/function-subscription-test/README.md step 4), claim
// hello.localhost, publish examples/function-hello's shrunk jar with a
// manifest that adds a public route, set its config/secret, wait for
// READY, promote, and assert the Hosts panel + Public Routes tab + a real
// HTTP call to the function's public listener.
//
// docs/spec/frontend-e2e.md §3/§4: assertions are the observable outcome
// after a reload, never a transient banner — every polled step below
// reloads the page and re-reads the DOM, not an in-memory flag.
//
// Every step below is UI-driven, including the create drawer and the
// Config & Secrets tab's "Add key" rows — see the comment in
// fixtures/functions.ts above createFunctionViaUi/setConfigAndSecretViaUi
// for what those closed (docs/functions.md §12).
import path from "node:path";
import { existsSync } from "node:fs";
import {
    test,
    expect,
    bareInput,
    rowWithText,
    submitDrawer,
    createApplication,
    provisionServiceAccount,
    createEventType,
} from "../fixtures/catalogue.js";
import {
    FN_ADDRESS,
    FN_ALIAS_HOSTNAME,
    FN_ALIAS_PREFIX,
    FN_APPLICATION_CODE,
    FN_DOMAIN_HOSTNAME,
    FN_NAME,
    FN_PUBLIC_PORT,
    FN_UNOPTED_PREFIX_HOSTNAME,
    aliasesTable,
    createFunctionViaUi,
    deleteAliasViaUi,
    helloManifestWithPublicRoute,
    promoteViaDialog,
    setConfigAndSecretViaUi,
    versionsTable,
    waitForHostState,
    waitForPublicRouteStatus,
    waitForVersionState,
} from "../fixtures/functions.js";

const JAR_PATH = path.resolve(
    import.meta.dirname,
    "..",
    "..",
    "examples",
    "function-hello",
    "target",
    "function-hello-0.0.1-SNAPSHOT-shrunk.jar",
);

const GREETING_VALUE = "Hello from the E2E flow";
const API_KEY_VALUE = "e2e-api-key-value";

test.describe("functions", () => {
    test("claim a domain, publish, configure, promote, and reach the function", async ({ adminPage: page }) => {
        test.skip(
            process.env.E2E_SIDE === "go",
            "the function service has no Go counterpart (docs/function-service-overview.md §9)",
        );

        // This flow does substantially more than a CRUD round trip — an
        // artifact upload, a publish, at least one of the in-process fcdev
        // host's own reconcile cycles (docs/function-service-overview.md §7:
        // "Reconcile every 15 s (or when triggered)") to reach READY, a
        // promote, and a second reconcile to reach LOADED — so it needs more
        // than the suite's default 60s per-flow budget. Never shrinks an
        // explicitly configured larger `E2E_TEST_TIMEOUT_MS`.
        test.setTimeout(Math.max(180_000, test.info().timeout));

        if (!existsSync(JAR_PATH)) {
            throw new Error(
                `functions.spec.ts: no shrunk jar at ${JAR_PATH} — run \`make examples\` first ` +
                    "(examples/function-hello is an opt-in reactor module, profile `examples`)",
            );
        }

        // ── 1. Application + its service account (prerequisite: subscription
        // deliveries are signed with the application's signing secret;
        // examples/function-subscription-test/README.md step 4 — promote
        // refuses with APPLICATION_SIGNING_SECRET_REQUIRED without one). ────
        await createApplication(page, { code: FN_APPLICATION_CODE, name: "Hello" });
        await provisionServiceAccount(page);

        // ── 2. Event types the sample's manifest subscribes to / emits. ────
        const requested = await createEventType(page, FN_APPLICATION_CODE, {
            subdomain: "greeting",
            aggregate: "greeting",
            event: "requested",
        });
        const sent = await createEventType(page, FN_APPLICATION_CODE, {
            subdomain: "greeting",
            aggregate: "greeting",
            event: "sent",
        });
        expect(requested.code).toBe("hello:greeting:greeting:requested");
        expect(sent.code).toBe("hello:greeting:greeting:sent");

        // ── 3. Functions → Domains: claim hello.localhost. A claim is
        // verified by being made (docs/spec/function-domains-no-dns.md) — no
        // DNS step, immediately usable. ─────────────────────────────────────
        await page.goto("/function-domains/new");
        await bareInput(page, "Hostname").fill(FN_DOMAIN_HOSTNAME);
        const claimed = await submitDrawer<{ hostname: string }>(
            page,
            "Claim",
            "/api/function-domains",
        );
        expect(claimed.hostname).toBe(FN_DOMAIN_HOSTNAME);
        await expect(page).toHaveURL(new RegExp(`/function-domains/${FN_DOMAIN_HOSTNAME}`));

        await page.reload();
        await expect(page.getByText(FN_DOMAIN_HOSTNAME, { exact: true }).first()).toBeVisible();

        // ── 4. Functions: create hello.default.hello through the real Create
        // Function drawer (Gap 1, closed — fixtures/functions.ts's
        // createFunctionViaUi), then publish its first version through the
        // real Publish drawer UI. ───────────────────────────────────────────
        await createFunctionViaUi(page);

        // FunctionDetailDrawer.vue's title is `fn.name` (the address's third
        // DNS label, "hello" — FunctionResponse.name is NOT a separate
        // display name, it is literally `f.address().name().value()`,
        // confirmed against FunctionApi.java's FunctionResponse.from), and
        // the full address is the subtitle underneath it, not the heading.
        await expect(page.getByRole("heading", { name: FN_NAME, exact: true })).toBeVisible();
        // .first() — the drawer's own subtitle AND the Overview tab's
        // "Address" detail field both render the same address text
        // (confirmed empirically: a bare exact match is a strict-mode
        // violation, matching this suite's established idiom for the same
        // situation elsewhere, e.g. catalogue.spec.ts's repeated comments).
        await expect(page.getByText(FN_ADDRESS, { exact: true }).first()).toBeVisible();

        await page.getByRole("tab", { name: "Versions", exact: true }).click();
        await page.getByRole("button", { name: "Publish Version", exact: true }).click();

        await page.getByTestId("publish-jar-input").setInputFiles(JAR_PATH);
        const manifestJson = JSON.stringify(helloManifestWithPublicRoute(), null, 2);
        await page.getByTestId("publish-manifest-input").setInputFiles({
            name: "manifest.json",
            mimeType: "application/json",
            buffer: Buffer.from(manifestJson, "utf8"),
        });

        const publishResponse = page.waitForResponse(
            (r) =>
                new URL(r.url()).pathname === `/api/functions/${FN_ADDRESS}/versions` &&
                r.request().method() === "POST",
        );
        await page.getByTestId("publish-submit").click();
        const publishRes = await publishResponse;
        const publishResBody = await publishRes.text().catch(() => "<body discarded by the browser after navigation>");
expect(publishRes.ok(), publishResBody).toBe(true);
        const published = JSON.parse(publishResBody) as { version: number };
        expect(published.version).toBeGreaterThan(0);

        await expect(page.locator(".versions-tab tr", { hasText: `v${published.version}` })).toBeVisible();

        // ── 5. Config & secrets: GREETING / API_KEY through the real Config &
        // Secrets tab's "Add key" rows (Gap 2, closed — fixtures/functions.ts's
        // setConfigAndSecretViaUi), set before this version has ever been
        // promoted. ─────────────────────────────────────────────────────────
        await setConfigAndSecretViaUi(page, FN_ADDRESS, GREETING_VALUE, API_KEY_VALUE);

        // ── 6. Wait for READY (the in-process fcdev host's own reconcile
        // loop), then Promote through the real UI — the Versions tab's
        // "Point Alias" dialog (package J2), defaulting to `live`. ─────────
        const pollBudgetMs = Math.max(15_000, Math.floor(test.info().timeout * 0.35));
        await waitForVersionState(page, published.version, "READY", pollBudgetMs);

        await promoteViaDialog(page, published.version, "live");
        // Scoped to the versions table specifically (not a bare
        // `.versions-tab tr`): once this promote lands, the Aliases table
        // below ALSO renders a row containing both `v{n}` and a "LIVE" tag
        // for the `live` alias — an unscoped locator would match both and
        // violate Playwright's strict mode.
        await expect(
            versionsTable(page).locator("tbody tr", { hasText: `v${published.version}` }).getByText("LIVE"),
        ).toBeVisible();

        // ── Hosts panel (Overview tab) shows LOADED after reload. ───────────
        await waitForHostState(page, published.version, "LOADED", pollBudgetMs);

        // ── Config & Secrets tab now (post-promote) reflects what Gap 2's
        // workaround set — the live manifest declares GREETING/API_KEY now,
        // so the tab finally has rows to show them (U7's own rule: the
        // secret's value is never in the DOM, only "set"). ─────────────────
        await page.getByRole("tab", { name: "Config & Secrets", exact: true }).click();
        await expect(page.getByText(GREETING_VALUE, { exact: true })).toBeVisible();
        const secretRow = rowWithText(page, "API_KEY");
        await expect(secretRow.getByText("set", { exact: true })).toBeVisible();
        await expect(page.getByText(API_KEY_VALUE)).toHaveCount(0);

        // ── Public routes tab: hello.localhost. ──────────────────────────────
        await page.getByRole("tab", { name: "Public Routes", exact: true }).click();
        const routeRow = rowWithText(page, FN_DOMAIN_HOSTNAME);
        await expect(routeRow).toBeVisible();

        // ── 7. The function is actually reachable on its public route. ──────
        const healthRes = await page.request.get(`http://${FN_DOMAIN_HOSTNAME}:${FN_PUBLIC_PORT}/healthz`);
        expect(healthRes.status(), await healthRes.text().catch(() => "")).toBe(200);

        // ── 8. Package J2/J3/J4: point the NAMED alias "qa" at the SAME
        // version through the Versions tab's Promote dialog — HTTP-only, no
        // wiring change (spec `function-zones-and-aliases.md` §2). The
        // dialog's row-level trigger stays enabled even though this version
        // is already live: `canPromoteRow` only gates on READY state, not
        // `v.live` — a different alias legitimately naming the same version
        // is not the platform's ALIAS_UNCHANGED conflict (only re-promoting
        // `qa` to a version it ALREADY names would be, and the dialog itself
        // guards that case, `promoteWouldBeNoOp`). The Aliases table gains a
        // "qa" row alongside "live"; `live`'s own Delete stays disabled (it
        // can never be removed), `qa`'s is enabled. ─────────────────────────
        await page.getByRole("tab", { name: "Versions", exact: true }).click();
        await promoteViaDialog(page, published.version, FN_ALIAS_PREFIX);

        const qaAliasRow = aliasesTable(page).locator("tbody tr", { hasText: FN_ALIAS_PREFIX });
        await expect(qaAliasRow).toBeVisible();
        await expect(qaAliasRow.getByText(`v${published.version}`, { exact: true })).toBeVisible();

        const liveAliasRow = aliasesTable(page).locator("tbody tr", { hasText: "live" });
        await expect(liveAliasRow.getByRole("button", { name: "Delete", exact: true })).toBeDisabled();
        await expect(qaAliasRow.getByRole("button", { name: "Delete", exact: true })).toBeEnabled();

        // ── 9. Public Routes tab: the opted-in alias prefix's derived
        // hostname renders next to hello.localhost's own route row (package
        // J3's "Alias Prefixes" column, `FunctionPublicRoutesTab.vue`). ─────
        await page.getByRole("tab", { name: "Public Routes", exact: true }).click();
        const publicRouteRow = rowWithText(page, FN_DOMAIN_HOSTNAME);
        await expect(publicRouteRow.getByText(FN_ALIAS_HOSTNAME, { exact: true })).toBeVisible();

        // ── 10. The alias-prefixed hostname is actually reachable once the
        // fcdev host's own reconcile loop (every 15 s) has picked up the
        // pointed alias; the exact hostname's OWN prefix that was never
        // opted in ("staging") stays 404 throughout — proving the match is
        // driven by the route's own `aliasPrefixes`, not by any hostname
        // ending in "-hello.localhost". ─────────────────────────────────────
        await waitForPublicRouteStatus(
            page,
            `http://${FN_ALIAS_HOSTNAME}:${FN_PUBLIC_PORT}/healthz`,
            200,
            pollBudgetMs,
        );
        const stagingRes = await page.request.get(`http://${FN_UNOPTED_PREFIX_HOSTNAME}:${FN_PUBLIC_PORT}/healthz`);
        expect(stagingRes.status(), await stagingRes.text().catch(() => "")).toBe(404);

        // ── 11. Delete the "qa" alias through the Aliases table; "live"'s
        // Delete button stays disabled the whole time (it protects the one
        // alias that can never be removed), and the "qa" row disappears. ───
        await page.getByRole("tab", { name: "Versions", exact: true }).click();
        await expect(
            aliasesTable(page).locator("tbody tr", { hasText: "live" }).getByRole("button", { name: "Delete", exact: true }),
        ).toBeDisabled();
        await deleteAliasViaUi(page, FN_ALIAS_PREFIX);
        await expect(aliasesTable(page).locator("tbody tr", { hasText: FN_ALIAS_PREFIX })).toHaveCount(0);
    });
});
