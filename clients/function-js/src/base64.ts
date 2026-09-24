/// <reference path="./extism-globals.d.ts" />
/// `atob`/`btoa` are available both under Node (tests) and inside the
/// QuickJS guest (`@extism/js-pdk`'s prelude polyfills them) — no `Buffer`,
/// so this works in both without a runtime dependency either way.

export function base64ToBytes(base64: string): Uint8Array {
	if (base64.length === 0) return new Uint8Array(0);
	const binary = atob(base64);
	const bytes = new Uint8Array(binary.length);
	for (let i = 0; i < binary.length; i++) {
		bytes[i] = binary.charCodeAt(i);
	}
	return bytes;
}

export function bytesToBase64(bytes: Uint8Array): string {
	let binary = "";
	for (let i = 0; i < bytes.length; i++) {
		binary += String.fromCharCode(bytes[i]!);
	}
	return btoa(binary);
}
