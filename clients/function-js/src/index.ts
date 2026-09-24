/// `@flowcatalyst/function` — the JavaScript guest library
/// (`docs/spec/function-js-guest.md` §1). A function author imports from
/// here; the package ships no runtime dependencies, so `esbuild` bundles it
/// straight into the guest's single output file.
export { handler } from "./handler.js";
export { Result } from "./result.js";
export { HttpDenied } from "./http-denied.js";
export { EventEmitException } from "./types.js";
export type {
	AnonymousCaller,
	Caller,
	Config,
	Context,
	Events,
	FunctionRequest,
	FunctionResult,
	HttpCaller,
	HttpMethod,
	HttpReply,
	HttpRequestInit,
	LogLevel,
	Logger,
	OutboundEvent,
	PlatformCaller,
	PrincipalCaller,
	Secrets,
} from "./types.js";
