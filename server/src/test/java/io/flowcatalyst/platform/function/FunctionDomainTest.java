package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec `function-registry.md` §6.5): `claim`,
/// `verified`'s one-way transition, `usableBy`, and the masked `toString`.
class FunctionDomainTest {

    private static final Hostname HOST = Hostname.parse("api.acme.com");

    @Test
    void claimStartsPending() {
        FunctionDomain d = FunctionDomain.claim("clt_1", HOST, "token-abc", Instant.now());
        assertThat(d.id()).startsWith("fnd_");
        assertThat(d.verification()).isInstanceOf(FunctionDomain.Verification.Pending.class);
        assertThat(d.usableBy("clt_1")).as("pending is not usable").isFalse();
    }

    @Test
    void verifiedMovesPendingToVerified() {
        FunctionDomain d = FunctionDomain.claim("clt_1", HOST, "token-abc", Instant.now());
        Instant at = Instant.now().plusSeconds(5);
        FunctionDomain verified = d.verified(at);
        assertThat(verified.verification()).isEqualTo(new FunctionDomain.Verification.Verified(at));
        assertThat(verified.usableBy("clt_1")).isTrue();
        assertThat(verified.usableBy("clt_other")).as("owned by clt_1, not another client").isFalse();
    }

    @Test
    void verifyingAnAlreadyVerifiedDomainConflicts() {
        FunctionDomain verified = FunctionDomain.claim("clt_1", HOST, "token-abc", Instant.now()).verified(Instant.now());
        assertThatThrownBy(() -> verified.verified(Instant.now()))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Conflict.class);
                    assertThat(err.code()).isEqualTo("DOMAIN_ALREADY_VERIFIED");
                });
    }

    @Test
    void toStringNeverPrintsTheVerificationTokenInFull() {
        String token = "super-secret-verification-token";
        FunctionDomain d = FunctionDomain.claim("clt_1", HOST, token, Instant.now());
        assertThat(d.toString()).doesNotContain(token);
    }
}
