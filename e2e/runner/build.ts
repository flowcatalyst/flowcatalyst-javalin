import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { existsSync, readdirSync } from "node:fs";
import path from "node:path";

const execFileAsync = promisify(execFile);

/// The Java repo root — this file lives at `e2e/runner/build.ts`, two
/// directories under it.
export const JAVA_REPO_ROOT = path.resolve(import.meta.dirname, "..", "..");

/// The Go repo (READ-ONLY — never written to). Sibling of the Java repo by
/// convention (`tools/frontend-drift.sh`'s own default), overridable with
/// `E2E_GO_REPO` for a differently-laid-out checkout.
export function goRepoRoot(): string {
    return process.env.E2E_GO_REPO ?? path.resolve(JAVA_REPO_ROOT, "..", "flowcatalyst-go");
}

/// `go build -o <scratchDir>/fcdev ./cmd/fcdev`, with the binary written OUT
/// of the Go tree. Cached per process — the caller only needs one `fcdev`
/// binary no matter how many sides start.
///
/// Go embeds `frontend/dist` from the tree it is built in (`//go:embed
/// all:dist`), and that directory is a local build artefact the owner
/// refreshes by hand — on 2026-09-06 it was two weeks behind the source, so
/// the first Go run exercised a stale SPA while the (hash-blanking) gate
/// said the sides matched. The build therefore happens in a scratch **copy**
/// of the Go tree whose `frontend/dist` is the Java side's embedded copy
/// (`server/src/main/resources/frontend`, the out-of-tree Vite build that
/// `make frontend` (`tools/build-frontend.sh`) refreshes from this repo's
/// own `frontend/src`): both binaries then serve byte-identical SPAs, and
/// the Go tree is never written to.
/// `E2E_GO_EMBED_TREE_SPA=1` builds in place instead (the tree's own dist).
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
        let buildDir = repo;
        if (process.env.E2E_GO_EMBED_TREE_SPA !== "1") {
            buildDir = path.join(scratchDir, "go-src");
            // Source only: no VCS, no node_modules, no local binaries or build output.
            await execFileAsync("rsync", ["-a", "--delete",
                "--exclude", ".git", "--exclude", "node_modules", "--exclude", "/bin", "--exclude", "/fc-dev",
                "--exclude", "/clients", "--exclude", "/dump-spec", "--exclude", "/parityharness", "--exclude", "/frontend/dist",
                repo + "/", buildDir + "/"]);
            const spa = path.join(JAVA_REPO_ROOT, "server", "src", "main", "resources", "frontend");
            if (!existsSync(path.join(spa, "index.html"))) {
                throw new Error(`buildGoFcdev: no embedded SPA at ${spa} — run make frontend first`);
            }
            await execFileAsync("rsync", ["-a", "--delete", spa + "/", path.join(buildDir, "frontend", "dist") + "/"]);
            console.log(`>> go build: scratch copy of ${repo} with the Java-synced SPA as frontend/dist`);
        }
        await execFileAsync("go", ["build", "-o", out, "./cmd/fcdev"], { cwd: buildDir, maxBuffer: 64 * 1024 * 1024 });
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
