/// Thrown by [HttpCaller#request] when the host refuses to make a call —
/// the target host is not on the version's `manifest.httpAllow` list, the
/// scheme is not `https` (and the host is not loopback), or the call could
/// not be reached. The JS mirror of `function-api`'s
/// `HttpCallRefusedException` — the host itself never traps a Wasm instance
/// for this (`docs/spec/function-wasm-runtime.md` §4: "a denied host is a
/// guest-visible error, not a trap"); this class is `@flowcatalyst/function`
/// turning the Extism `status: 0` signal into something a handler can
/// `catch` by type instead of remembering to check a magic status code.
export class HttpDenied extends Error {
	constructor(reason: string) {
		super(`outbound call refused: ${reason}`);
		this.name = "HttpDenied";
	}
}
