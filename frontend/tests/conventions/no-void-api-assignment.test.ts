/**
 * Convention guard: don't assign the result of a void-returning API call
 * to a Vue reactive ref (or any value used downstream).
 *
 * **Why this rule exists.** Many of our update/PATCH/DELETE backend
 * handlers return `204 No Content` (no body). `apiFetch` resolves to
 * `undefined` for 204 responses. If the frontend wrapper is typed
 * honestly as `Promise<void>` AND a page does:
 *
 *     thing.value = await thingsApi.update(id, ...);
 *
 * …then `thing.value` becomes `undefined`. Every `v-if="thing"` clause
 * in the template flips false, and any `v-else` ("Thing not found",
 * "Failed to load", etc.) flashes momentarily — the user thinks the
 * save failed even though the network call succeeded.
 *
 * **The fix.** Either:
 *   1. Re-fetch from the source of truth after the void call:
 *        await thingsApi.update(id, ...);
 *        await loadThing(id);
 *   2. Or, if the backend should be returning the entity, change the
 *      handler to do so and update the FE wrapper's return type.
 *
 * This test catches the bad pattern across `src/pages` and `src/components`
 * so it can't slip in again. If you genuinely need to silence it for one
 * call site, add a trailing `// fc-api-void: ok` comment on that line.
 */

import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../..", import.meta.url));
const SRC = join(ROOT, "src");
const API_DIR = join(SRC, "api");
const SCAN_DIRS = [join(SRC, "pages"), join(SRC, "components")];

interface VoidMethod {
	apiObject: string; // e.g. "subscriptionsApi"
	method: string;    // e.g. "update"
	file: string;      // for the failure message
	line: number;
}

interface Offence {
	file: string;
	line: number;
	apiObject: string;
	method: string;
	snippet: string;
}

/**
 * Parse each `src/api/*.ts` to find method declarations whose return type
 * is `Promise<void>`. We use a simple line-based regex because the file
 * style is consistent (one method per object, signature on a single line).
 */
function collectVoidMethods(): VoidMethod[] {
	const out: VoidMethod[] = [];
	for (const file of readdirSync(API_DIR)) {
		if (!file.endsWith(".ts")) continue;
		const fullPath = join(API_DIR, file);
		const text = readFileSync(fullPath, "utf-8");
		// Find the exported `<name>Api` object so we can attribute methods to it.
		const apiMatch = text.match(/export const (\w+Api)\s*=\s*\{/);
		if (!apiMatch) continue;
		const apiObject = apiMatch[1]!;
		const lines = text.split("\n");
		for (let i = 0; i < lines.length; i++) {
			// Matches:  `  methodName(args): Promise<void> {`
			// and:      `  methodName(args): Promise<void>;`
			const m = lines[i]!.match(/^\s*(\w+)\s*\([^)]*\)\s*:\s*Promise<void>/);
			if (m) {
				out.push({
					apiObject,
					method: m[1]!,
					file: relative(ROOT, fullPath),
					line: i + 1,
				});
			}
		}
	}
	return out;
}

/**
 * Walk a directory recursively and yield .vue / .ts file paths.
 */
function* walk(dir: string): Generator<string> {
	let entries: string[];
	try {
		entries = readdirSync(dir);
	} catch {
		return;
	}
	for (const entry of entries) {
		const full = join(dir, entry);
		const st = statSync(full);
		if (st.isDirectory()) {
			yield* walk(full);
		} else if (st.isFile() && (entry.endsWith(".vue") || entry.endsWith(".ts"))) {
			yield full;
		}
	}
}

const ASSIGNMENT_RE =
	/(\w+)\.value\s*=\s*await\s+(\w+Api)\.(\w+)\s*\(/;
const ALLOW_COMMENT = /\bfc-api-void:\s*ok\b/;

function scanForOffences(voidByKey: Set<string>): Offence[] {
	const offences: Offence[] = [];
	for (const dir of SCAN_DIRS) {
		for (const file of walk(dir)) {
			const lines = readFileSync(file, "utf-8").split("\n");
			for (let i = 0; i < lines.length; i++) {
				const line = lines[i]!;
				if (ALLOW_COMMENT.test(line)) continue;
				const m = line.match(ASSIGNMENT_RE);
				if (!m) continue;
				const apiObject = m[2]!;
				const method = m[3]!;
				const key = `${apiObject}.${method}`;
				if (voidByKey.has(key)) {
					offences.push({
						file: relative(ROOT, file),
						line: i + 1,
						apiObject,
						method,
						snippet: line.trim(),
					});
				}
			}
		}
	}
	return offences;
}

describe("no void-API assignment to refs", () => {
	it("never assigns the result of a Promise<void> API call to a .value", () => {
		const voidMethods = collectVoidMethods();
		expect(
			voidMethods.length,
			"sanity: expected to find at least one Promise<void> method in src/api",
		).toBeGreaterThan(0);

		const voidByKey = new Set(
			voidMethods.map((v) => `${v.apiObject}.${v.method}`),
		);
		const offences = scanForOffences(voidByKey);

		if (offences.length > 0) {
			const lines = offences
				.map(
					(o) =>
						`  ${o.file}:${o.line}\n` +
						`    ${o.snippet}\n` +
						`    ▸ ${o.apiObject}.${o.method}() returns Promise<void>; the assignment will set the ref to undefined.\n` +
						`      Replace with: await ${o.apiObject}.${o.method}(...); await load…(id);`,
				)
				.join("\n\n");
			throw new Error(
				`Found ${offences.length} assignment(s) of a void-returning API call to a reactive ref.\n` +
					`See frontend/tests/conventions/no-void-api-assignment.test.ts for context.\n\n` +
					lines,
			);
		}
	});
});
