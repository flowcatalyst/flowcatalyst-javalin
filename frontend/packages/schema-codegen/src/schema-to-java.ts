/**
 * Java Record Generator
 *
 * Generates a Java 17+ record from a JSON Schema object.
 * Includes a static fromMap() factory and a toMap() method.
 */

import type { Schema } from "./types.js";

export function generateJavaRecord(
	schema: Schema,
	eventCode: string,
): string {
	const { packageName, className } = eventCodeToJavaNames(eventCode);
	const required = new Set(
		Array.isArray(schema["required"])
			? (schema["required"] as string[])
			: [],
	);
	const properties = schema["properties"] as
		| Record<string, Schema>
		| undefined;

	if (!properties) {
		return buildRecord(packageName, className, [], new Set(), [], []);
	}

	const components: string[] = [];
	const fromMapLines: string[] = [];
	const toMapLines: string[] = [];
	const imports = new Set<string>();

	for (const [key, propSchema] of Object.entries(properties)) {
		const nullable = !required.has(key);
		const typeInfo = resolveJavaType(propSchema, nullable);

		for (const imp of typeInfo.imports) imports.add(imp);

		components.push(`    ${typeInfo.type} ${key}`);
		fromMapLines.push(`            (${typeInfo.cast}) map.get("${key}")`);
		toMapLines.push(`        map.put("${key}", ${key});`);
	}

	return buildRecord(
		packageName,
		className,
		components,
		imports,
		fromMapLines,
		toMapLines,
	);
}

interface JavaTypeInfo {
	type: string;
	cast: string;
	imports: string[];
}

function resolveJavaType(schema: Schema, nullable: boolean): JavaTypeInfo {
	// anyOf — handle nullable and literal unions
	if (Array.isArray(schema["anyOf"])) {
		return resolveAnyOfJava(schema["anyOf"] as Schema[], nullable);
	}

	if (Array.isArray(schema["oneOf"])) {
		return resolveAnyOfJava(schema["oneOf"] as Schema[], nullable);
	}

	// const / literal
	if ("const" in schema) {
		return { type: "String", cast: "String", imports: [] };
	}

	const type = schema["type"];

	// Handle JSON Schema type arrays like ["string", "null"]
	if (Array.isArray(type)) {
		const nonNull = (type as string[]).filter((t) => t !== "null");
		const hasNull = (type as string[]).includes("null");
		if (nonNull.length === 1) {
			return resolveJavaType({ ...schema, type: nonNull[0] }, hasNull || nullable);
		}
		// Multiple non-null types — fall back to Object
		return { type: "Object", cast: "Object", imports: [] };
	}

	if (type === "string") {
		return { type: "String", cast: "String", imports: [] };
	}
	if (type === "integer") {
		return nullable
			? { type: "Integer", cast: "Integer", imports: [] }
			: { type: "int", cast: "Integer", imports: [] };
	}
	if (type === "number") {
		return nullable
			? { type: "Double", cast: "Double", imports: [] }
			: { type: "double", cast: "Double", imports: [] };
	}
	if (type === "boolean") {
		return nullable
			? { type: "Boolean", cast: "Boolean", imports: [] }
			: { type: "boolean", cast: "Boolean", imports: [] };
	}
	if (type === "null") {
		return { type: "Void", cast: "Void", imports: [] };
	}

	if (type === "array") {
		return resolveArrayJavaType(schema, nullable);
	}

	if (type === "object") {
		return resolveObjectJavaType(schema);
	}

	return { type: "Object", cast: "Object", imports: [] };
}

function resolveArrayJavaType(schema: Schema, _nullable: boolean): JavaTypeInfo {
	const items = schema["items"] as Schema | undefined;
	const imports = ["java.util.List"];

	if (!items) {
		return { type: "List<Object>", cast: "List<Object>", imports };
	}

	const inner = resolveJavaType(items, false);
	const boxed = boxType(inner.type);
	return {
		type: `List<${boxed}>`,
		cast: `List<${boxed}>`,
		imports: [...imports, ...inner.imports],
	};
}

