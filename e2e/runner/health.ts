/// Polls `GET <baseUrl>/health` until it answers 200, or throws once
/// `budgetMs` has elapsed (spec §2: "Readiness = /health 200", 60s budget).
export async function waitForHealth(baseUrl: string, budgetMs = 60_000, intervalMs = 500): Promise<void> {
    const deadline = Date.now() + budgetMs;
    let lastError: unknown;
    while (Date.now() < deadline) {
        try {
            const res = await fetch(new URL("/health", baseUrl), { signal: AbortSignal.timeout(intervalMs * 4) });
            if (res.ok) return;
            lastError = new Error(`GET /health answered ${res.status}`);
        } catch (e) {
            lastError = e;
        }
        await sleep(intervalMs);
    }
    throw new Error(`waitForHealth: ${baseUrl}/health did not answer 200 within ${budgetMs}ms: ${String(lastError)}`);
}

function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}
