package io.flowcatalyst.platform.loginattempt;

import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository.FailureStats;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository.ListFilter;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_LOGIN_ATTEMPTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The store (spec §5, §6): attempts written through the repository read
/// back exactly, the write leaves no envelope trace, the keyset order and
/// cursor, the filters, and the backoff statistics. Rows are scoped to a
/// per-JVM identifier namespace so tests never see one another's rows.
class LoginAttemptRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final LoginAttemptRepository repo = new LoginAttemptRepository(DS);

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String ADA = "ada." + RUN + "@example.test";
    private static final String BOB = "bob." + RUN + "@example.test";
    private static final String IP_A = "10.0.0.1";
    private static final String IP_B = "10.0.0.2";
    private static final Instant BASE = Instant.parse("2026-03-10T12:00:00.250000Z");

    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static List<String> adaNewestFirst;

    @BeforeAll
    static void seed() {
        // Ada: newest first — success@0, failure@-1 (IP_A), failure@-2 (IP_B), two failures@-3 (IP_A, tie), success@-10
        String s0 = record(AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, ADA, PRINCIPAL, IP_A, null, null, BASE);
        String f1 = record(AttemptType.USER_LOGIN, AttemptOutcome.FAILURE, ADA, null, IP_A, null, "Invalid credentials", BASE.minusSeconds(1));
        String f2 = record(AttemptType.USER_LOGIN, AttemptOutcome.FAILURE, ADA, null, IP_B, null, "Invalid credentials", BASE.minusSeconds(2));
        String f3a = record(AttemptType.USER_LOGIN, AttemptOutcome.FAILURE, ADA, PRINCIPAL, IP_A, "Mozilla", "Invalid 2FA code", BASE.minusSeconds(3));
        String f3b = record(AttemptType.USER_LOGIN, AttemptOutcome.FAILURE, ADA, PRINCIPAL, IP_A, "Mozilla", "Invalid passkey", BASE.minusSeconds(3));
        String tieHigh = f3a.compareTo(f3b) > 0 ? f3a : f3b;
        String tieLow = tieHigh.equals(f3a) ? f3b : f3a;
        String s10 = record(AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, ADA, PRINCIPAL, null, null, null, BASE.minusSeconds(10));
        adaNewestFirst = List.of(s0, f1, f2, tieHigh, tieLow, s10);
        // Bob: token mints only, never a success
        record(AttemptType.SERVICE_ACCOUNT_TOKEN, AttemptOutcome.FAILURE, BOB, null, null, null, "invalid_client", BASE.minusSeconds(5));
        record(AttemptType.DEVELOPER_TOKEN, AttemptOutcome.FAILURE, BOB, PRINCIPAL, IP_A, null, "invalid_scope", BASE.minusSeconds(6));
    }

    private static String record(AttemptType type, AttemptOutcome outcome, String identifier, String principalId,
                                 String ip, String userAgent, String failureReason, Instant at) {
        var a = new LoginAttempt(EntityType.LOGIN_ATTEMPT.generate(), type, outcome, failureReason, identifier, principalId, ip, userAgent, at);
        repo.recordAttempt(a);
        return a.id();
    }

    private static ListFilter ada() {
        return new ListFilter(null, null, ADA, null, null, null);
    }

    // ── Write ──────────────────────────────────────────────────────────────

    @Test
    void aRecordedAttemptReadsBackExactly() {
        var a = repo.findPage(ada(), null, 1).getFirst();
        assertThat(a.id()).isEqualTo(adaNewestFirst.getFirst());
        assertThat(a.attemptType()).isEqualTo(AttemptType.USER_LOGIN);
        assertThat(a.outcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(a.identifier()).isEqualTo(ADA);
        assertThat(a.principalId()).isEqualTo(PRINCIPAL);
        assertThat(a.ipAddress()).isEqualTo(IP_A);
        assertThat(a.userAgent()).isNull();
        assertThat(a.failureReason()).isNull();
        assertThat(a.attemptedAt()).isEqualTo(BASE);

        var f = repo.findPage(new ListFilter(null, null, ADA, null, null, BASE.minusSeconds(3)), null, 1).getFirst();
        assertThat(f.outcome()).isEqualTo(AttemptOutcome.FAILURE);
        assertThat(f.userAgent()).isEqualTo("Mozilla");
        assertThat(f.failureReason()).isIn("Invalid 2FA code", "Invalid passkey");
    }

    @Test
    void absentDetailsAreStoredAsNullNotEmptyString() {
        var row = DB.selectFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.ID.eq(adaNewestFirst.getLast())).fetchOne();
        assertThat(row).isNotNull();
        assertThat(row.getIpAddress()).isNull();
        assertThat(row.getUserAgent()).isNull();
        assertThat(row.getFailureReason()).isNull();
        assertThat(row.getAttemptType()).isEqualTo("USER_LOGIN");
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    void recordingLeavesNoEnvelopeTrace() {
        // spec §6: a direct write — no domain event, no audit row for this aggregate
        assertThat(DB.fetchCount(DSL.table("aud_logs"), DSL.field("entity_id", String.class).in(adaNewestFirst))).isZero();
        assertThat(DB.fetchCount(DSL.table("msg_events"), DSL.field("subject", String.class).like("%" + adaNewestFirst.getFirst()))).isZero();
    }

    @Test
    void unknownStoredValuesReadLeniently() {
        String id = EntityType.LOGIN_ATTEMPT.generate();
        String who = "foreign." + RUN + "@example.test";
        DB.insertInto(IAM_LOGIN_ATTEMPTS)
                .set(IAM_LOGIN_ATTEMPTS.ID, id)
                .set(IAM_LOGIN_ATTEMPTS.ATTEMPT_TYPE, "SOMETHING_NEW")
                .set(IAM_LOGIN_ATTEMPTS.OUTCOME, "MAYBE")
                .set(IAM_LOGIN_ATTEMPTS.IDENTIFIER, who)
                .execute();
        var a = repo.findRecentByIdentifier(who, 5).getFirst();
        assertThat(a.id()).isEqualTo(id);
        assertThat(a.attemptType()).isEqualTo(AttemptType.USER_LOGIN);
        assertThat(a.outcome()).isEqualTo(AttemptOutcome.SUCCESS);
        assertThat(a.attemptedAt()).as("the column default stamps now()").isNotNull();
    }

    // ── Keyset read ────────────────────────────────────────────────────────

    @Test
    void pageIsNewestFirstWithIdAsTiebreakAndTheCursorContinuesExactly() {
        var first = repo.findPage(ada(), null, 3);
        assertThat(first).extracting(LoginAttempt::id).containsExactlyElementsOf(adaNewestFirst.subList(0, 3));
        var second = repo.findPage(ada(), first.getLast().cursor(), 3);
        assertThat(second).extracting(LoginAttempt::id).containsExactlyElementsOf(adaNewestFirst.subList(3, 6));
        // a cursor inside the tie returns only the lower id of the pair, then the oldest row
        var inTie = repo.findPage(ada(), second.getFirst().cursor(), 3);
        assertThat(inTie).extracting(LoginAttempt::id).containsExactly(adaNewestFirst.get(4), adaNewestFirst.get(5));
        assertThat(repo.findPage(ada(), inTie.getLast().cursor(), 3)).isEmpty();
    }

    @Test
    void filtersAreEqualityAndInclusiveBounds() {
        assertThat(repo.findPage(new ListFilter(null, "FAILURE", ADA, null, null, null), null, 10)).hasSize(4)
                .allSatisfy(a -> assertThat(a.outcome()).isEqualTo(AttemptOutcome.FAILURE));
        assertThat(repo.findPage(new ListFilter("DEVELOPER_TOKEN", null, BOB, null, null, null), null, 10))
                .singleElement().satisfies(a -> assertThat(a.failureReason()).isEqualTo("invalid_scope"));
        assertThat(repo.findPage(new ListFilter(null, null, ADA, PRINCIPAL, null, null), null, 10)).hasSize(4);
        assertThat(repo.findPage(new ListFilter(null, null, ADA, null, BASE.minusSeconds(3), BASE.minusSeconds(1)), null, 10))
                .extracting(LoginAttempt::id).containsExactlyElementsOf(adaNewestFirst.subList(1, 5));
        // an unknown enum string matches nothing rather than being read leniently (spec §3, open question 5)
        assertThat(repo.findPage(new ListFilter("bogus", null, ADA, null, null, null), null, 10)).isEmpty();
        assertThat(repo.findPage(new ListFilter(null, "bogus", ADA, null, null, null), null, 10)).isEmpty();
    }

    @Test
    void limitIsAProgrammingContract() {
        assertThatThrownBy(() -> repo.findPage(ada(), null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.findRecentByIdentifier(ADA, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recentByIdentifierIsTheNewestWindow() {
        assertThat(repo.findRecentByIdentifier(ADA, 2)).extracting(LoginAttempt::id)
                .containsExactlyElementsOf(adaNewestFirst.subList(0, 2));
        assertThat(repo.findRecentByIdentifier("nobody." + RUN + "@example.test", 20)).isEmpty();
    }

    // ── Backoff statistics ─────────────────────────────────────────────────

    @Test
    void lastSuccessIsTheNewestSuccessOrEmpty() {
        assertThat(repo.lastSuccessAt(ADA)).contains(BASE);
        assertThat(repo.lastSuccessAt(BOB)).as("never logged in").isEmpty();
        assertThat(repo.lastSuccessAt("nobody." + RUN + "@example.test")).isEmpty();
    }

    @Test
    void failureStatsArePerIdentifierAndIpSinceTheBoundInclusive() {
        assertThat(repo.failureStatsSince(ADA, IP_A, BASE.minusSeconds(3)))
                .isEqualTo(new FailureStats(3, BASE.minusSeconds(1)));
        assertThat(repo.failureStatsSince(ADA, IP_A, BASE.minusSeconds(2)))
                .isEqualTo(new FailureStats(1, BASE.minusSeconds(1)));
        assertThat(repo.failureStatsSince(ADA, IP_B, BASE.minusSeconds(60)))
                .isEqualTo(new FailureStats(1, BASE.minusSeconds(2)));
        assertThat(repo.failureStatsSince(ADA, IP_A, BASE)).as("a success does not count").isEqualTo(new FailureStats(0, null));
        assertThat(repo.failureStatsSince(ADA, "192.0.2.9", BASE.minusSeconds(60))).isEqualTo(new FailureStats(0, null));
    }

    @Test
    void failureCountSpansEveryIp() {
        assertThat(repo.countFailuresSince(ADA, BASE.minusSeconds(60))).isEqualTo(4);
        assertThat(repo.countFailuresSince(ADA, BASE.minusSeconds(2))).isEqualTo(2);
        assertThat(repo.countFailuresSince(ADA, BASE)).isZero();
        assertThat(repo.countFailuresSince(BOB, BASE.minusSeconds(60))).isEqualTo(2);
    }

    @Test
    void failureStatsRefuseAnInconsistentShape() {
        assertThatThrownBy(() -> new FailureStats(-1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FailureStats(0, BASE)).isInstanceOf(IllegalArgumentException.class);
    }
}
