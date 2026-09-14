/**
 * TypeScript Interface Generator
 *
 * Generates a TypeScript `export interface` from a JSON Schema object.
 * Handles the subset of JSON Schema that TypeBox generates.
 */

import type { Schema } from "./types.js";

export function generateTypeScriptInterface(
	schema: Schema,
	eventCode: string,
): string {
	const name = eventCodeToInterfaceName(eventCode);
	const required = new Set(
		Array.isArray(schema["required"])
			? (schema["required"] as string[])
			: [],
	);
	const properties = schema["properties"] as
		| Record<string, Schema>
		| undefined;

	if (!properties) {
		return `export interface ${name} {\n  [key: string]: unknown;\n}\n`;
	}

	const lines: string[] = [`export interface ${name} {`];

	for (const [key, propSchema] of Object.entries(properties)) {
		const optional = !required.has(key);
		const type = resolveType(propSchema, "  ");
		lines.push(`  ${key}${optional ? "?" : ""}: ${type};`);
	}

	lines.push("}\n");
	return lines.join("\n");
}

function resolveType(schema: Schema, indent: string): string {
	// const / literal
	if ("const" in schema) {
		return JSON.stringify(schema["const"]);
	}

	// anyOf — union type
	if (Array.isArray(schema["anyOf"])) {
		return resolveAnyOf(schema["anyOf"] as Schema[], indent);
	}

	// oneOf — same treatment
	if (Array.isArray(schema["oneOf"])) {
		return resolveAnyOf(schema["oneOf"] as Schema[], indent);
	}

	const type = schema["type"];

	// Handle JSON Schema type arrays like ["string", "null"] → "string | null"
	if (Array.isArray(type)) {
		const types = (type as string[]).map((t) => {
			if (t === "string") return "string";
			if (t === "integer" || t === "number") return "number";
			if (t === "boolean") return "boolean";
			if (t === "null") return "null";
			if (t === "object") return resolveObjectType(schema, indent);
			if (t === "array") return "unknown[]";
			return "unknown";
		});
		return [...new Set(types)].join(" | ");
	}

	if (type === "string") return "string";
	if (type === "integer" || type === "number") return "number";
	if (type === "boolean") return "boolean";
	if (type === "null") return "null";

	if (type === "array") {
		const items = schema["items"] as Schema | undefined;
		if (!items) return "unknown[]";
		const inner = resolveType(items, indent);
		// Wrap union types in parens for array
		return inner.includes("|") ? `(${inner})[]` : `${inner}[]`;
	}

	if (type === "object") {
		return resolveObjectType(schema, indent);
	}

	return "unknown";
}

function resolveObjectType(schema: Schema, indent: string): string {
	const properties = schema["properties"] as
		| Record<string, Schema>
		| undefined;

	if (properties) {
		const required = new Set(
			Array.isArray(schema["required"])
				? (schema["required"] as string[])
				: [],
		);
		const innerIndent = indent + "  ";
		const lines: string[] = ["{"];

		for (const [key, propSchema] of Object.entries(properties)) {
			const optional = !required.has(key);
			const type = resolveType(propSchema, innerIndent);
			lines.push(`${innerIndent}${key}${optional ? "?" : ""}: ${type};`);
		}

		lines.push(`${indent}}`);
		return lines.join("\n");
	}

	// Record<string, T> via additionalProperties
	const additionalProperties = schema["additionalProperties"] as
		| Schema
		| boolean
		| undefined;
	if (additionalProperties && typeof additionalProperties === "object") {
		const valueType = resolveType(additionalProperties, indent);
		return `Record<string, ${valueType}>`;
	}

	// Record<string, T> via patternProperties
	const patternProperties = schema["patternProperties"] as
		| Record<string, Schema>
		| undefined;
	if (patternProperties) {
		const first = Object.values(patternProperties)[0];
		if (first) {
			const valueType = resolveType(first, indent);
			return `Record<string, ${valueType}>`;
		}
	}

	return "Record<string, unknown>";
}

function resolveAnyOf(variants: Schema[], indent: string): string {
	// Flatten nested anyOf
	const flat: Schema[] = [];
	for (const v of variants) {
		if (Array.isArray(v["anyOf"])) {
			flat.push(...(v["anyOf"] as Schema[]));
		} else {
			flat.push(v);
		}
	}

	// All-const → string literal union
	if (flat.every((v) => "const" in v)) {
		return flat.map((v) => JSON.stringify(v["const"])).join(" | ");
	}

	const types = flat.map((v) => resolveType(v, indent));
	// Deduplicate
	return [...new Set(types)].join(" | ");
}

function eventCodeToInterfaceName(eventCode: string): string {
	const segments = eventCode.split(":");
	const last2 = segments.slice(-2);
	const pascal = last2
		.map((s) =>
			s
				.split(/[-_]/)
				.map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
				.join(""),
		)
		.join("");
	return `${pascal}Data`;
}
