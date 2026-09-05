import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { existsSync, readdirSync } from "node:fs";
import path from "node:path";

const execFileAsync = promisify(execFile);

/// The Java repo root — this file lives at `e2e/runner/build.ts`, two
/// directories under it.
export const JAVA_REPO_ROOT = path.resolve(import.meta.dirname, "..", "..");

/// The Go repo (READ-ONLY — never written to). Sibling of the Java repo by
/// convention (`tools/sync-frontend.sh`'s own default), overridable with
/// `E2E_GO_REPO` for a differently-laid-out checkout.
export function goRepoRoot(): string {
    return process.env.E2E_GO_REPO ?? path.resolve(JAVA_REPO_ROOT, "..", "flowcatalyst-go");
}

/// `go build -o <scratchDir>/fcdev ./cmd/fcdev`, run from the Go repo with
/// the binary written OUT of that tree. Cached per process — the caller
/// only needs one `fcdev` binary no matter how many sides start.
let goBuildPromise: Promise<string> | null = null;

export function buildGoFcdev(scratchDir: string): Promise<string> {
    if (goBuildPromise) return goBuildPromise;
    goBuildPromise = (async () => {
        const repo = goRepoRoot();
        if (!existsSync(repo)) {
            throw new Error(`buildGoFcdev: Go repo not found at ${repo} (set E2E_GO_REPO)`);
        }
        const out = path.join(scratchDir, "fcdev-go-bin");
        const started = Date.now();
        await execFileAsync("go", ["build", "-o", out, "./cmd/fcdev"], { cwd: repo, maxBuffer: 64 * 1024 * 1024 });
        // eslint-disable-next-line no-console
        console.log(`>> go build ./cmd/fcdev done in ${Date.now() - started}ms -> ${out}`);
        return out;
    })();
    return goBuildPromise;
}

/// `mvn -q -pl fcdev -am package -DskipTests`, run once from the Java repo
/// root. Reuses an already-built shaded jar when one exists (build hygiene:
/// CLAUDE.md warns two concurrent Maven runs clobber `target/`) — set
/// `E2E_FORCE_JAVA_BUILD=1` to force a rebuild.
let javaBuildPromise: Promise<string> | null = null;

export function buildJavaFcdev(): Promise<string> {
    if (javaBuildPromise) return javaBuildPromise;
    javaBuildPromise = (async () => {
        const targetDir = path.join(JAVA_REPO_ROOT, "fcdev", "target");
        const existing = existingShadedJar(targetDir);
        if (existing && process.env.E2E_FORCE_JAVA_BUILD !== "1") {
            console.log(`>> reusing existing fcdev jar ${existing}`);
            return existing;
        }
        const javaHome = await resolveJavaHome();
        const started = Date.now();
        await execFileAsync("mvn", ["-q", "-pl", "fcdev", "-am", "package", "-DskipTests"], {
            cwd: JAVA_REPO_ROOT,
            env: { ...process.env, JAVA_HOME: javaHome },
            maxBuffer: 64 * 1024 * 1024,
        });
        const built = existingShadedJar(targetDir);
        if (!built) {
            throw new Error(`buildJavaFcdev: mvn package finished but no jar found under ${targetDir}`);
        }
        console.log(`>> mvn -pl fcdev -am package -DskipTests done in ${Date.now() - started}ms -> ${built}`);
        return built;
    })();
    return javaBuildPromise;
}

function existingShadedJar(targetDir: string): string | null {
    if (!existsSync(targetDir)) return null;
    const jar = readdirSync(targetDir).find((f) => f.startsWith("flowcatalyst-fcdev-") && f.endsWith(".jar") && !f.startsWith("original-"));
    return jar ? path.join(targetDir, jar) : null;
}

/// `JAVA_HOME`, else `mise where java` (CLAUDE.md: never hardcode the path).
export async function resolveJavaHome(): Promise<string> {
    if (process.env.JAVA_HOME) return process.env.JAVA_HOME;
    const { stdout } = await execFileAsync("mise", ["where", "java"]);
    return stdout.trim();
}