function resolveObjectJavaType(schema: Schema): JavaTypeInfo {
	const properties = schema["properties"] as
		| Record<string, Schema>
		| undefined;
	const imports = ["java.util.Map"];

	if (properties) {
		// Nested object → Map<String, Object>
		return {
			type: "Map<String, Object>",
			cast: "Map<String, Object>",
			imports,
		};
	}

	// Record via additionalProperties
	const additionalProperties = schema["additionalProperties"] as
		| Schema
		| boolean
		| undefined;
	if (additionalProperties && typeof additionalProperties === "object") {
		const inner = resolveJavaType(additionalProperties, false);
		const boxed = boxType(inner.type);
		return {
			type: `Map<String, ${boxed}>`,
			cast: `Map<String, ${boxed}>`,
			imports: [...imports, ...inner.imports],
		};
	}

	// Record via patternProperties
	const patternProperties = schema["patternProperties"] as
		| Record<string, Schema>
		| undefined;
	if (patternProperties) {
		const first = Object.values(patternProperties)[0];
		if (first) {
			const inner = resolveJavaType(first, false);
			const boxed = boxType(inner.type);
			return {
				type: `Map<String, ${boxed}>`,
				cast: `Map<String, ${boxed}>`,
				imports: [...imports, ...inner.imports],
			};
		}
	}

	return {
		type: "Map<String, Object>",
		cast: "Map<String, Object>",
		imports,
	};
}

function resolveAnyOfJava(variants: Schema[], nullable: boolean): JavaTypeInfo {
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

	// All-const → String (with Javadoc comment showing allowed values)
	if (nonNull.every((v) => "const" in v)) {
		return { type: "String", cast: "String", imports: [] };
	}

	// Single non-null type → that type (boxed if nullable)
	if (nonNull.length === 1) {
		const inner = resolveJavaType(nonNull[0]!, hasNull || nullable);
		if (hasNull || nullable) {
			return { ...inner, type: boxType(inner.type) };
		}
		return inner;
	}

	return { type: "Object", cast: "Object", imports: [] };
}

function boxType(type: string): string {
	switch (type) {
		case "int":
			return "Integer";
		case "double":
			return "Double";
		case "boolean":
			return "Boolean";
		default:
			return type;
	}
}

function buildRecord(
	packageName: string,
	className: string,
	components: string[],
	imports: Set<string>,
	fromMapLines: string[],
	toMapLines: string[],
): string {
	const lines: string[] = [`package ${packageName};`];

	// Collect imports — always need Map for fromMap/toMap
	const allImports = new Set(imports);
	allImports.add("java.util.Map");
	allImports.add("java.util.LinkedHashMap");

	const sortedImports = [...allImports].sort();
	lines.push("");
	for (const imp of sortedImports) {
		lines.push(`import ${imp};`);
	}

	lines.push("", `public record ${className}(`);

	if (components.length > 0) {
		for (let i = 0; i < components.length; i++) {
			const suffix = i < components.length - 1 ? "," : "";
			lines.push(`${components[i]}${suffix}`);
		}
	}

	lines.push(") {");

	// fromMap factory
	lines.push("");
	lines.push(`    @SuppressWarnings("unchecked")`);
	lines.push(
		`    public static ${className} fromMap(Map<String, Object> map) {`,
	);
	lines.push(`        return new ${className}(`);

	if (fromMapLines.length > 0) {
		for (let i = 0; i < fromMapLines.length; i++) {
			const suffix = i < fromMapLines.length - 1 ? "," : "";
			lines.push(`${fromMapLines[i]}${suffix}`);
		}
	}

	lines.push("        );");
	lines.push("    }");

	// toMap method
	lines.push("");
	lines.push("    public Map<String, Object> toMap() {");
	lines.push(
		"        var map = new LinkedHashMap<String, Object>();",
	);
	for (const line of toMapLines) {
		lines.push(line);
	}
	lines.push("        return map;");
	lines.push("    }");

	lines.push("}", "");
	return lines.join("\n");
}

function eventCodeToJavaNames(eventCode: string): {
	packageName: string;
	className: string;
} {
	const segments = eventCode.split(":");
	const [app, subdomain, aggregate, event] = segments;
	const packageName = `com.${(app ?? "app").toLowerCase()}.events.${(subdomain ?? "domain").toLowerCase()}.${(aggregate ?? "aggregate").toLowerCase()}`;
	const className = `${pascalCase(aggregate ?? "Unknown")}${pascalCase(event ?? "Event")}Data`;
	return { packageName, className };
}

function pascalCase(s: string): string {
	return s
		.split(/[-_]/)
		.map((w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
		.join("");
}
