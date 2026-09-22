import { existsSync } from "fs";
import { defineConfig } from "@hey-api/openapi-ts";

// Default to the backend's committed OpenAPI lockfile — the same contract
// the lockfile coverage test (Java) / `make api-diff` (Go) gate — so the SPA's
// generated types can never drift from what the server actually serves.
// OPENAPI_LIVE=true points at a running server instead (useful while
// iterating on an unmerged backend change).
//
// This file is shared verbatim between the Go repo (`../api/openapi.lock.json`)
// and the Java repo (`../server/src/main/resources/openapi/openapi.lock.json`,
// a byte-for-byte copy of the same file) — it picks whichever path exists at
// run time, so the Go side can take it as-is. See docs/go-mirror/2026-09-14-frontend-source-shared.md.
const goLockfile = "../api/openapi.lock.json";
const javaLockfile = "../server/src/main/resources/openapi/openapi.lock.json";
const livePort = process.env.FC_API_PORT ?? "8080";
const openApiInput =
	process.env.OPENAPI_LIVE === "true"
		? `http://localhost:${livePort}/q/openapi`
		: existsSync(goLockfile)
			? goLockfile
			: javaLockfile;

// The function service's API is a second, separate OpenAPI document (no Go
// counterpart — Java-first, see docs/spec/function-ui.md §1). It is committed
// only in this repo, so there is no Go-vs-Java existence-check pattern to
// mirror; the Java path is the only one that can exist.
const functionsOpenApiInput =
	"../server/src/main/resources/openapi/functions.openapi.json";

// `defineConfig` accepts a single UserConfig or a readonly array of them —
// an array runs every entry through one `openapi-ts` invocation, so
// `pnpm api:generate` regenerates both `src/api/generated` (unchanged) and
// `src/api/generated-functions` (new) without a second script.
export default defineConfig([
	{
		input: openApiInput,
		output: {
			path: "src/api/generated",
		},
		postProcess: [],
		// Types only: the app's transport is the hand-rolled api/client.ts
		// (toasts, 401 handling, field errors). The previously-generated fetch
		// client + SDK were never imported by app code, and the retry layer
		// attached to them never executed.
		plugins: ["@hey-api/typescript"],
	},
	{
		input: functionsOpenApiInput,
		output: {
			path: "src/api/generated-functions",
		},
		postProcess: [],
		plugins: ["@hey-api/typescript"],
	},
]);
