package io.flowcatalyst.platform.loginattempt;

import io.flowcatalyst.platform.shared.apicommon.KeysetCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The pure rules of the aggregate (spec §1, §4): the record's invariants,
/// the lenient enum readers and the row's keyset position, without a
/// database. The cursor encoding itself is [KeysetCursor]'s, tested there.
class LoginAttemptTest {

    private static final Instant T = Instant.parse("2026-08-22T10:11:12.123456Z");

    // ── Record ─────────────────────────────────────────────────────────────

    @Test
    void requiredComponentsAreNonNull() {
        assertThatThrownBy(() -> new LoginAttempt(null, AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, null, null, null, null, null, T))
                .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> new LoginAttempt("lat_1", null, AttemptOutcome.SUCCESS, null, null, null, null, null, T))
                .isInstanceOf(NullPointerException.class).hasMessage("attemptType");
        assertThatThrownBy(() -> new LoginAttempt("lat_1", AttemptType.USER_LOGIN, null, null, null, null, null, null, T))
                .isInstanceOf(NullPointerException.class).hasMessage("outcome");
        assertThatThrownBy(() -> new LoginAttempt("lat_1", AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("attemptedAt");
    }

    @Test
    void attemptIsStampedNowWithAGeneratedIdAndCarriesEveryDetailAsGiven() {
        Instant before = Instant.now();
        var a = LoginAttempt.attempt(AttemptType.DEVELOPER_TOKEN, AttemptOutcome.FAILURE, "Invalid credentials",
                "ada@example.test", "prn_1", "10.0.0.1", "Mozilla");
        assertThat(a.id()).startsWith("lat_").hasSize(17);
        assertThat(a.attemptType()).isEqualTo(AttemptType.DEVELOPER_TOKEN);
        assertThat(a.outcome()).isEqualTo(AttemptOutcome.FAILURE);
        assertThat(a.identifier()).isEqualTo("ada@example.test");
        assertThat(a.principalId()).isEqualTo("prn_1");
        assertThat(a.ipAddress()).isEqualTo("10.0.0.1");
        assertThat(a.userAgent()).isEqualTo("Mozilla");
        assertThat(a.failureReason()).isEqualTo("Invalid credentials");
        assertThat(a.attemptedAt()).isBetween(before, Instant.now());
    }

    @Test
    void absentDetailsStayNull() {
        var a = LoginAttempt.attempt(AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, null, "ada@example.test", null, null, null);
        assertThat(a.principalId()).isNull();
        assertThat(a.ipAddress()).isNull();
        assertThat(a.userAgent()).isNull();
        assertThat(a.failureReason()).isNull();
    }

    @Test
    void cursorIsTheRowPosition() {
        var a = new LoginAttempt("lat_1", AttemptType.USER_LOGIN, AttemptOutcome.SUCCESS, null, null, null, null, null, T);
        assertThat(a.cursor()).isEqualTo(new KeysetCursor(T, "lat_1"));
    }

    // ── Lenient enum readers ───────────────────────────────────────────────

    @ParameterizedTest(name = "attempt_type {0} reads as {1}")
    @CsvSource(nullValues = "NULL", value = {
            "USER_LOGIN, USER_LOGIN",
            "SERVICE_ACCOUNT_TOKEN, SERVICE_ACCOUNT_TOKEN",
            "DEVELOPER_TOKEN, DEVELOPER_TOKEN",
            "bogus, USER_LOGIN",
            "'', USER_LOGIN",
            "NULL, USER_LOGIN",
    })
    void attemptTypeReadsLeniently(String stored, AttemptType expected) {
        assertThat(AttemptType.parse(stored)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "outcome {0} reads as {1}")
    @CsvSource(nullValues = "NULL", value = {
            "SUCCESS, SUCCESS",
            "FAILURE, FAILURE",
            "failure, SUCCESS",
            "'', SUCCESS",
            "NULL, SUCCESS",
    })
    void outcomeReadsLeniently(String stored, AttemptOutcome expected) {
        assertThat(AttemptOutcome.parse(stored)).isEqualTo(expected);
    }
}
