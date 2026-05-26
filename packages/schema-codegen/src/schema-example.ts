/**
 * Schema Example Generator
 *
 * Walks a JSON Schema object and produces a sample payload.
 * Handles the subset of JSON Schema that TypeBox generates.
 */

export function generateExample(schema: Record<string, unknown>): unknown {
	// Literal / const
	if ("const" in schema) {
		return schema["const"];
	}

	// anyOf (TypeBox unions) — pick first non-null, fallback to null
	if (Array.isArray(schema["anyOf"])) {
		const variants = schema["anyOf"] as Record<string, unknown>[];
		const nonNull = variants.find((v) => v["type"] !== "null");
		return generateExample(nonNull ?? variants[0]!);
	}

	// oneOf — same approach
	if (Array.isArray(schema["oneOf"])) {
		const variants = schema["oneOf"] as Record<string, unknown>[];
		const nonNull = variants.find((v) => v["type"] !== "null");
		return generateExample(nonNull ?? variants[0]!);
	}

	const type = schema["type"];

	if (type === "object") {
		const properties = schema["properties"] as
			| Record<string, Record<string, unknown>>
			| undefined;

		if (properties) {
			const result: Record<string, unknown> = {};
			for (const [key, propSchema] of Object.entries(properties)) {
				result[key] = generateExample(propSchema);
			}
			return result;
		}

		// Record<string, T> — patternProperties or additionalProperties
		const additionalProperties = schema["additionalProperties"] as
			| Record<string, unknown>
			| undefined;
		if (additionalProperties && typeof additionalProperties === "object") {
			return { key: generateExample(additionalProperties) };
		}

		const patternProperties = schema["patternProperties"] as
			| Record<string, Record<string, unknown>>
			| undefined;
		if (patternProperties) {
			const first = Object.values(patternProperties)[0];
			if (first) return { key: generateExample(first) };
		}

		return {};
	}

	if (type === "array") {
		const items = schema["items"] as Record<string, unknown> | undefined;
		return items ? [generateExample(items)] : [];
	}

	if (type === "string") return "string";
	if (type === "integer") return 0;
	if (type === "number") return 0;
	if (type === "boolean") return true;
	if (type === "null") return null;

	// Unknown / empty schema
	return {};
}
