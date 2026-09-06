package io.flowcatalyst.platform.mail;

import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/mail-outbox.md` §4 rows 2-5, over embedded Postgres (`TestPg`)
/// with a real [MailSender] — the deliver-and-mark loop, the retry ladder,
/// the retry cap, and claim exclusivity under two concurrent senders.
class MailSenderTest {

    private static final DataSource DS = TestPg.dataSource();

    // ── row 2: delivers and marks SENT with sent_at ─────────────────────

    @Test
    @Timeout(10)
    void theSenderDeliversAndMarksSentWithSentAt() throws Exception {
        var repo = new MailOutboxRepository(DS);
        String to = unique();
        String id = repo.insertPending(new Mail(to, "s", "<p>h</p>"), Instant.now());

        ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        MailService recording = mail -> deliveries.computeIfAbsent(mail.to(), k -> new AtomicInteger()).incrementAndGet();

        var sender = MailSender.start(DS, recording, Clock.systemUTC(), Duration.ofMillis(50));
        try {
            awaitUntil(() -> repo.find(id).map(r -> "SENT".equals(r.status())).orElse(false), Duration.ofSeconds(5));
        } finally {
            sender.close();
        }

        var row = repo.find(id).orElseThrow();
        assertThat(row.status()).isEqualTo("SENT");
        assertThat(row.sentAt()).as("sent_at stamped").isNotNull();
        // Never re-marked / re-claimed after success: exactly one delivery reached the transport.
        assertThat(deliveries.getOrDefault(to, new AtomicInteger()).get())
                .as("delivered exactly once").isEqualTo(1);
    }

    // ── row 3: a failing transport advances the ladder ──────────────────

    @Test
    @Timeout(15)
    void aFailingTransportAdvancesNextAttemptAtByTheLadder() throws Exception {
        var repo = new MailOutboxRepository(DS);
        ManualClock clock = new ManualClock(Instant.now());
        String to = unique();
        String id = repo.insertPending(new Mail(to, "s", "<p>h</p>"), clock.instant());

        MailService failing = mail -> {
            throw new MailException("boom");
        };
        var sender = MailSender.start(DS, failing, clock, Duration.ofMillis(30));
        try {
            awaitUntil(() -> repo.find(id).map(r -> r.attempts() >= 1).orElse(false), Duration.ofSeconds(5));
            var afterFirst = repo.find(id).orElseThrow();
            assertThat(afterFirst.attempts()).isEqualTo(1);
            assertThat(Duration.between(clock.instant(), afterFirst.nextAttemptAt()).getSeconds())
                    .as("ladder[0] after the first failure").isEqualTo(5);

            clock.advance(Duration.ofSeconds(5));
            awaitUntil(() -> repo.find(id).map(r -> r.attempts() >= 2).orElse(false), Duration.ofSeconds(5));
            var afterSecond = repo.find(id).orElseThrow();
            assertThat(afterSecond.attempts()).isEqualTo(2);
            assertThat(Duration.between(clock.instant(), afterSecond.nextAttemptAt()).getSeconds())
                    .as("ladder[1] after the second failure").isEqualTo(15);
        } finally {
            sender.close();
        }
    }

    // ── row 4: the retry cap ─────────────────────────────────────────────

    @Test
    @Timeout(30)
    void afterSixFailuresTheRowIsDeadAndNoLongerClaimed() throws Exception {
        var repo = new MailOutboxRepository(DS);
        ManualClock clock = new ManualClock(Instant.now());
        String to = unique();
        String id = repo.insertPending(new Mail(to, "s", "<p>h</p>"), clock.instant());

        AtomicInteger calls = new AtomicInteger();
        MailService failing = mail -> {
            calls.incrementAndGet();
            throw new MailException("boom");
        };
        int[] ladder = {5, 15, 30, 60, 120};
        var sender = MailSender.start(DS, failing, clock, Duration.ofMillis(20));
        try {
            for (int i = 0; i < 6; i++) {
                int attemptNumber = i + 1;
                awaitUntil(() -> calls.get() >= attemptNumber, Duration.ofSeconds(5));
                if (attemptNumber < 6) {
                    clock.advance(Duration.ofSeconds(ladder[Math.min(attemptNumber - 1, ladder.length - 1)]));
                }
            }
            awaitUntil(() -> repo.find(id).map(r -> "FAILED".equals(r.status())).orElse(false), Duration.ofSeconds(5));
            var dead = repo.find(id).orElseThrow();
            assertThat(dead.status()).isEqualTo("FAILED");
            assertThat(dead.attempts()).isEqualTo(6);
            assertThat(calls.get()).as("exactly 6 delivery attempts").isEqualTo(6);

            // Advance well past any conceivable schedule and confirm no 7th call.
            clock.advance(Duration.ofDays(1));
            Thread.sleep(300);
            assertThat(calls.get()).as("a FAILED row is never claimed again").isEqualTo(6);
        } finally {
            sender.close();
        }
    }

    // ── row 5: two senders never deliver the same row ───────────────────

    @Test
    @Timeout(20)
    void twoSendersNeverDeliverTheSameRowTwice() throws Exception {
        var repo = new MailOutboxRepository(DS);
        Instant now = Instant.now();
        List<String> tos = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String to = unique();
            tos.add(to);
            repo.insertPending(new Mail(to, "s", "<p>h</p>"), now);
        }

        ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        MailService recording = mail -> deliveries.computeIfAbsent(mail.to(), k -> new AtomicInteger()).incrementAndGet();

        var senderA = MailSender.start(DS, recording, Clock.systemUTC(), Duration.ofMillis(20));
        var senderB = MailSender.start(DS, recording, Clock.systemUTC(), Duration.ofMillis(20));
        try {
            awaitUntil(() -> tos.stream().allMatch(t -> deliveries.containsKey(t)), Duration.ofSeconds(15));
            // Give a would-be duplicate delivery time to show up before asserting.
            Thread.sleep(500);
        } finally {
            senderA.close();
            senderB.close();
        }

        for (String to : tos) {
            assertThat(deliveries.getOrDefault(to, new AtomicInteger()).get())
                    .as("to=%s delivered exactly once", to).isEqualTo(1);
        }
    }

    private static String unique() {
        return "mailsender-" + UUID.randomUUID() + "@example.com";
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(20);
        }
    }

    /// A [Clock] the test advances by hand, so ladder timing (seconds to
    /// minutes) can be asserted without the test itself waiting real time.
    private static final class ManualClock extends Clock {
        private volatile Instant now;

        ManualClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
