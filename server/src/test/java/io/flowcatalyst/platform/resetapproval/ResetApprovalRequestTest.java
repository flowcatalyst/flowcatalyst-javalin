package io.flowcatalyst.platform.resetapproval;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The reset-approval aggregate's transitions (spec §3.7, §8.6, §11.10), no
/// database: `create` always lands `PENDING`, `reset2fa = true`, expiring
/// in 72h; `approve` / `deny` only leave `PENDING` while unexpired.
class ResetApprovalRequestTest {

    @Test
    void createIsAlwaysPendingWithReset2faTrueAndSeventyTwoHourExpiry() {
        var r = ResetApprovalRequest.create("prn_user1", "cli_x");
        assertThat(r.id()).startsWith("rar_");
        assertThat(r.status()).isEqualTo(ResetApprovalStatus.PENDING);
        assertThat(r.reset2fa()).isTrue();
        assertThat(r.note()).isNull();
        assertThat(r.decidedBy()).isNull();
        assertThat(r.decidedAt()).isNull();
        assertThat(r.expiresAt()).isCloseTo(r.createdAt().plus(ResetApprovalRequest.TTL), within(2));
    }

    @Test
    void approveFromPendingRecordsTheDeciderAndTheNote() {
        var r = ResetApprovalRequest.create("prn_user1", "cli_x");
        Instant now = Instant.now();
        var approved = r.approve("prn_admin1", "looks legit", now);
        assertThat(approved.status()).isEqualTo(ResetApprovalStatus.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("prn_admin1");
        assertThat(approved.note()).isEqualTo("looks legit");
        assertThat(approved.decidedAt()).isEqualTo(now);
        // Everything else about the row is untouched.
        assertThat(approved.id()).isEqualTo(r.id());
        assertThat(approved.principalId()).isEqualTo(r.principalId());
        assertThat(approved.expiresAt()).isEqualTo(r.expiresAt());
    }

    @Test
    void denyFromPendingRecordsDenied() {
        var r = ResetApprovalRequest.create("prn_user1", "cli_x");
        var denied = r.deny("prn_admin1", null, Instant.now());
        assertThat(denied.status()).isEqualTo(ResetApprovalStatus.DENIED);
        assertThat(denied.decidedBy()).isEqualTo("prn_admin1");
    }

    /// The invariant `approve`/`deny` protect: a request already decided
    /// cannot be decided again — this is what
    /// `ResetApprovalRepository#decide`'s SQL guard enforces for real; this
    /// test pins the same rule on the in-memory model.
    @Test
    void aSecondDecisionOnAnAlreadyDecidedRequestIsRejected() {
        var r = ResetApprovalRequest.create("prn_user1", "cli_x").approve("prn_admin1", null, Instant.now());
        assertUseCaseError(() -> r.approve("prn_admin2", null, Instant.now()), "ALREADY_DECIDED");
        assertUseCaseError(() -> r.deny("prn_admin2", null, Instant.now()), "ALREADY_DECIDED");
    }

    @Test
    void aDecisionAfterExpiryIsRejectedEvenIfStillPending() {
        var r = ResetApprovalRequest.create("prn_user1", "cli_x");
        Instant afterExpiry = r.expiresAt().plusSeconds(1);
        assertUseCaseError(() -> r.approve("prn_admin1", null, afterExpiry), "ALREADY_DECIDED");
    }

    private static void assertUseCaseError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo(code);
                });
    }

    private static org.assertj.core.data.TemporalUnitWithinOffset within(long seconds) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(seconds, ChronoUnit.SECONDS);
    }
}
