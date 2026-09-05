import { readFile } from "node:fs/promises";
import path from "node:path";

import { startSide, JAVA_REPO_ROOT } from "../runner/side.js";
import type { RunningSide, Side } from "../runner/types.js";
import { runSpaGate } from "../runner/spaGate.js";
import { runPlaywrightSuite } from "./runPlaywright.js";
import { readPlaywrightReport, type TestOutcome } from "./playwrightReport.js";

const E2E_DIR = path.resolve(import.meta.dirname, "..");

function pass(status: string): boolean {
    return status === "passed";
}

interface Row {
    title: string;
    go: string;
    java: string;
    mismatch: boolean;
    goTrace: string | null;
    javaTrace: string | null;
}

function buildRows(go: TestOutcome[], java: TestOutcome[]): Row[] {
    const byTitle = new Map<string, { go?: TestOutcome; java?: TestOutcome }>();
    for (const o of go) byTitle.set(o.title, { ...byTitle.get(o.title), go: o });
    for (const o of java) byTitle.set(o.title, { ...byTitle.get(o.title), java: o });
    const rows: Row[] = [];
    for (const [title, { go: g, java: j }] of byTitle) {
        const goStatus = g?.status ?? "missing";
        const javaStatus = j?.status ?? "missing";
        rows.push({
            title,
            go: goStatus,
            java: javaStatus,
            mismatch: pass(goStatus) !== pass(javaStatus),
            goTrace: g?.tracePath ?? null,
            javaTrace: j?.tracePath ?? null,
        });
    }
    rows.sort((a, b) => a.title.localeCompare(b.title));
    return rows;
}

function renderTable(rows: Row[]): string {
    const titleWidth = Math.max(4, ...rows.map((r) => r.title.length));
    const pad = (s: string, w: number) => s + " ".repeat(Math.max(0, w - s.length));
    const lines = [
        `${pad("test", titleWidth)}  go        java      `,
        `${"-".repeat(titleWidth)}  --------  --------`,
    ];
    for (const r of rows) {
        const flag = r.mismatch ? "  <-- MISMATCH" : "";
        lines.push(`${pad(r.title, titleWidth)}  ${pad(r.go, 8)}  ${pad(r.java, 8)}${flag}`);
    }
    return lines.join("\n");
}

/// Starts one side, converting a thrown startup failure into a `null`
/// handle rather than letting it take the other side's `Promise.all` down
/// with it — a side that can't even boot (e.g. the target's own seed data
/// violating its own schema) is a finding to report, not a crash.
async function startSideOrNull(side: Side): Promise<{ side: Side; running: RunningSide | null; error: unknown }> {
    try {
        const running = await startSide(side);
        return { side, running, error: null };
    } catch (error) {
        return { side, running: null, error };
    }
}

async function main(): Promise<number> {
    console.log(">> starting go and java fcdev…");
    const t0 = Date.now();
    const [goResult, javaResult] = await Promise.all([startSideOrNull("go"), startSideOrNull("java")]);
    console.log(`>> side startup finished in ${Date.now() - t0}ms`);
    if (goResult.running) console.log(`   go:   ready at ${goResult.running.baseUrl}`);
    else console.log(`   go:   FAILED TO START — ${String(goResult.error)}`);
    if (javaResult.running) console.log(`   java: ready at ${javaResult.running.baseUrl}`);
    else console.log(`   java: FAILED TO START — ${String(javaResult.error)}`);

    try {
        if (!goResult.running || !javaResult.running) {
            console.log("\n>> cannot run the suite on both sides — at least one fcdev never came up. See the logs above and test-results/{go,java}.log.");
            // A side that never booted still "fails" every flow trivially;
            // that's not useful to spell out row by row, so this reports
            // the boot failure itself and stops there.
            return 1;
        }
        const goSide = goResult.running;
        const javaSide = javaResult.running;

        const sourceCommitPath = path.join(JAVA_REPO_ROOT, "server", "src", "main", "resources", "frontend.source-commit");
        const javaSourceCommit = await readFile(sourceCommitPath, "utf8").then((s) => s.trim()).catch(() => "");
        const allowMismatch = process.env.E2E_ALLOW_SPA_MISMATCH === "1";
        const gate = await runSpaGate(goSide.baseUrl, javaSide.baseUrl, javaSourceCommit, allowMismatch);
        console.log(`>> SPA gate: ${gate.message}`);
        if (!gate.proceed) {
            return 1;
        }

        console.log("\n>> running the suite against go…");
        const goExit = await runPlaywrightSuite("go", goSide.baseUrl);
        const goReport = await readPlaywrightReport(path.join(E2E_DIR, "test-results", "go-report.json"));

        console.log("\n>> running the suite against java…");
        const javaExit = await runPlaywrightSuite("java", javaSide.baseUrl);
        const javaReport = await readPlaywrightReport(path.join(E2E_DIR, "test-results", "java-report.json"));

        const rows = buildRows(goReport, javaReport);
        const table = renderTable(rows);
        console.log("\n" + table + "\n");

        const anyJavaFailure = rows.some((r) => !pass(r.java));
        const anyMismatch = rows.some((r) => r.mismatch);
        if (anyMismatch) {
            console.log(">> tests that differ between sides:");
            for (const r of rows.filter((r) => r.mismatch)) {
                console.log(`   - ${r.title}: go=${r.go} java=${r.java}` +
                    (r.goTrace ? ` goTrace=${r.goTrace}` : "") +
                    (r.javaTrace ? ` javaTrace=${r.javaTrace}` : ""));
            }
        }
        if (anyJavaFailure) {
            console.log(">> java failures:");
            for (const r of rows.filter((r) => !pass(r.java))) {
                console.log(`   - ${r.title}: java=${r.java}` + (r.javaTrace ? ` trace=${r.javaTrace}` : ""));
            }
        }

        // Spec §5: non-zero on any Java failure or any flow that differs
        // between sides. `goExit`/`javaExit` themselves are the Playwright
        // process's own exit code (reported above); the table already
        // captures every per-test outcome those exit codes summarise.
        void goExit;
        void javaExit;
        return anyJavaFailure || anyMismatch ? 1 : 0;
    } finally {
        await Promise.all([goResult.running?.stop(), javaResult.running?.stop()]);
    }
}

main()
    .then((code) => process.exit(code))
    .catch((e) => {
        console.error(e);
        process.exit(1);
    });
