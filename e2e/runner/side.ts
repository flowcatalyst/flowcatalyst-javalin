import { spawn, execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtempSync, mkdirSync, createWriteStream, appendFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { freePorts } from "./ports.js";
import { generateJwtSigningKey, generateAppKey, sharedEnv } from "./env.js";
import { waitForHealth } from "./health.js";
import { buildGoFcdev, buildJavaFcdev, goRepoRoot, resolveJavaHome, JAVA_REPO_ROOT } from "./build.js";
import type { RunningSide, Side } from "./types.js";

const execFileAsync = promisify(execFile);

const E2E_DIR = path.resolve(import.meta.dirname, "..");
const TEST_RESULTS_DIR = path.join(E2E_DIR, "test-results");

export const ADMIN_EMAIL = "e2e-admin@example.com";
export const ADMIN_PASSWORD = "Tromso-Nebula-7734!";

/// `fcdev`'s two flag surfaces (`docs/spec/fcdev-commands.md`): the `start`
/// flags both binaries mirror exactly, plus `init`'s own flags. Built here
/// so `side.ts` is the one place that knows the exact CLI shape.
function startArgs(opts: { apiPort: number; metricsPort: number; embeddedDbPort: number; embeddedDbPath: string; pidFile: string }): string[] {
    return [
        "start",
        "--api-port", String(opts.apiPort),
        "--metrics-port", String(opts.metricsPort),
        "--embedded-db-path", opts.embeddedDbPath,
        "--embedded-db-port", String(opts.embeddedDbPort),
        "--embedded-db-reset",
        "--pid-file", opts.pidFile,
    ];
}

function initArgs(opts: { databaseUrl: string; root: string; apiBaseUrl: string; adminEmail: string; adminPassword: string }): string[] {
    return [
        "init",
        "--yes",
        "--database-url", opts.databaseUrl,
        "--admin-email", opts.adminEmail,
        "--admin-password", opts.adminPassword,
        "--code", "e2e",
        "--name", "E2E",
        "--root", opts.root,
        "--api-base-url", opts.apiBaseUrl,
    ];
}

interface Launcher {
    command: string;
    baseArgs: string[];
    env?: NodeJS.ProcessEnv;
}

async function resolveLauncher(side: Side, scratchDir: string): Promise<Launcher> {
    if (side === "go") {
        const bin = await buildGoFcdev(scratchDir);
        return { command: bin, baseArgs: [] };
    }
    const jar = await buildJavaFcdev();
    const javaHome = await resolveJavaHome();
    // The router module ships a preview-feature class (structured
    // concurrency) — `--enable-preview` must be a JVM flag, before `-jar`,
    // not just a compiler flag (`docs/STATUS.md`: "compiler args *and*
    // runtime").
    return { command: path.join(javaHome, "bin", "java"), baseArgs: ["--enable-preview", "-jar", jar] };
}

/// Starts one side end-to-end (spec §2): build (or reuse) its `fcdev`,
/// launch `start` against a fresh embedded Postgres, wait for `/health`,
/// then run `init` against that same database to create the bootstrap
/// admin + default client + application. Neither Go's nor Java's `init`
/// takes embedded-db flags (only `--database-url`) — see the brief — so
/// the embedded Postgres is always brought up through `start` first.
export async function startSide(side: Side): Promise<RunningSide> {
    mkdirSync(TEST_RESULTS_DIR, { recursive: true });
    const scratchDir = mkdtempSync(path.join(tmpdir(), `fc-e2e-${side}-`));
    const [apiPort, metricsPort, embeddedDbPort] = await freePorts(3);
    const baseUrl = `http://localhost:${apiPort}`;
    const embeddedDbPath = path.join(scratchDir, "pg");
    const pidFile = path.join(scratchDir, "fcdev.pid");

    const jwtSigningKeyPath = await generateJwtSigningKey(scratchDir);
    const appKey = generateAppKey();
    const env: NodeJS.ProcessEnv = {
        ...process.env,
        ...sharedEnv({
            apiPort, metricsPort, embeddedDbPort, baseUrl, scratchDir, jwtSigningKeyPath, appKey,
            adminEmail: ADMIN_EMAIL, adminPassword: ADMIN_PASSWORD,
        }),
    };
    delete env.FC_SMTP_HOST;
    delete env.SMTP_HOST;

    const launcher = await resolveLauncher(side, scratchDir);
    const logPath = path.join(TEST_RESULTS_DIR, `${side}.log`);
    const log = createWriteStream(logPath, { flags: "w" });
    log.write(`>> starting ${side} fcdev: ${launcher.command} ${[...launcher.baseArgs, "start"].join(" ")}\n`);

    const args = [...launcher.baseArgs, ...startArgs({ apiPort, metricsPort, embeddedDbPort, embeddedDbPath, pidFile })];
    const child = spawn(launcher.command, args, { env, stdio: ["ignore", "pipe", "pipe"] });
    child.stdout.pipe(log, { end: false });
    child.stderr.pipe(log, { end: false });

    let exited = false;
    let exitInfo = "";
    let onExit: (() => void) | null = null;
    const exitedEarly = new Promise<never>((_resolve, reject) => {
        onExit = () => {
            exited = true;
            reject(new Error(`${side} fcdev exited before becoming healthy (${exitInfo}); see ${logPath}`));
        };
    });
    child.on("exit", (code, signal) => {
        exitInfo = `code=${code} signal=${signal}`;
        onExit?.();
    });

    try {
        // Race the health poll against the process actually exiting — a
        // crash (e.g. the target's own seed data violating its own schema)
        // should fail immediately, not after the full 60s health budget.
        await Promise.race([waitForHealth(baseUrl, 60_000), exitedEarly]);
    } catch (e) {
        if (!exited) {
            child.kill("SIGKILL");
            throw new Error(`${side} fcdev never became healthy: ${String(e)}; see ${logPath}`);
        }
        throw e;
    }

    // ── fcdev init, against the same embedded database ──────────────────
    const databaseUrl = `postgresql://postgres:postgres@localhost:${embeddedDbPort}/flowcatalyst?sslmode=disable`;
    appendFileSync(logPath, `\n>> running ${side} fcdev init\n`);
    try {
        const initArgv = [...launcher.baseArgs, ...initArgs({
            databaseUrl,
            root: scratchDir,
            apiBaseUrl: baseUrl,
            adminEmail: ADMIN_EMAIL,
            adminPassword: ADMIN_PASSWORD,
        })];
        const { stdout, stderr } = await execFileAsync(launcher.command, initArgv, { env, maxBuffer: 16 * 1024 * 1024 });
        appendFileSync(logPath, stdout);
        if (stderr) appendFileSync(logPath, stderr);
    } catch (e) {
        child.kill("SIGTERM");
        const detail = e && typeof e === "object" && "stdout" in e ? String((e as { stdout?: unknown }).stdout ?? "") : "";
        throw new Error(`${side} fcdev init failed: ${String(e)} ${detail}; see ${logPath}`);
    }

    const stop = async (): Promise<void> => {
        if (exited) return;
        child.kill("SIGTERM");
        const deadline = Date.now() + 10_000;
        while (!exited && Date.now() < deadline) {
            await new Promise((r) => setTimeout(r, 200));
        }
        if (!exited) {
            child.kill("SIGKILL");
        }
    };

    return {
        side,
        baseUrl,
        apiPort,
        metricsPort,
        scratchDir,
        logPath,
        adminEmail: ADMIN_EMAIL,
        adminPassword: ADMIN_PASSWORD,
        stop,
    };
}

export { goRepoRoot, JAVA_REPO_ROOT };
