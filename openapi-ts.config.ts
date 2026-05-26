import { defineConfig } from "@hey-api/openapi-ts";

// Default to the snapshotted JSON spec (refreshed by `just regen-sdks`).
const livePort = process.env.FC_API_PORT ?? "8080";
const openApiInput =
	process.env.OPENAPI_LIVE === "true"
		? `http://localhost:${livePort}/q/openapi`
		: "./openapi/openapi.json";

export default defineConfig({
	input: openApiInput,
	output: {
		path: "src/api/generated",
	},
	postProcess: [],
	plugins: ["@hey-api/typescript", "@hey-api/sdk", "@hey-api/client-fetch"],
});
