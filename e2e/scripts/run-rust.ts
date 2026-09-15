import path from "node:path";

import { startSide } from "../runner/side.js";
import { loadRouteAllowlist, runRouteAllowlistGate } from "../runner/spaGate.js";
import { runPlaywrightSuite } from "./runPlaywright.js";

const E2E_DIR = path.resolve(import.meta.dirname, "..");

/// `pnpm e2e:rust`: run the whole suite against the Rust `fc-dev` binary
/// (L1 lane, `docs/java-parity-plan.md` §3). Mirrors `run-one.ts`
/// (`E2E_SIDE=go|java`) but hardcoded to `"rust"`, and — since the Rust
/// SPA is a fork and can never pass the byte-identical SPA gate `go`/`java`
/// use against each other (`spaGate.ts`'s `decideSpaGate`) — runs the
/// route allow-list gate instead: `e2e/rust-routes-allowlist.json` names
/// the client routes the Rust SPA is expected to serve, and this checks
/// each one is actually reachable before handing off to Playwright. A
/// Java-only spec that visits a `javaOnly` route still runs (this script
/// does not filter `e2e/tests/*.spec.ts` — that's out of L1's scope, see
/// `docs/parity/l1.md`) and is expected to fail there; the allow-list's
/// job is only to make that a documented, expected gap rather than a
/// silent or confusing one.
async function main(): Promise<number> {
    console.log(">> starting rust fcdev…");
    const t0 = Date.now();
    const running = await startSide("rust");
    console.log(`>> rust fcdev ready at ${running.baseUrl} in ${Date.now() - t0}ms (log: ${running.logPath})`);

    try {
        const allowlistPath = path.join(E2E_DIR, "rust-routes-allowlist.json");
        const allowlist = await loadRouteAllowlist(allowlistPath);
        const gate = await runRouteAllowlistGate(running.baseUrl, allowlist);
        console.log(`>> ${gate.message}`);
        if (!gate.ok) {
            console.log(`>> route allow-list gate failed — see ${allowlistPath}`);
            return 1;
        }

        return await runPlaywrightSuite("rust", running.baseUrl);
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
