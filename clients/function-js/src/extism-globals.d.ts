/// Ambient globals the `extism-js` compiler's runtime prelude provides
/// inside the guest (github.com/extism/js-pdk) that this library needs
/// beyond what `@extism/js-pdk`'s own published types declare
/// (`Host`/`Config`/`Http`/`Memory`/`I64`/`PTR` — a devDependency here and
/// in a function project's own `tsconfig.json` `types`, per the PDK's
/// README). `@flowcatalyst/function` itself still ships zero RUNTIME
/// dependencies (`docs/spec/function-js-guest.md` §1) — this is
/// type-checking only.
///
/// `atob`/`btoa` are missing from `@extism/js-pdk@1.1.1`'s published types
/// even though the PDK's own README lists them as fully supported at
/// runtime (`crates/core/src/prelude/src/atob-btoa.ts` upstream) — declared
/// here so `base64.ts` type-checks under a consuming project's restricted
/// `"lib": []` guest tsconfig, which has no other source of them.
declare global {
	function atob(data: string): string;
	function btoa(data: string): string;
}

/// This library's two host functions (`docs/spec/function-wasm-runtime.md`
/// §4) — an augmentation of `@extism/js-pdk`'s own extension point
/// (`declare module "extism:host" { interface user {} }`), which is why
/// this merges instead of conflicting.
declare module "extism:host" {
	interface user {
		fc_secret_get(ptr: PTR): PTR;
		fc_emit_event(ptr: PTR): PTR;
	}
}

export {};
