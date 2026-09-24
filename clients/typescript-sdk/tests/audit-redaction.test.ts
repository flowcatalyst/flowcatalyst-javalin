/**
 * Audit logs must never store passwords or secrets (docs/spec/audit-redaction.md).
 * `tests/fixtures/audit-redaction-vectors.json` is a byte-identical copy of
 * the canonical `docs/spec/audit-redaction-vectors.json` — every case here
 * must pass in every SDK and in the platform.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { redactAuditData } from "../src/outbox/audit-redaction.js";
import { CreateAuditLogDto } from "../src/outbox/create-audit-log-dto.js";

interface Vector {
	name: string;
	input: Record<string, unknown>;
	masked: string[];
	expected: Record<string, unknown>;
}

const here = dirname(fileURLToPath(import.meta.url));
const vectors: Vector[] = JSON.parse(
	readFileSync(join(here, "fixtures", "audit-redaction-vectors.json"), "utf8"),
);

test("the fixture copy is byte-identical to the canonical spec vectors", () => {
	const canonicalPath = join(here, "..", "..", "..", "docs", "spec", "audit-redaction-vectors.json");
	const canonical = readFileSync(canonicalPath, "utf8");
	const copy = readFileSync(join(here, "fixtures", "audit-redaction-vectors.json"), "utf8");
	assert.equal(copy, canonical);
});

test("redactAuditData never mutates its input", () => {
	const input = { password: "hunter2", nested: { token: "t" } };
	const before = JSON.stringify(input);
	redactAuditData(input);
	assert.equal(JSON.stringify(input), before);
});

for (const vector of vectors) {
	test(`vector: ${vector.name}`, () => {
		const actual = redactAuditData(vector.input, vector.masked);
		assert.deepStrictEqual(actual, vector.expected);
	});
}

test("a password in operationData never appears in the outbox payload string", () => {
	const dto = CreateAuditLogDto.create("Principal", "p_1", "CREATE").withOperationData({
		email: "a@b.c",
		password: "hunter2",
		webhookCredentials: { token: "tok", signingSecret: "s3" },
	});

	const payload = JSON.stringify(dto.toPayload());

	assert.ok(!payload.includes("hunter2"), "password value must not appear in the payload");
	assert.ok(!payload.includes("tok\""), "nested token value must not appear in the payload");
	assert.ok(!payload.includes("s3\""), "nested secret value must not appear in the payload");
	assert.ok(payload.includes("a@b.c"), "non-secret fields must survive redaction");
});

test("CreateAuditLogDto.withOperationData masks a caller-declared field the name rule would keep", () => {
	const dto = CreateAuditLogDto.create("PlatformConfig", "cfg_1", "SET_PROPERTY").withOperationData(
		{ property: "key", value: "sk_live_123", valueType: "SECRET" },
		["value"],
	);

	const operationData = JSON.parse(dto.toPayload().operationData as string);
	assert.equal(operationData.value, "***");
	assert.equal(operationData.valueType, "SECRET", "unmasked sibling fields survive");
});
