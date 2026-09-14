/**
 * Schema Code Generation
 *
 * Generates language-specific code from JSON Schema objects.
 */

export { generateTypeScriptInterface } from "./schema-to-typescript.js";
export { generatePhpDto } from "./schema-to-php.js";
export { generatePythonDataclass } from "./schema-to-python.js";
export { generateJavaRecord } from "./schema-to-java.js";
export { generateExample } from "./schema-example.js";
export type { Schema, SupportedLanguage } from "./types.js";
export { SUPPORTED_LANGUAGES } from "./types.js";

import { generateTypeScriptInterface } from "./schema-to-typescript.js";
import { generatePhpDto } from "./schema-to-php.js";
import { generatePythonDataclass } from "./schema-to-python.js";
import { generateJavaRecord } from "./schema-to-java.js";
import type { Schema, SupportedLanguage } from "./types.js";

/**
 * Generate code for a given language from a JSON Schema.
 */
export function generateCode(
	schema: Schema,
	eventCode: string,
	language: SupportedLanguage,
): string {
	switch (language) {
		case "typescript":
			return generateTypeScriptInterface(schema, eventCode);
		case "php":
			return generatePhpDto(schema, eventCode);
		case "python":
			return generatePythonDataclass(schema, eventCode);
		case "java":
			return generateJavaRecord(schema, eventCode);
	}
}
