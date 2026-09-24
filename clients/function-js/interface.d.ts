// The Wasm interface a FlowCatalyst JS function compiles against
// (`docs/spec/function-js-guest.md` §1) — shipped with
// `@flowcatalyst/function` so an author never writes this by hand. Pass it
// to `extism-js` as the `-i` interface file:
//
//   extism-js dist/index.js -i node_modules/@flowcatalyst/function/interface.d.ts -o dist/function.wasm
//
// `handle` is the one export the host ever calls (the manifest's
// `entrypoint`); `fc_secret_get`/`fc_emit_event` are the two host functions
// `@flowcatalyst/function` calls on a function's behalf
// (`docs/spec/function-wasm-runtime.md` §4) — HTTP, config and logging ride
// Extism's own built-ins and need no entry here.

declare module "main" {
	export function handle(): I32;
}

declare module "extism:host" {
	interface user {
		fc_secret_get(ptr: I64): I64;
		fc_emit_event(ptr: I64): I64;
	}
}
