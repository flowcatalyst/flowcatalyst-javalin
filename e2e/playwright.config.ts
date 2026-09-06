import { defineConfig, devices } from "@playwright/test";

/// Both sides are started and torn down by `scripts/run-one.ts` — a plain
/// `pnpm exec playwright test` without that wrapper has no server to talk
/// to, which is why `E2E_BASE_URL` is required rather than defaulted.
const baseURL = process.env.E2E_BASE_URL;
if (!baseURL) {
    // Playwright loads this file just to print `--help`/list tests too, so
    // this only throws once a real run tries to use `baseURL` — but fail
    // fast here anyway with the actionable message (spec §1).
    // eslint-disable-next-line no-console
    console.warn("E2E_BASE_URL is not set — run through `pnpm e2e` / `pnpm e2e:both`, not `playwright test` directly.");
}

export default defineConfig({
    testDir: "./tests",
    // // SPEC?: Playwright wipes `outputDir` at the start of every run.
    // The spec puts each side's captured log at `test-results/<side>.log`
    // (§2/§4) — directly in the *default* `outputDir` — so a plain
    // `playwright test` run deletes the very file the mail-from-log helper
    // needs. Traces/screenshots move one level down instead, so the side
    // logs and the JSON reports (both written straight under
    // `test-results/`) survive.
    outputDir: "test-results/artifacts",
    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    // Per-test budget and retries are tunable per run: a discovery run against
    // a side that has never passed wants `E2E_RETRIES=0` and a short budget so a
    // stalled flow costs a minute, not the default plus a retry.
    timeout: Number(process.env.E2E_TEST_TIMEOUT_MS ?? 60_000),
    retries: process.env.E2E_RETRIES != null ? Number(process.env.E2E_RETRIES) : 1,
    workers: 1,
    reporter: [
        ["list"],
        ["json", { outputFile: `test-results/${process.env.E2E_SIDE ?? "run"}-report.json` }],
    ],
    use: {
        baseURL,
        trace: "on-first-retry",
        video: "off",
        screenshot: "only-on-failure",
    },
    projects: [
        {
            name: "chromium",
            use: { ...devices["Desktop Chrome"] },
        },
    ],
});
