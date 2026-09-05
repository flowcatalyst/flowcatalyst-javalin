import { spawn } from "node:child_process";
import path from "node:path";
import type { Side } from "../runner/types.js";

const E2E_DIR = path.resolve(import.meta.dirname, "..");

/// Runs the Playwright suite against an already-started side. Inherits
/// stdio so the `list` reporter's live pass/fail prints as it goes; the
/// `json` reporter (configured in `playwright.config.ts`, one file per
/// side) is what `run-both.ts` reads back afterwards. Resolves to the exit
/// code rather than rejecting on a test failure — a failing suite is a
/// normal outcome here, not a script error.
export function runPlaywrightSuite(side: Side, baseUrl: string): Promise<number> {
    return new Promise((resolve, reject) => {
        const child = spawn(
            "pnpm",
            ["exec", "playwright", "test", "--project=chromium"],
            {
                cwd: E2E_DIR,
                stdio: "inherit",
                env: { ...process.env, E2E_SIDE: side, E2E_BASE_URL: baseUrl },
            },
        );
        child.on("error", reject);
        child.on("exit", (code) => resolve(code ?? 1));
    });
}
