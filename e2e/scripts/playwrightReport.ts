import { readFile } from "node:fs/promises";

/// The slice of Playwright's `json` reporter shape this file actually
/// reads — reporter internals, not a public API, so this is deliberately
/// narrow and defensive (missing fields just drop the row rather than
/// throwing).
interface JsonReportSpec {
    title: string;
    tests?: Array<{
        results?: Array<{
            status?: string;
            attachments?: Array<{ name?: string; path?: string }>;
        }>;
    }>;
}

interface JsonReportSuite {
    title?: string;
    specs?: JsonReportSpec[];
    suites?: JsonReportSuite[];
}

interface JsonReport {
    suites?: JsonReportSuite[];
}

export interface TestOutcome {
    title: string;
    status: "passed" | "failed" | "timedOut" | "skipped" | "interrupted" | "unknown";
    tracePath: string | null;
}

function flattenSuite(suite: JsonReportSuite, parents: string[]): TestOutcome[] {
    const here = suite.title ? [...parents, suite.title] : parents;
    const out: TestOutcome[] = [];
    for (const spec of suite.specs ?? []) {
        const title = [...here, spec.title].filter(Boolean).join(" > ");
        // The *last* attempt (a retry that eventually passed is a pass).
        const results = spec.tests?.[0]?.results ?? [];
        const last = results[results.length - 1];
        const status = (last?.status as TestOutcome["status"]) ?? "unknown";
        const trace = last?.attachments?.find((a) => a.name === "trace")?.path ?? null;
        out.push({ title, status, tracePath: status === "passed" ? null : trace });
    }
    for (const child of suite.suites ?? []) {
        out.push(...flattenSuite(child, here));
    }
    return out;
}

/// Reads a Playwright `json` reporter file and flattens it to one row per
/// test (dropping the file-level suite from the title — every spec here
/// lives in one file per group, so it adds nothing).
export async function readPlaywrightReport(path: string): Promise<TestOutcome[]> {
    const raw = JSON.parse(await readFile(path, "utf8")) as JsonReport;
    const out: TestOutcome[] = [];
    for (const suite of raw.suites ?? []) {
        // suite here is the per-file suite; recurse into its children
        // without adding the file name itself to the title.
        out.push(...flattenSuite({ ...suite, title: undefined }, []));
    }
    return out;
}
