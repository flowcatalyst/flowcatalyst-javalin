package io.flowcatalyst.function;

import java.io.IOException;
import java.io.Serial;

/// Thrown by [HttpCaller#send] when the response body passes the host's fixed
/// cap of [#MAX_BYTES] (owner ruling 2026-09-25, `docs/backlog.md` item 9).
/// The host reads the body as a stream and stops at the cap, so one call to an
/// endpoint returning a huge export fails that call rather than exhausting the
/// host and every function on it. Not configurable.
///
/// An [IOException]: the call was made, and receiving its answer failed, the
/// same family as a dropped connection. It is not an [HttpCallRefusedException],
/// which means the host never made the call.
public final class HttpResponseTooLargeException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    /// 16 MiB.
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    /// The error code a guest or log sees.
    public static final String CODE = "RESPONSE_TOO_LARGE";

    private final String host;

    public HttpResponseTooLargeException(String host) {
        super(CODE + ": the response from '" + host + "' is larger than " + MAX_BYTES + " bytes");
        this.host = host;
    }

    /// The host that sent the response.
    public String host() {
        return host;
    }
}
