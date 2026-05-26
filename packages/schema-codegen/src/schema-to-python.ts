/**
 * Python Dataclass Generator
 *
 * Generates a Python 3.10+ dataclass from a JSON Schema object.
 * Uses @dataclass with type hints, from_dict() and to_dict() methods.
 */

import type { Schema } from "./types.js";

export function generatePythonDataclass(
	schema: Schema,
	eventCode: string,
): string {
	const className = eventCodeToClassName(eventCode);
	const required = new Set(
		Array.isArray(schema["required"])
			? (schema["required"] as string[])
			: [],
	);
	const properties = schema["properties"] as
		| Record<string, Schema>
		| undefined;

	if (!properties) {
		return buildClass(className, [], [], []);
	}

	const fields: string[] = [];
	const fromDictLines: string[] = [];
	const toDictLines: string[] = [];

	// Required fields first, then optional
	const entries = Object.entries(properties);
	const requiredEntries = entries.filter(([key]) => required.has(key));
	const optionalEntries = entries.filter(([key]) => !required.has(key));

	for (const [key, propSchema] of [...requiredEntries, ...optionalEntries]) {
		const isRequired = required.has(key);
		const snakeKey = toSnakeCase(key);
		const pyType = resolvePythonType(propSchema, !isRequired);

		if (isRequired) {
			fields.push(`    ${snakeKey}: ${pyType}`);
		} else {
			fields.push(`    ${snakeKey}: ${pyType} = None`);
		}

		fromDictLines.push(`            ${snakeKey}=data.get("${key}"),`);
		toDictLines.push(`            "${key}": self.${snakeKey},`);
	}

	return buildClass(className, fields, fromDictLines, toDictLines);
}

function resolvePythonType(schema: Schema, nullable: boolean): string {
	// const / literal
	if ("const" in schema) {
		return "str";
	}

	// anyOf — union type
	if (Array.isArray(schema["anyOf"])) {
		return resolveAnyOfPython(schema["anyOf"] as Schema[], nullable);
	}

	if (Array.isArray(schema["oneOf"])) {
		return resolveAnyOfPython(schema["oneOf"] as Schema[], nullable);
	}

	const type = schema["type"];

	// Handle JSON Schema type arrays like ["string", "null"] → "str | None"
	if (Array.isArray(type)) {
		const hasNull = (type as string[]).includes("null");
		const nonNull = (type as string[]).filter((t) => t !== "null");
		if (nonNull.length === 1) {
			return resolvePythonType({ ...schema, type: nonNull[0] }, hasNull || nullable);
		}
		const types = nonNull.map((t) => {
			if (t === "string") return "str";
			if (t === "integer") return "int";
			if (t === "number") return "float";
			if (t === "boolean") return "bool";
			return "Any";
		});
		const union = [...new Set(types)].join(" | ");
		return hasNull || nullable ? `${union} | None` : union;
	}

	if (type === "string") return nullable ? "str | None" : "str";
	if (type === "integer") return nullable ? "int | None" : "int";
	if (type === "number") return nullable ? "float | None" : "float";
	if (type === "boolean") return nullable ? "bool | None" : "bool";
	if (type === "null") return "None";

	if (type === "array") {
		const items = schema["items"] as Schema | undefined;
		if (!items) return "list" + (nullable ? " | None" : "");
		const inner = resolvePythonType(items, false);
		const base = `list[${inner}]`;
		return nullable ? `${base} | None` : base;
	}

	if (type === "object") {
		return resolveObjectPythonType(schema, nullable);
	}

	return "Any";
}

function resolveObjectPythonType(schema: Schema, nullable: boolean): string {
	const properties = schema["properties"] as
		| Record<string, Schema>
		| undefined;

	if (properties) {
		// Nested object → dict
		const base = "dict[str, Any]";
		return nullable ? `${base} | None` : base;
	}

	// Record type via additionalProperties
	const additionalProperties = schema["additionalProperties"] as
		| Schema
		| boolean
		| undefined;
	if (additionalProperties && typeof additionalProperties === "object") {
		const valueType = resolvePythonType(additionalProperties, false);
		const base = `dict[str, ${valueType}]`;
		return nullable ? `${base} | None` : base;
	}

	// Record type via patternProperties
	const patternProperties = schema["patternProperties"] as
		| Record<string, Schema>
		| undefined;
	if (patternProperties) {
		const first = Object.values(patternProperties)[0];
		if (first) {
			const valueType = resolvePythonType(first, false);
			const base = `dict[str, ${valueType}]`;
			return nullable ? `${base} | None` : base;
		}
	}

	const base = "dict[str, Any]";
	return nullable ? `${base} | None` : base;
}

function resolveAnyOfPython(variants: Schema[], nullable: boolean): string {
	// Flatten nested anyOf
	const flat: Schema[] = [];
	for (const v of variants) {
		if (Array.isArray(v["anyOf"])) {
			flat.push(...(v["anyOf"] as Schema[]));
		} else {
			flat.push(v);
		}
	}

	const hasNull = flat.some((v) => v["type"] === "null");
	const nonNull = flat.filter((v) => v["type"] !== "null");

	// All-const → Literal union
	if (nonNull.every((v) => "const" in v)) {
		const literals = nonNull.map((v) => JSON.stringify(v["const"])).join(", ");
		const base = `Literal[${literals}]`;
		return hasNull || nullable ? `${base} | None` : base;
	}

	// Single non-null type
	if (nonNull.length === 1) {
		return resolvePythonType(nonNull[0]!, hasNull || nullable);
	}

	// Multiple non-null types
	const types = nonNull.map((v) => resolvePythonType(v, false));
	const unique = [...new Set(types)];
	const union = unique.join(" | ");
	return hasNull || nullable ? `${union} | None` : union;
}

function buildClass(
	className: string,
	fields: string[],
	fromDictLines: string[],
	toDictLines: string[],
): string {
	const imports: string[] = ["from __future__ import annotations"];
	const needsAny = fields.some((f) => f.includes("Any"));
	const needsLiteral = fields.some((f) => f.includes("Literal["));

	const typingImports: string[] = [];
	if (needsAny) typingImports.push("Any");
	if (needsLiteral) typingImports.push("Literal");
	if (typingImports.length > 0) {
		imports.push(`from typing import ${typingImports.join(", ")}`);
	}

	imports.push("from dataclasses import dataclass");

	const lines: string[] = [
		...imports,
		"",
		"",
		"@dataclass(frozen=True)",
		`class ${className}:`,
	];

	if (fields.length === 0) {
		lines.push("    pass");
	} else {
		lines.push(...fields);
	}

	lines.push("");

	// from_dict
	lines.push(
		"    @classmethod",
		`    def from_dict(cls, data: dict[str, Any]) -> ${className}:`,
		"        return cls(",
	);
	lines.push(...fromDictLines);
	lines.push("        )");

	lines.push("");

	// to_dict
	lines.push(
		`    def to_dict(self) -> dict[str, Any]:`,
		"        return {",
	);
	lines.push(...toDictLines);
	lines.push("        }");

	lines.push("");
	return lines.join("\n");
}

function eventCodeToClassName(eventCode: string): string {
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

function toSnakeCase(s: string): string {
	return s
		.replace(/([A-Z])/g, "_$1")
		.toLowerCase()
		.replace(/^_/, "");
}
