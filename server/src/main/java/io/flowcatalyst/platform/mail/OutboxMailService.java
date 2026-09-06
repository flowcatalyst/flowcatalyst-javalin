package io.flowcatalyst.platform.mail;

import java.time.Clock;
import java.util.Objects;

/// The `MailService` every request-path caller gets (`docs/spec/mail-outbox.md`
/// §2): `send` inserts one `PENDING` row in its own short transaction through
/// the pool and returns at once — no socket, no SMTP conversation, on the
/// caller's thread. The `MailService` contract has no transaction to join; a
/// crash between the caller's own commit and this insert loses the mail
/// exactly as a crash before SMTP did before this unit — the caller's retry
/// path is unchanged. [MailSender] is what actually delivers, off the
/// request path entirely.
public final class OutboxMailService implements MailService {

    private final MailOutboxRepository repository;
    private final Clock clock;

    public OutboxMailService(MailOutboxRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public OutboxMailService(MailOutboxRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void send(Mail mail) {
        repository.insertPending(mail, clock.instant());
    }
}
