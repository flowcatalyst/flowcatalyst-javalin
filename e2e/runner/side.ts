import { spawn, execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtempSync, mkdirSync, createWriteStream, appendFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { freePorts } from "./ports.js";
import { generateJwtSigningKey, generateJwtPublicKey, generateAppKey, sharedEnv } from "./env.js";
import { waitForHealth } from "./health.js";
import { buildGoFcdev, buildJavaFcdev, buildRustFcdev, goRepoRoot, rustRepoRoot, resolveJavaHome, JAVA_REPO_ROOT } from "./build.js";
import type { RunningSide, Side } from "./types.js";

const execFileAsync = promisify(execFile);

const E2E_DIR = path.resolve(import.meta.dirname, "..");
const TEST_RESULTS_DIR = path.join(E2E_DIR, "test-results");

export const ADMIN_EMAIL = "e2e-admin@example.com";
export const ADMIN_PASSWORD = "Tromso-Nebula-7734!";

/// `fcdev`'s two flag surfaces (`docs/spec/fcdev-commands.md`): the `start`
/// flags every binary mirrors exactly (Go, Java, and — since the L1 lane of
/// `docs/java-parity-plan.md` — Rust's `bin/fc-dev`), plus `init`'s own
/// flags. Built here so `side.ts` is the one place that knows the exact CLI
/// shape. One shared implementation, not per-side: all three binaries'
/// `start` subcommands take the identical flag set by design (that
/// agreement is itself part of what this lane's Rust changes establish —
/// see `docs/parity/l1.md`), so a per-side branch would just be dead code.
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
    if (side === "rust") {
        const bin = await buildRustFcdev(scratchDir);
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

    if (side === "rust") {
        // Pre-L0 (`docs/java-parity-plan.md` §3): Rust doesn't read
        // FC_JWT_SIGNING_KEY_PATH yet, only its own two-file env names.
        // Once L0 lands the alias this becomes redundant but harmless —
        // both point at the same key.
        env.FC_JWT_PRIVATE_KEY_PATH = jwtSigningKeyPath;
        env.FC_JWT_PUBLIC_KEY_PATH = await generateJwtPublicKey(jwtSigningKeyPath, scratchDir);
        // FC_AUTH_ALLOW_TEST_HEADERS / FC_DEFAULT_BROKER are Rust
        // `fc-dev start`'s own dev defaults (bin/fc-dev/src/main.rs,
        // set-if-unset) — not overridden here, matching how Go/Java's own
        // fcdev also sets its dev defaults itself rather than the runner
        // doing it on their behalf.
        //
        // NOT setting LOG_FORMAT=json here, deliberately, despite it being
        // the one thing standing between mail.ts's parser and a working
        // mail-from-log path on this side (see `docs/parity/e2e-run-1.md`
        // "Mail-from-log path" for the full trace, incl. a real captured
        // JSON mail line proving the shape is otherwise exactly right).
        // e2e run #1 tried it and found it makes the full suite *worse*
        // right now: with JSON logs on, `auth.spec.ts`'s "forgot password"
        // test can finally find its mail and drive a real password reset
        // through to completion, but then fails at the very next
        // assertion (the pre-existing UNAUTHORIZED-vs-"invalid
        // credentials" gap) *before* its cleanup step restores
        // ADMIN_PASSWORD — permanently changing the shared admin's
        // password for the rest of the run and cascading a login-page
        // bounce into every other spec that reuses it. Land this only
        // once that restore path is hardened (or the message gap closes)
        // — not as a silent side effect of a runner env default.
    }

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
    // Rust's embedded Postgres pins its bootstrap password to "flowcatalyst"
    // (bin/fc-dev/src/main.rs: "Pin the password so the data dir and the
    // connection URL stay consistent across restarts"), not "postgres" —
    // Go's and Java's embedded Postgres both use "postgres", which is why
    // this was never side-branched before e2e run #0 exercised the rust
    // side against a real binary (docs/parity/e2e-run-0.md).
    const embeddedDbPassword = side === "rust" ? "flowcatalyst" : "postgres";
    const databaseUrl = `postgresql://postgres:${embeddedDbPassword}@localhost:${embeddedDbPort}/flowcatalyst?sslmode=disable`;
    appendFileSync(logPath, `\n>> running ${side} fcdev init\n`);
    // Rust's `init` defaults --embedded-db=true (it starts its OWN embedded
    // Postgres unless told otherwise — bin/fc-dev/src/init.rs), which would
    // otherwise ignore the --database-url above and try to bind the same
    // embedded-pg port `start` already holds. Only the init call needs this
    // override; `start`'s own `env` (above) must keep starting the embedded
    // instance.
    const initEnv = side === "rust" ? { ...env, FC_EMBEDDED_DB: "false" } : env;
    try {
        const initArgv = [...launcher.baseArgs, ...initArgs({
            databaseUrl,
            root: scratchDir,
            apiBaseUrl: baseUrl,
            adminEmail: ADMIN_EMAIL,
            adminPassword: ADMIN_PASSWORD,
        })];
        const { stdout, stderr } = await execFileAsync(launcher.command, initArgv, { env: initEnv, maxBuffer: 16 * 1024 * 1024 });
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

export { goRepoRoot, rustRepoRoot, JAVA_REPO_ROOT };
