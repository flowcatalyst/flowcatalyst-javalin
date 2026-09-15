import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

/// The SPA gate (spec §1): before any test runs, fetch `/index.html` from
/// both sides and compare the bytes. Vite's asset hashes are content
/// hashes, so two builds of one source produce the same document and any
/// other build a different one — the earlier gate blanked the hashes and
/// on 2026-09-06 passed a two-week-old Go build as "matching". A mismatched build produces dozens of
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

/// The pure decision (unit-testable without a network): given the two
/// sides' `/index.html` bodies, the Java copy's stamped source commit, and
/// whether a mismatch is allowed, decide whether the run may proceed.
export function decideSpaGate(
    goIndexHtml: Buffer | string,
    javaIndexHtml: Buffer | string,
    javaSourceCommit: string,
    allowMismatch: boolean,
): SpaGateResult {
    const goHash = sha256(goIndexHtml);
    const javaHash = sha256(javaIndexHtml);
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

// ── Rust route allow-list gate ───────────────────────────────────────────
//
// The Rust SPA is a fork of Java's (docs/java-parity-plan.md §4.1), so its
// /index.html bytes can never match Java's — `decideSpaGate` above would
// refuse every Rust run. This is E2E_SIDE=rust's replacement: instead of a
// byte comparison, `e2e/rust-routes-allowlist.json` names the client
// routes both SPAs actually serve ("shared") and the ones only Java has
// yet ("javaOnly", documented rather than silently missing).

export interface RouteAllowlist {
    /// Client routes present in both SPAs — checked reachable before the
    /// suite runs.
    shared: string[];
    /// Client routes only Java's SPA has (portal identities, docs, …) —
    /// never checked; listed so a skipped Java-only spec against the Rust
    /// side has a documented reason instead of looking like a silent gap.
    javaOnly: string[];
}

export interface RouteAllowlistGateResult {
    /// Whether every `shared` route answered (HTTP status < 500 — a client
    /// route renders through the SPA fallback, so any non-server-error
    /// status means the Rust binary served *something* for it; the actual
    /// screen assertions are each spec's job, not the gate's).
    ok: boolean;
    checked: string[];
    unreachable: string[];
    skipped: string[];
    message: string;
}

/// The pure decision (unit-testable without a network): given each
/// `shared` route's reachability (`true` = answered, `false` = did not),
/// decide whether the run may proceed.
export function decideRouteAllowlistGate(
    reachable: Record<string, boolean>,
    allowlist: RouteAllowlist,
): RouteAllowlistGateResult {
    const checked = [...allowlist.shared];
    const unreachable = checked.filter((route) => reachable[route] !== true);
    const ok = unreachable.length === 0;
    const message = ok
        ? `route allow-list gate: ${checked.length} shared route(s) reachable; ${allowlist.javaOnly.length} Java-only route(s) skipped (see e2e/rust-routes-allowlist.json).`
        : `route allow-list gate: ${unreachable.length}/${checked.length} shared route(s) unreachable: ${unreachable.join(", ")}`;
    return { ok, checked, unreachable, skipped: allowlist.javaOnly, message };
}

/// Loads and parses `e2e/rust-routes-allowlist.json` (or any path — tests
/// pass a fixture).
export async function loadRouteAllowlist(path: string): Promise<RouteAllowlist> {
    const text = await readFile(path, "utf8");
    const parsed = JSON.parse(text) as { shared?: unknown; javaOnly?: unknown };
    const shared = Array.isArray(parsed.shared) ? parsed.shared.filter((s): s is string => typeof s === "string") : [];
    const javaOnly = Array.isArray(parsed.javaOnly) ? parsed.javaOnly.filter((s): s is string => typeof s === "string") : [];
    return { shared, javaOnly };
}

/// Fetches every `allowlist.shared` route against `baseUrl` (a client-side
/// route, so any non-5xx response means the SPA fallback served
/// `index.html` for it — vue-router then renders client-side) and decides.
export async function runRouteAllowlistGate(baseUrl: string, allowlist: RouteAllowlist): Promise<RouteAllowlistGateResult> {
    const reachable: Record<string, boolean> = {};
    await Promise.all(
        allowlist.shared.map(async (route) => {
            try {
                const res = await fetch(new URL(route, baseUrl));
                reachable[route] = res.status < 500;
            } catch {
                reachable[route] = false;
            }
        }),
    );
    return decideRouteAllowlistGate(reachable, allowlist);
}
