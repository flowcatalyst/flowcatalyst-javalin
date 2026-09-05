package io.flowcatalyst.platform.passwordreset;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/auth-identity.md` §3.6: the raw link token and its hash, the
/// two purposes and their lifetimes.
class ResetTokenTest {

    @Test
    void theRawTokenIs43UrlSafeCharactersAndOnlyItsSha256IsKept() {
        var minted = ResetToken.mint("prn_1", ResetToken.Purpose.RESET, false, true, null, Instant.parse("2026-09-05T12:00:00Z"));
        assertThat(minted.raw()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(minted.token().tokenHash()).isEqualTo(ResetToken.hash(minted.raw())).matches("[0-9a-f]{64}");
        assertThat(minted.toString()).doesNotContain(minted.raw());
        assertThat(ResetToken.hash("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ResetToken.generateRaw()).isNotEqualTo(ResetToken.generateRaw());
    }

    @Test
    void resetLivesFifteenMinutesAndInviteSeventyTwoHours() {
        Instant now = Instant.parse("2026-09-05T12:00:00Z");
        var reset = ResetToken.mint("prn_1", ResetToken.Purpose.RESET, true, false, null, now).token();
        var invite = ResetToken.mint("ptu_1", ResetToken.Purpose.INVITE, false, false, "https://p.example/", now).token();
        assertThat(Duration.between(now, reset.expiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(Duration.between(now, invite.expiresAt())).isEqualTo(Duration.ofHours(72));
        assertThat(reset.isExpired(now.plus(Duration.ofMinutes(15)))).as("expiry is exclusive").isTrue();
        assertThat(reset.isExpired(now.plus(Duration.ofMinutes(15)).minusSeconds(1))).isFalse();
        assertThat(reset.portalSubject()).isFalse();
        assertThat(invite.portalSubject()).isTrue();
        assertThat(ResetToken.Purpose.parse("invite")).isEqualTo(ResetToken.Purpose.INVITE);
        assertThat(ResetToken.Purpose.parse("anything-else")).as("unknown reads as reset").isEqualTo(ResetToken.Purpose.RESET);
        assertThat(reset.id()).startsWith("prt_");
    }
}
