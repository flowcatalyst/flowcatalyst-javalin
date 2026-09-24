# @flowcatalyst/function

The guest library for FlowCatalyst JavaScript functions (`docs/spec/function-js-guest.md`).
JavaScript functions run **through Wasm** (QuickJS via the Extism JS PDK) — the same
invocation ABI the Java runtime uses (`docs/spec/function-wasm-runtime.md` §3-§4).

Zero runtime dependencies: the package ships its TypeScript source directly (`"main":
"./src/index.ts"`), so a function's own `esbuild` bundle step compiles it in place.

## Usage

```ts
import { handler, Result } from "@flowcatalyst/function";

export const handle = handler((req, ctx) => {
	if (req.path === "/healthz") {
		return Result.json(200, { status: "ok" });
	}
	const greeting = ctx.config.get("GREETING") ?? "Hello";
	return Result.json(200, { message: `${greeting}, ${req.pathParams.name ?? "world"}!` });
});
```

Compile with `esbuild` then `extism-js`, against the interface file this package ships:

```bash
esbuild src/index.ts --bundle --format=cjs --target=es2020 --outfile=dist/index.js
extism-js dist/index.js -i node_modules/@flowcatalyst/function/interface.d.ts -o dist/function.wasm
```

See `examples/function-hello-js` for a full example, and `fcdev fn init --lang js` to scaffold
a new one.

## What works, what doesn't

QuickJS is an interpreter with no Node built-ins and no event loop — see `docs/functions.md`
"JavaScript functions" for the full list of constraints and what each `ctx` helper does.

## Development

```bash
npm install
npm test   # vitest — no Wasm toolchain needed
npm run lint  # tsc --noEmit
```
