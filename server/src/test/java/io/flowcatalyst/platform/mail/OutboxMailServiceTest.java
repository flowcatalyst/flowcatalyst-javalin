package io.flowcatalyst.platform.mail;

import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.flowcatalyst.db.generated.Tables.MAIL_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/mail-outbox.md` §4 row 1: `send(Mail)` inserts and returns —
/// no SMTP happens on the caller's thread, even while a [MailSender] sharing
/// the same table is stuck delivering through a blocked transport.
class OutboxMailServiceTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);

    @Test
    @Timeout(10)
    void sendReturnsBeforeAnySmtpHappens() throws Exception {
        String to = "outbox-" + UUID.randomUUID() + "@example.com";
        CountDownLatch latch = new CountDownLatch(1);
        MailService blockedTransport = mail -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        var repository = new MailOutboxRepository(DS);
        var svc = new OutboxMailService(repository, Clock.systemUTC());
        // A sender sharing the outbox table, stuck delivering through the
        // latch — proves `send` below does not merely happen to be fast in
        // isolation, but is genuinely decoupled from delivery.
        var sender = MailSender.start(DS, blockedTransport, Clock.systemUTC(), Duration.ofMillis(50));
        try {
            long start = System.nanoTime();
            svc.send(new Mail(to, "subject", "<p>hi</p>"));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMs).as("send() does not wait on delivery").isLessThan(1000);

            // The row exists and is PENDING — not skipped, not delivered
            // (the latch is still closed) — while the sender is presumably
            // stuck holding it.
            var row = DB.selectFrom(MAIL_OUTBOX).where(MAIL_OUTBOX.TO_ADDR.eq(to)).fetchOne();
            assertThat(row).as("send() persisted the row").isNotNull();
            assertThat(row.getStatus()).isEqualTo("PENDING");
        } finally {
            latch.countDown();
            sender.close();
        }
    }
}
