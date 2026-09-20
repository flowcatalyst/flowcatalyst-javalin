package io.flowcatalyst.function;

import java.io.Serial;

/// Thrown by [HttpCaller#send] when the host refuses to make a call — the
/// target host is not on the version's `manifest.httpAllow` list, the scheme
/// is not `https` (and the host is not a loopback address), or a redirect
/// would step outside the allowlist (`docs/spec/function-context.md` §2).
/// Unchecked: a refusal is a programming error in the function's own
/// manifest/call, not a routine outcome the function is expected to recover
/// from at the call site (unlike [HttpCaller#send]'s checked `Exception`,
/// which still covers ordinary I/O failure).
///
/// Design note (`docs/function-runner-plan.md` §5): for a JVM function this
/// allowlist is a convention the host enforces on its own mediated client,
/// not a network-level containment boundary — a function could still reach
/// the network directly if it bundled its own HTTP client. [HttpCaller] is
/// simply the only outbound path the host gives a function through this API.
public final class HttpCallRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String host;

    public HttpCallRefusedException(String host) {
        super("outbound call to '" + host + "' refused: not permitted by this function's httpAllow list");
        this.host = host;
    }

    public HttpCallRefusedException(String host, String reason) {
        super("outbound call to '" + host + "' refused: " + reason);
        this.host = host;
    }

    /// The target host the call was refused for.
    public String host() {
        return host;
    }
}
