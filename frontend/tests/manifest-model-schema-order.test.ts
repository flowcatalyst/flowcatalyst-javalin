// @vitest-environment jsdom
/**
 * docs/spec/function-manifest-authoring.md M4: Export follows the JSON Schema's key order.
 * The order lists in manifestModel.ts are copies of the schema's `properties` order; this pins
 * them to the committed schema so the two cannot drift, and pins that a key no list knows is
 * kept rather than silently dropped on export.
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
	CORS_ORDER,
	DB_ORDER,
	ENDPOINT_ORDER,
	LIMITS_ORDER,
	PUBLIC_ROUTE_ORDER,
	SCHEDULE_ORDER,
	SUBSCRIPTION_ORDER,
	TOP_ORDER,
	exportManifest,
	type ManifestModel,
} from "@/pages/functions/manifestModel";

const schema = JSON.parse(
	readFileSync(join(__dirname, "../../server/src/main/resources/schemas/function-manifest.schema.json"), "utf-8"),
);
const keys = (node: { properties?: Record<string, unknown> }) => Object.keys(node.properties ?? {});
const top = schema.properties;

describe("manifest export order mirrors the JSON Schema", () => {
	it("every order list equals the schema's property order at its level", () => {
		expect([...TOP_ORDER]).toEqual(keys(schema).filter((k) => k !== "$schema"));
		expect(LIMITS_ORDER).toEqual(keys(top.limits));
		expect(ENDPOINT_ORDER).toEqual(keys(top.endpoints.items));
		expect(CORS_ORDER).toEqual(keys(top.endpoints.items.properties.cors));
		expect(SUBSCRIPTION_ORDER).toEqual(keys(top.subscriptions.items));
		expect(SCHEDULE_ORDER).toEqual(keys(top.schedules.items));
		expect(PUBLIC_ROUTE_ORDER).toEqual(keys(top.public.items));
		expect(DB_ORDER).toEqual(keys(top.db.items));
	});

	it("a key no order list knows is kept on export, never dropped", () => {
		const model = {
			runtime: "jvm",
			entrypoint: "a.B",
			futureField: 1,
			endpoints: [{ path: "/x", auth: "platform", futureEndpointField: true }],
		} as unknown as ManifestModel;
		const out = exportManifest(model) as Record<string, unknown>;
		expect(out.futureField).toBe(1);
		expect((out.endpoints as Record<string, unknown>[])[0].futureEndpointField).toBe(true);
	});
});
