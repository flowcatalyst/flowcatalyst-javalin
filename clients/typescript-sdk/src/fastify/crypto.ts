/**
 * AES-256-GCM session-cookie encryption via WebCrypto.
 *
 * No native modules — works on Node 20+, Bun, and Deno. Uses
 * `crypto.subtle` directly, available on `globalThis.crypto`.
 *
 * Envelope (base64url): `v1.<iv>.<ciphertext+tag>`
 *   - `v1`            — version tag, lets us rotate the scheme later
 *   - `iv`            — 12-byte random nonce, base64url-encoded
 *   - `ciphertext+tag`— AES-GCM output (tag appended by WebCrypto), base64url
 *
 * Keys: 32 raw bytes. Pass either a base64/base64url string or a Buffer.
 * Multiple keys are supported for rotation; the first is used to encrypt,
 * any of them can decrypt.
 */

const IV_LEN = 12;
const KEY_LEN = 32;

export interface SessionCrypto {
	encrypt(plaintext: string): Promise<string>;
	decrypt(envelope: string): Promise<string | null>;
}

export function createSessionCrypto(
	secrets: string | readonly string[],
): SessionCrypto {
	const rawKeys = (Array.isArray(secrets) ? secrets : [secrets]) as readonly string[];
	if (rawKeys.length === 0) {
		throw new Error("session crypto requires at least one secret");
	}
	const keyPromises = rawKeys.map((s) => importKey(decodeSecret(s)));

	return {
		async encrypt(plaintext: string): Promise<string> {
			const key = await keyPromises[0]!;
			const iv = crypto.getRandomValues(new Uint8Array(IV_LEN));
			const ct = await crypto.subtle.encrypt(
				{ name: "AES-GCM", iv: toBufferSource(iv) },
				key,
				toBufferSource(new TextEncoder().encode(plaintext)),
			);
			return `v1.${b64uEncode(iv)}.${b64uEncode(new Uint8Array(ct))}`;
		},

		async decrypt(envelope: string): Promise<string | null> {
			const parts = envelope.split(".");
			if (parts.length !== 3 || parts[0] !== "v1") return null;
			const iv = b64uDecode(parts[1]!);
			const ct = b64uDecode(parts[2]!);
			if (iv.byteLength !== IV_LEN) return null;
			for (const keyPromise of keyPromises) {
				try {
					const key = await keyPromise;
					const pt = await crypto.subtle.decrypt(
						{ name: "AES-GCM", iv: toBufferSource(iv) },
						key,
						toBufferSource(ct),
					);
					return new TextDecoder().decode(pt);
				} catch {
					// try next key
				}
			}
			return null;
		},
	};
}

function decodeSecret(secret: string): Uint8Array {
	// Accept base64, base64url, or hex. Reject anything that doesn't yield 32B.
	const tryDecoders: Array<(s: string) => Uint8Array | null> = [
		(s) => tryDecode(() => b64uDecode(s)),
		(s) => tryDecode(() => Uint8Array.from(Buffer.from(s, "base64"))),
		(s) => tryDecode(() => Uint8Array.from(Buffer.from(s, "hex"))),
	];
	for (const dec of tryDecoders) {
		const bytes = dec(secret);
		if (bytes && bytes.byteLength === KEY_LEN) return bytes;
	}
	throw new Error(
		`session secret must decode to ${KEY_LEN} bytes (base64url, base64, or hex)`,
	);
}

function tryDecode(fn: () => Uint8Array): Uint8Array | null {
	try {
		return fn();
	} catch {
		return null;
	}
}

async function importKey(raw: Uint8Array): Promise<CryptoKey> {
	return crypto.subtle.importKey(
		"raw",
		toBufferSource(raw),
		{ name: "AES-GCM" },
		false,
		["encrypt", "decrypt"],
	);
}

/**
 * Coerce a Uint8Array to a BufferSource backed by a plain ArrayBuffer.
 * TS6 distinguishes `Uint8Array<ArrayBuffer>` from `Uint8Array<SharedArrayBuffer>`;
 * WebCrypto wants the former.
 */
function toBufferSource(bytes: Uint8Array): ArrayBuffer {
	const out = new ArrayBuffer(bytes.byteLength);
	new Uint8Array(out).set(bytes);
	return out;
}

function b64uEncode(bytes: Uint8Array): string {
	return Buffer.from(bytes).toString("base64url");
}

function b64uDecode(s: string): Uint8Array {
	return Uint8Array.from(Buffer.from(s, "base64url"));
}

/**
 * Generate a fresh 32-byte secret, base64url-encoded. Convenience for
 * scripts/READMEs: `node -e "console.log(require('@flowcatalyst/sdk/fastify').generateSessionSecret())"`.
 */
export function generateSessionSecret(): string {
	return b64uEncode(crypto.getRandomValues(new Uint8Array(KEY_LEN)));
}
