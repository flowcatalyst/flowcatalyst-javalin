/// `"rust"` drives the Rust `fc-dev` binary (`bin/fc-dev` in
/// `flowcatalyst-rust`) — added for the L1 lane of
/// `docs/java-parity-plan.md`. The Rust frontend is a fork (see
/// `spaGate.ts`'s route-allow-list gate), so `"rust"` never takes part in
/// the byte-identical SPA gate `"go"`/`"java"` use against each other.
export type Side = "go" | "java" | "rust";

export interface RunningSide {
    side: Side;
    baseUrl: string;
    apiPort: number;
    metricsPort: number;
    scratchDir: string;
    /// Where this side's merged stdout+stderr landed — §4 reads mail from it.
    logPath: string;
    adminEmail: string;
    adminPassword: string;
    /// SIGTERM, wait up to 10s, then SIGKILL (spec §2 "Readiness").
    stop(): Promise<void>;
}
