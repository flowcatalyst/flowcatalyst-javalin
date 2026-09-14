import { strict as assert } from "node:assert";
import { describe, it } from "node:test";
import {
	createSessionCrypto,
	generateSessionSecret,
} from "../../src/fastify/crypto.js";

describe("session crypto", () => {
	it("round-trips a payload", async () => {
		const c = createSessionCrypto(generateSessionSecret());
		const env = await c.encrypt("hello world");
		const out = await c.decrypt(env);
		assert.equal(out, "hello world");
	});

	it("supports key rotation: any key can decrypt", async () => {
		const k1 = generateSessionSecret();
		const k2 = generateSessionSecret();
		const oldCrypto = createSessionCrypto(k1);
		const env = await oldCrypto.encrypt("legacy payload");

		// Rotate: new instance has k2 first, k1 second.
		const rotated = createSessionCrypto([k2, k1]);
		assert.equal(await rotated.decrypt(env), "legacy payload");

		// New writes use k2; old key alone cannot decrypt.
		const fresh = await rotated.encrypt("new payload");
		assert.equal(await createSessionCrypto(k2).decrypt(fresh), "new payload");
		assert.equal(await createSessionCrypto(k1).decrypt(fresh), null);
	});

	it("returns null for tampered envelope", async () => {
		const c = createSessionCrypto(generateSessionSecret());
		const env = await c.encrypt("secret");
		const parts = env.split(".");
		// Flip one byte of the ciphertext.
		const ctBytes = Buffer.from(parts[2]!, "base64url");
		ctBytes[0] = ctBytes[0]! ^ 0x01;
		const tampered = `${parts[0]}.${parts[1]}.${ctBytes.toString("base64url")}`;
		assert.equal(await c.decrypt(tampered), null);
	});

	it("returns null for wrong key", async () => {
		const a = createSessionCrypto(generateSessionSecret());
		const b = createSessionCrypto(generateSessionSecret());
		const env = await a.encrypt("secret");
		assert.equal(await b.decrypt(env), null);
	});

	it("returns null for malformed envelope", async () => {
		const c = createSessionCrypto(generateSessionSecret());
		assert.equal(await c.decrypt("garbage"), null);
		assert.equal(await c.decrypt("v2.aaaa.bbbb"), null);
		assert.equal(await c.decrypt("v1.short.bbbb"), null);
	});

	it("rejects keys that don't decode to 32 bytes", () => {
		assert.throws(() => createSessionCrypto("too-short"));
		assert.throws(() => createSessionCrypto([]));
	});
});
