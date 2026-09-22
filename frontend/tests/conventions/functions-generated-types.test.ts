/**
 * U8 (docs/spec/function-ui.md §6): api/functions.ts must use the generated
 * function types, not hand-rolled duplicates. Two checks, same convention
 * style as no-void-api-assignment.test.ts:
 *
 *  1. api/functions.ts actually imports from generated-functions (so a
 *     backend contract change flows through to a compile error there,
 *     the same guarantee api/dispatch-pools.ts gets from ./generated).
 *  2. No file under src/api (other than the generated-* directories
 *     themselves) declares an `interface`/`type` whose name collides with
 *     a name the generated function types module exports — that would be
 *     a hand-written shadow of a generated contract type.
 */

import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../..", import.meta.url));
const SRC = join(ROOT, "src");
const API_DIR = join(SRC, "api");
const GENERATED_FUNCTIONS_INDEX = join(
	API_DIR,
	"generated-functions",
	"index.ts",
);
const GENERATED_DIR_NAMES = new Set(["generated", "generated-functions"]);

function generatedFunctionTypeNames(): Set<string> {
	const text = readFileSync(GENERATED_FUNCTIONS_INDEX, "utf-8");
	const match = text.match(/export type \{([^}]+)\} from ['"]\.\/types\.gen['"]/);
	if (!match) {
		throw new Error(
			`Could not parse the generated export list from ${relative(ROOT, GENERATED_FUNCTIONS_INDEX)}`,
		);
	}
	return new Set(
		match[1]!
			.split(",")
			.map((s) => s.trim())
			.filter(Boolean),
	);
}

function* walkApiFiles(dir: string): Generator<string> {
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
			if (GENERATED_DIR_NAMES.has(entry)) continue;
			yield* walkApiFiles(full);
		} else if (st.isFile() && entry.endsWith(".ts")) {
			yield full;
		}
	}
}

const DECL_RE = /^\s*export\s+(?:interface|type)\s+(\w+)\b/;

describe("api/functions.ts uses the generated function types", () => {
	it("imports from generated-functions", () => {
		const text = readFileSync(join(API_DIR, "functions.ts"), "utf-8");
		expect(text).toMatch(/from ["']\.\/generated-functions["']/);
	});

	it("declares no hand-written interface/type shadowing a generated function type", () => {
		const generatedNames = generatedFunctionTypeNames();
		expect(generatedNames.size, "sanity: expected generated-functions to export types").toBeGreaterThan(0);

		const offences: { file: string; line: number; name: string }[] = [];
		for (const file of walkApiFiles(API_DIR)) {
			const lines = readFileSync(file, "utf-8").split("\n");
			for (let i = 0; i < lines.length; i++) {
				const m = lines[i]!.match(DECL_RE);
				if (m && generatedNames.has(m[1]!)) {
					offences.push({ file: relative(ROOT, file), line: i + 1, name: m[1]! });
				}
			}
		}

		if (offences.length > 0) {
			const detail = offences
				.map((o) => `  ${o.file}:${o.line} — hand-written "${o.name}" shadows a generated function type`)
				.join("\n");
			throw new Error(
				`Found ${offences.length} hand-written type(s) shadowing generated-functions exports:\n${detail}`,
			);
		}
	});
});
