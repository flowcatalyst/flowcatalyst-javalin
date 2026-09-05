import { createHash } from "node:crypto";

/// The SPA gate (spec §1): before any test runs, fetch `/index.html` from
/// both sides and compare them with Vite's per-build asset hashes blanked. A mismatched build produces dozens of
/// spurious failures downstream (every screen the SPA can't render right),
/// so the run refuses to start on a mismatch unless the caller opts in with
/// `E2E_ALLOW_SPA_MISMATCH=1` — in which case it proceeds but the result is
/// stamped "SPA revisions differ" so the table is read in that light.
export interface SpaGateResult {
    /// Whether the two sides' `/index.html` bytes were identical.
    matched: boolean;
    /// Whether the run may proceed (`matched`, or the caller allowed a mismatch).
    proceed: boolean;
    goHash: string;
    javaHash: string;
    /// A human-readable summary for the run's console output / report.
    message: string;
}

function sha256(bytes: Buffer | string): string {
    return createHash("sha256").update(bytes).digest("hex").slice(0, 16);
}

/// Vite stamps every emitted asset with a content hash that is *not* stable
/// across separate `vite build` invocations of the same source, so two
/// builds of one commit differ only in `index-CigTA1gY.js` vs
/// `index-vE2h-lD0.js`. The gate compares the document with those hashes
/// blanked: same source, same document. (Found on the first run of the
/// runner: identical source commit, different chunk names.)
export function normaliseIndexHtml(html: Buffer | string): string {
    return html.toString().replace(/-[A-Za-z0-9_-]{8}\.(js|css)\b/g, ".$1");
}

/// The pure decision (unit-testable without a network): given the two
/// sides' `/index.html` bodies, the Java copy's stamped source commit, and
/// whether a mismatch is allowed, decide whether the run may proceed.
export function decideSpaGate(
    goIndexHtml: Buffer | string,
    javaIndexHtml: Buffer | string,
    javaSourceCommit: string,
    allowMismatch: boolean,
): SpaGateResult {
    const goHash = sha256(normaliseIndexHtml(goIndexHtml));
    const javaHash = sha256(normaliseIndexHtml(javaIndexHtml));
    const matched = goHash === javaHash;
    if (matched) {
        return { matched, proceed: true, goHash, javaHash, message: `SPA revisions match (${goHash}).` };
    }
    const detail =
        `SPA revisions differ: go index.html=${goHash} java index.html=${javaHash} ` +
        `(java embedded frontend.source-commit=${javaSourceCommit || "unknown"})`;
    if (allowMismatch) {
        return { matched, proceed: true, goHash, javaHash, message: `${detail} — continuing (E2E_ALLOW_SPA_MISMATCH=1).` };
    }
    return {
        matched,
        proceed: false,
        goHash,
        javaHash,
        message: `${detail} — refusing to run (set E2E_ALLOW_SPA_MISMATCH=1 to override).`,
    };
}

/// Fetches `/index.html` from a running side. Thrown errors are the
/// caller's problem (the side isn't up, `/index.html` 404s, …).
export async function fetchIndexHtml(baseUrl: string): Promise<string> {
    const res = await fetch(new URL("/index.html", baseUrl));
    if (!res.ok) {
        throw new Error(`fetchIndexHtml: ${baseUrl}/index.html answered ${res.status}`);
    }
    return await res.text();
}

/// The full gate: fetch both sides' `/index.html` over HTTP and decide.
export async function runSpaGate(
    goBaseUrl: string,
    javaBaseUrl: string,
    javaSourceCommit: string,
    allowMismatch: boolean,
): Promise<SpaGateResult> {
    const [goHtml, javaHtml] = await Promise.all([fetchIndexHtml(goBaseUrl), fetchIndexHtml(javaBaseUrl)]);
    return decideSpaGate(goHtml, javaHtml, javaSourceCommit, allowMismatch);
}
