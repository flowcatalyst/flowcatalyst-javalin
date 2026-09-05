package io.flowcatalyst.platform.mail;

/// A message the transport could not deliver; callers decide whether the
/// user can proceed without it.
public final class MailException extends RuntimeException {
    public MailException(String message) {
        super(message);
    }

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }
}
