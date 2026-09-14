/**
 * PHP DTO Generator
 *
 * Generates a PHP 8.1+ readonly DTO class with constructor promotion,
 * fromArray(), and toArray() from a JSON Schema object.
 */

import type { Schema } from "./types.js";

export function generatePhpDto(schema: Schema, eventCode: string): string {
	const { namespace, className } = eventCodeToPhpNames(eventCode);
	const required = new Set(
		Array.isArray(schema["required"])
			? (schema["required"] as string[])
			: [],
	);
	const properties = schema["properties"] as
		| Record<string, Schema>
		| undefined;

	if (!properties) {
		return buildClass(namespace, className, [], [], []);
	}

	const constructorLines: string[] = [];
	const fromArrayLines: string[] = [];
	const toArrayLines: string[] = [];
	const docBlocks: string[] = [];

	for (const [key, propSchema] of Object.entries(properties)) {
		const nullable = !required.has(key);
		const typeInfo = resolvePhpType(propSchema, nullable);

		// PHPDoc for special types
		if (typeInfo.phpDoc) {
			docBlocks.push(
				`    /** @var ${typeInfo.phpDoc} */`,
			);
		}

		const prefix = nullable && typeInfo.type !== "mixed" ? "?" : "";
		constructorLines.push(
			`        public ${prefix}${typeInfo.type} $${key},`,
		);

		fromArrayLines.push(
			`            ${key}: ${typeInfo.fromArray(key)},`,
		);

		toArrayLines.push(
			`            '${key}' => ${typeInfo.toArray(key)},`,
		);
	}

	return buildClass(
		namespace,
		className,
		constructorLines,
		fromArrayLines,
		toArrayLines,
		docBlocks,
	);
}

interface PhpTypeInfo {
	type: string;
	phpDoc: string | null;
	fromArray: (key: string) => string;
	toArray: (key: string) => string;
}

function resolvePhpType(schema: Schema, nullable: boolean): PhpTypeInfo {
	// anyOf — handle nullable and literal unions
	if (Array.isArray(schema["anyOf"])) {
		return resolveAnyOfPhp(schema["anyOf"] as Schema[], nullable);
	}

	if (Array.isArray(schema["oneOf"])) {
		return resolveAnyOfPhp(schema["oneOf"] as Schema[], nullable);
	}

	// const / literal
	if ("const" in schema) {
		return {
			type: "string",
			phpDoc: `'${schema["const"]}'`,
			fromArray: (k) => `$data['${k}']`,
			toArray: (k) => `$this->${k}`,
		};
	}

	const type = schema["type"];

	// Handle JSON Schema type arrays like ["string", "null"]
	if (Array.isArray(type)) {
		const nonNull = (type as string[]).filter((t: string) => t !== "null");
		const hasNull = (type as string[]).includes("null");
		if (nonNull.length === 1) {
			return resolvePhpType({ ...schema, type: nonNull[0] }, hasNull || nullable);
		}
		return simpleType("mixed", false);
	}

	if (type === "string") return simpleType("string", nullable);
	if (type === "integer") return simpleType("int", nullable);
	if (type === "number") return simpleType("float", nullable);
	if (type === "boolean") return simpleType("bool", nullable);
	if (type === "null") return simpleType("mixed", false);

	if (type === "array") {
		return {
			type: "array",
			phpDoc: resolveArrayPhpDoc(schema),
			fromArray: (k) => `$data['${k}']`,
			toArray: (k) => `$this->${k}`,
		};
	}

	if (type === "object") {
		return resolveObjectPhpType(schema, nullable);
	}

	return simpleType("mixed", false);
}

function simpleType(phpType: string, _nullable: boolean): PhpTypeInfo {
	return {
		type: phpType,
		phpDoc: null,
		fromArray: (k) => `$data['${k}']`,
		toArray: (k) => `$this->${k}`,
	};
}

function resolveObjectPhpType(schema: Schema, nullable: boolean): PhpTypeInfo {
	const properties = schema["properties"] as
		| Record<string, Schema>
		| undefined;

	if (properties) {
		// Nested object → array with shape doc
		const required = new Set(
			Array.isArray(schema["required"])
				? (schema["required"] as string[])
				: [],
		);
		const shapeEntries: string[] = [];
		for (const [key, propSchema] of Object.entries(properties)) {
			const innerType = resolvePhpDocType(propSchema, !required.has(key));
			shapeEntries.push(`${key}: ${innerType}`);
		}
		const shape = `array{${shapeEntries.join(", ")}}`;
		return {
			type: "array",
			phpDoc: nullable ? `${shape}|null` : shape,
			fromArray: (k) => `$data['${k}']`,
			toArray: (k) => `$this->${k}`,
		};
	}

	// Record type
	return {
		type: "array",
		phpDoc: nullable ? "array<string, mixed>|null" : "array<string, mixed>",
		fromArray: (k) => `$data['${k}']`,
		toArray: (k) => `$this->${k}`,
	};
}

