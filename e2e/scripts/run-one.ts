import type { Side } from "../runner/types.js";
import { startSide } from "../runner/side.js";
import { runPlaywrightSuite } from "./runPlaywright.js";

/// `pnpm e2e` / `pnpm e2e:go` / `pnpm e2e:java`: run the whole suite
/// against exactly one side (spec §1 — "`E2E_SIDE=go|java`").
///
/// // SPEC?: the design's SPA gate (§1) needs both sides' `/index.html`;
/// a single-side run only starts one `fcdev`, so there is no second side
/// to compare against here without booting one just for that check. The
/// gate runs for real in `pnpm e2e:both` (`run-both.ts`), which starts both
/// sides anyway; a solo `pnpm e2e:go`/`e2e:java` skips it and says so.
async function main(): Promise<number> {
    const side = process.env.E2E_SIDE as Side | undefined;
    if (side !== "go" && side !== "java") {
        console.error('run-one: set E2E_SIDE=go or E2E_SIDE=java (e.g. "pnpm e2e:go")');
        return 2;
    }

    console.log(`>> starting ${side} fcdev…`);
    const t0 = Date.now();
    const running = await startSide(side);
    console.log(`>> ${side} fcdev ready at ${running.baseUrl} in ${Date.now() - t0}ms (log: ${running.logPath})`);
    console.log(`>> skipping the SPA gate (single-side run) — run \`pnpm e2e:both\` for the real check`);

    try {
        return await runPlaywrightSuite(side, running.baseUrl);
    } finally {
        await running.stop();
    }
}

main()
    .then((code) => process.exit(code))
    .catch((e) => {
        console.error(e);
        process.exit(1);
    });
