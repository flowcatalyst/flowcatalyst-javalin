/**
 * Shared types for schema code generation.
 */

export type Schema = Record<string, unknown>;

export type SupportedLanguage = "typescript" | "php" | "python" | "java";

export const SUPPORTED_LANGUAGES: readonly SupportedLanguage[] = [
	"typescript",
	"php",
	"python",
	"java",
] as const;
