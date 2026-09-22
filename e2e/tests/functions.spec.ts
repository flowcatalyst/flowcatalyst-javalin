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
// Two steps use a documented API-level workaround instead of a UI action —
// see the big comment in fixtures/functions.ts above
// createFunctionViaApi/setConfigAndSecretViaApi for exactly which UI gaps
// make that unavoidable today, with file/line citations. Every other step
// below is UI-driven.
import path from "node:path";
import { existsSync } from "node:fs";
import {
    test,
    expect,
    bareInput,
    confirmedAction,
    rowWithText,
    submitDrawer,
    createApplication,
    provisionServiceAccount,
    createEventType,
} from "../fixtures/catalogue.js";
import {
    FN_ADDRESS,
    FN_APPLICATION_CODE,
    FN_DOMAIN_HOSTNAME,
    FN_NAME,
    FN_PUBLIC_PORT,
    createFunctionViaApi,
    helloManifestWithPublicRoute,
    setConfigAndSecretViaApi,
    waitForHostState,
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

        // ── 3. Functions → Domains: claim hello.localhost. `.localhost`
        // auto-verifies immediately in dev mode (docs/functions.md §6a). ────
        await page.goto("/function-domains/new");
        await bareInput(page, "Hostname").fill(FN_DOMAIN_HOSTNAME);
        const claimed = await submitDrawer<{ hostname: string; verification: { state: string } }>(
            page,
            "Claim",
            "/api/function-domains",
        );
        expect(claimed.verification.state).toBe("VERIFIED");
        await expect(page).toHaveURL(new RegExp(`/function-domains/${FN_DOMAIN_HOSTNAME}`));

        await page.reload();
        await expect(page.getByText("VERIFIED", { exact: true }).first()).toBeVisible();
        await expect(page.getByText("Auto-verified in dev mode", { exact: false })).toBeVisible();

        // ── 4. Functions: create hello.default.hello (API workaround — Gap 1,
        // fixtures/functions.ts), then publish its first version through the
        // real Publish drawer UI. ───────────────────────────────────────────
        await createFunctionViaApi(page.request);

        await page.goto(`/functions/${FN_ADDRESS}`);
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
        expect(publishRes.ok(), await publishRes.text()).toBe(true);
        const published = (await publishRes.json()) as { version: number };
        expect(published.version).toBeGreaterThan(0);

        await expect(page.locator("tr", { hasText: `v${published.version}` })).toBeVisible();

        // ── 5. Config & secrets: GREETING / API_KEY (API workaround — Gap 2,
        // fixtures/functions.ts: the tab cannot set a key before a live
        // version exists, and promote's own settings check needs it set
        // NOW, before promote — there is no UI ordering that satisfies both).
        // ────────────────────────────────────────────────────────────────
        await setConfigAndSecretViaApi(page.request, FN_ADDRESS, GREETING_VALUE, API_KEY_VALUE);

        // ── 6. Wait for READY (the in-process fcdev host's own reconcile
        // loop), then Promote through the real UI. ─────────────────────────
        const pollBudgetMs = Math.max(15_000, Math.floor(test.info().timeout * 0.35));
        await waitForVersionState(page, published.version, "READY", pollBudgetMs);

        await confirmedAction(page, "Promote", "Promote Version", "Promote");
        await expect(page.locator("tr", { hasText: `v${published.version}` }).getByText("LIVE")).toBeVisible();

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

        // ── Public routes tab: hello.localhost, auto-verified. ──────────────
        await page.getByRole("tab", { name: "Public Routes", exact: true }).click();
        const routeRow = rowWithText(page, FN_DOMAIN_HOSTNAME);
        await expect(routeRow).toBeVisible();
        await expect(routeRow.getByText("auto-verified (dev mode)", { exact: true })).toBeVisible();

        // ── 7. The function is actually reachable on its public route. ──────
        const healthRes = await page.request.get(`http://${FN_DOMAIN_HOSTNAME}:${FN_PUBLIC_PORT}/healthz`);
        expect(healthRes.status(), await healthRes.text().catch(() => "")).toBe(200);
    });
});