function resolveAnyOfPhp(variants: Schema[], nullable: boolean): PhpTypeInfo {
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

	// All-const → string with PHPDoc literal union
	if (nonNull.every((v) => "const" in v)) {
		const literals = nonNull.map((v) => `'${v["const"]}'`).join("|");
		return {
			type: "string",
			phpDoc: hasNull ? `${literals}|null` : literals,
			fromArray: (k) => `$data['${k}']`,
			toArray: (k) => `$this->${k}`,
		};
	}

	// Single non-null type → that type, nullable
	if (nonNull.length === 1) {
		return resolvePhpType(nonNull[0]!, hasNull || nullable);
	}

	return simpleType("mixed", false);
}

function resolvePhpDocType(schema: Schema, nullable: boolean): string {
	if (Array.isArray(schema["anyOf"])) {
		const flat: Schema[] = [];
		for (const v of schema["anyOf"] as Schema[]) {
			if (Array.isArray(v["anyOf"])) {
				flat.push(...(v["anyOf"] as Schema[]));
			} else {
				flat.push(v);
			}
		}
		const hasNull = flat.some((v) => v["type"] === "null");
		const nonNull = flat.filter((v) => v["type"] !== "null");

		if (nonNull.every((v) => "const" in v)) {
			const literals = nonNull.map((v) => `'${v["const"]}'`).join("|");
			return hasNull ? `${literals}|null` : literals;
		}

		if (nonNull.length === 1) {
			return resolvePhpDocType(nonNull[0]!, hasNull || nullable);
		}

		return "mixed";
	}

	if ("const" in schema) return `'${schema["const"]}'`;

	const type = schema["type"];
	const suffix = nullable ? "|null" : "";

	// Handle JSON Schema type arrays like ["string", "null"]
	if (Array.isArray(type)) {
		const hasNull = (type as string[]).includes("null");
		const nonNull = (type as string[]).filter((t: string) => t !== "null");
		if (nonNull.length === 1) {
			return resolvePhpDocType({ ...schema, type: nonNull[0] }, hasNull || nullable);
		}
		return "mixed";
	}

	if (type === "string") return `string${suffix}`;
	if (type === "integer") return `int${suffix}`;
	if (type === "number") return `float${suffix}`;
	if (type === "boolean") return `bool${suffix}`;
	if (type === "null") return "null";
	if (type === "array") return `array${suffix}`;
	if (type === "object") return `array${suffix}`;
	return "mixed";
}

function resolveArrayPhpDoc(schema: Schema): string | null {
	const items = schema["items"] as Schema | undefined;
	if (!items) return null;
	const itemType = resolvePhpDocType(items, false);
	return `${itemType}[]`;
}

function buildClass(
	namespace: string,
	className: string,
	constructorLines: string[],
	fromArrayLines: string[],
	toArrayLines: string[],
	_docBlocks: string[] = [],
): string {
	const lines: string[] = [
		"<?php",
		"",
		"declare(strict_types=1);",
		"",
		`namespace ${namespace};`,
		"",
		`final readonly class ${className}`,
		"{",
	];

	// Constructor
	if (constructorLines.length > 0) {
		lines.push("    public function __construct(");
		// Interleave doc blocks with constructor lines
		for (let i = 0; i < constructorLines.length; i++) {
			lines.push(constructorLines[i]!);
		}
		lines.push("    ) {}");
	} else {
		lines.push("    public function __construct() {}");
	}

	lines.push("");

	// fromArray
	lines.push(
		"    /** @param array<string, mixed> $data */",
		"    public static function fromArray(array $data): self",
		"    {",
		"        return new self(",
	);
	for (const line of fromArrayLines) {
		lines.push(line);
	}
	lines.push("        );", "    }");

	lines.push("");

	// toArray
	lines.push(
		"    /** @return array<string, mixed> */",
		"    public function toArray(): array",
		"    {",
		"        return [",
	);
	for (const line of toArrayLines) {
		lines.push(line);
	}
	lines.push("        ];", "    }");

	lines.push("}", "");
	return lines.join("\n");
}

function eventCodeToPhpNames(eventCode: string): {
	namespace: string;
	className: string;
} {
	const segments = eventCode.split(":");
	// e.g. platform:iam:user:created → App=Platform, Subdomain=Iam, Aggregate=User, Event=Created
	const [app, subdomain, aggregate, event] = segments.map(pascalCase);
	const namespace = `Flowcatalyst\\Events\\${app ?? "App"}\\${subdomain ?? "Domain"}\\${aggregate ?? "Aggregate"}`;
	const className = `${aggregate ?? "Unknown"}${event ?? "Event"}Data`;
	return { namespace, className };
}

function pascalCase(s: string): string {
	return s
		.split(/[-_]/)
		.map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
		.join("");
}
