/**
 * Audit logs must never store passwords or secrets. `operationData` reaches
 * the outbox as the whole command; this walks it and masks anything that
 * looks like a credential before it is serialised into the audit row, so a
 * leak (a command that carries a plaintext token or secret field the way
 * `serviceaccount` Create/Update once did) never reaches `aud_logs`.
 *
 * A key is secret when, lower-cased with `_` and `-` removed, it **ends
 * with** `password`, `passwordhash`, `secret`, `secretref`, `passphrase` or
 * `token`, or **equals** `apikey`, `privatekey`, `authorization` or
 * `cookie`. A secret key's value becomes the string `"***"` — whatever its
 * type — except `null` and booleans, which are kept as-is. `maskedFields`
 * additionally masks top-level field names the caller declares even though
 * the name rule would keep them (e.g. a config value whose secrecy depends
 * on a sibling field). Objects and arrays are walked; everything else is
 * untouched. Pure — never mutates `data`.
 */
export function redactAuditData(
	data: Record<string, unknown>,
	maskedFields: readonly string[] = [],
): Record<string, unknown> {
	return redactObject(data, new Set(maskedFields));
}

const SECRET_SUFFIXES = [
	"password",
	"passwordhash",
	"secret",
	"secretref",
	"passphrase",
	"token",
];

const SECRET_EXACT = new Set(["apikey", "privatekey", "authorization", "cookie"]);

function normalizeKey(key: string): string {
	return key.toLowerCase().replace(/[_-]/g, "");
}

function isSecretKey(key: string): boolean {
	const normalized = normalizeKey(key);
	return (
		SECRET_EXACT.has(normalized) ||
		SECRET_SUFFIXES.some((suffix) => normalized.endsWith(suffix))
	);
}

function maskValue(value: unknown): unknown {
	if (value === null || typeof value === "boolean") {
		return value;
	}
	return "***";
}

function redactValue(value: unknown): unknown {
	if (Array.isArray(value)) {
		return value.map((item) => redactValue(item));
	}
	if (value !== null && typeof value === "object") {
		return redactObject(value as Record<string, unknown>, EMPTY_MASKED);
	}
	return value;
}

const EMPTY_MASKED: ReadonlySet<string> = new Set();

function redactObject(
	obj: Record<string, unknown>,
	topLevelMasked: ReadonlySet<string>,
): Record<string, unknown> {
	const result: Record<string, unknown> = {};
	for (const [key, value] of Object.entries(obj)) {
		if (topLevelMasked.has(key) || isSecretKey(key)) {
			result[key] = maskValue(value);
		} else {
			result[key] = redactValue(value);
		}
	}
	return result;
}
