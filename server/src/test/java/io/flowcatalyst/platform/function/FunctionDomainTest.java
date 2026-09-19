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
    private static final FunctionOwner CLIENT_1 = FunctionOwner.ofClientId("clt_1");
    private static final FunctionOwner OTHER_CLIENT = FunctionOwner.ofClientId("clt_other");
    private static final FunctionOwner PLATFORM = new FunctionOwner.Platform();

    @Test
    void claimStartsPending() {
        FunctionDomain d = FunctionDomain.claim(CLIENT_1, HOST, "token-abc", Instant.now());
        assertThat(d.id()).startsWith("fnd_");
        assertThat(d.verification()).isInstanceOf(FunctionDomain.Verification.Pending.class);
        assertThat(d.usableBy(CLIENT_1)).as("pending is not usable").isFalse();
    }

    @Test
    void verifiedMovesPendingToVerified() {
        FunctionDomain d = FunctionDomain.claim(CLIENT_1, HOST, "token-abc", Instant.now());
        Instant at = Instant.now().plusSeconds(5);
        FunctionDomain verified = d.verified(at);
        assertThat(verified.verification()).isEqualTo(new FunctionDomain.Verification.Verified(at));
        assertThat(verified.usableBy(CLIENT_1)).isTrue();
        assertThat(verified.usableBy(OTHER_CLIENT)).as("owned by clt_1, not another client").isFalse();
    }

    @Test
    void verifyingAnAlreadyVerifiedDomainConflicts() {
        FunctionDomain verified = FunctionDomain.claim(CLIENT_1, HOST, "token-abc", Instant.now()).verified(Instant.now());
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
        FunctionDomain d = FunctionDomain.claim(CLIENT_1, HOST, token, Instant.now());
        assertThat(d.toString()).doesNotContain(token);
    }

    // ── §8 M20: usableBy is owner-exact, Platform included ──────────────────

    @Test
    void aClientsVerifiedDomainIsNotUsableByThePlatformNorTheReverse() {
        FunctionDomain clientDomain = FunctionDomain.claim(CLIENT_1, HOST, "token-abc", Instant.now())
                .verified(Instant.now());
        assertThat(clientDomain.usableBy(CLIENT_1)).isTrue();
        assertThat(clientDomain.usableBy(PLATFORM)).as("a client's domain is not usable by the platform").isFalse();

        Hostname platformHost = Hostname.parse("platform.acme.com");
        FunctionDomain platformDomain = FunctionDomain.claim(PLATFORM, platformHost, "token-xyz", Instant.now())
                .verified(Instant.now());
        assertThat(platformDomain.usableBy(PLATFORM)).isTrue();
        assertThat(platformDomain.usableBy(CLIENT_1)).as("the platform's domain is not usable by a client").isFalse();
    }
}
