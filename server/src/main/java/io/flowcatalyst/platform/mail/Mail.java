package io.flowcatalyst.platform.mail;

import java.util.Objects;

/// One outbound HTML message to one recipient (`docs/spec/auth-identity.md` §9).
public record Mail(String to, String subject, String html) {
    public Mail {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(html, "html");
    }
}
