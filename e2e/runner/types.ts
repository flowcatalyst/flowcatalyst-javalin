export type Side = "go" | "java";

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
