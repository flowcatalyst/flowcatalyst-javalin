package io.flowcatalyst.function;

import java.io.Serial;

/// Thrown by [Webhook#event(Request)] / [Webhook#schedule(Request)] when a
/// delivery body is not valid JSON, or is valid JSON that is not shaped
/// like the envelope it claims to be (wrong top-level type, a missing or
/// mistyped required field).
public final class WebhookFormatException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WebhookFormatException(String message) {
        super(message);
    }

    public WebhookFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
