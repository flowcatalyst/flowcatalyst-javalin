package io.flowcatalyst.platform.auth.grant;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [RefreshToken#issue] takes its TTL from the caller (`Env.refreshTokenTtlSeconds()`
/// through the composition root, owner ruling 2026-09-11 supersedes C-Q16,
/// `docs/spec/deployed-dispatch.md` §4) rather than reading a static default
/// itself — pinned here in isolation from the DB-backed grant store tests.
class RefreshTokenTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    /// A configured 30-day TTL (the deployed `OIDC_REFRESH_TOKEN_TTL`) must
    /// produce an expiry 30 days out — not the 7-day default. A mutant that
    /// ignores the `ttlSeconds` parameter and falls back to
    /// [RefreshToken#TTL_SECONDS] still leaves every field-shape assertion
    /// intact but fails the two assertions below.
    @Test
    void issueUsesTheGivenTtlNotTheSevenDayDefault() {
        long thirtyDays = 30L * 24 * 3600;
        var issued = RefreshToken.issue("prn_x", NOW, thirtyDays);

        assertThat(issued.token().expiresAt()).as("expires exactly TTL seconds after issuance")
                .isEqualTo(NOW.plusSeconds(thirtyDays));
        assertThat(issued.token().expiresAt()).as("must differ from the old hardcoded 7-day default")
                .isNotEqualTo(NOW.plusSeconds(RefreshToken.TTL_SECONDS));
    }

    /// The default constant itself still works as a TTL value — callers that
    /// pass it (tests, and any caller with no configured value) see the
    /// historical behaviour unchanged.
    @Test
    void issueWithTheDefaultConstantMatchesTheHistoricalSevenDays() {
        var issued = RefreshToken.issue("prn_y", NOW, RefreshToken.TTL_SECONDS);
        assertThat(issued.token().expiresAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 3600));
    }
}
