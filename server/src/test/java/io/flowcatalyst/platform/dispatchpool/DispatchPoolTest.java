package io.flowcatalyst.platform.dispatchpool;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–2): code parsing and normalisation,
/// the create defaults, the three unconditional status flips and the lenient
/// status read — no database involved.
class DispatchPoolTest {

    // ── Code ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"a", "my-pool", "my_pool", "pool-1_x", "logistics_portal"})
    void codeAcceptsLowercaseAlphanumericHyphensAndUnderscores(String code) {
        assertThat(DispatchPoolCode.parse(code)).isEqualTo(new DispatchPoolCode(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1bad", "-bad", "_bad", "Bad", "bad code", "bad.code", "bad:code", " my-pool"})
    void codeRejectsAnythingElseWithOneMessage(String code) {
        assertUseCaseError(() -> DispatchPoolCode.parse(code), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThatThrownBy(() -> DispatchPoolCode.parse(code)).hasMessageContaining(DispatchPoolCode.FORMAT_MESSAGE);
    }

    @Test
    void codeParseIsStrictButNormalisedTrimsAndLowercases() {
        assertUseCaseError(() -> DispatchPoolCode.parse("  My-Pool "), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThat(DispatchPoolCode.normalised("  My-Pool ").value()).isEqualTo("my-pool");
        assertUseCaseError(() -> DispatchPoolCode.normalised("  "), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertUseCaseError(() -> DispatchPoolCode.parse(null), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsActivePlatformWideWithDefaultConcurrencyAndNoRateLimit() {
        var p = DispatchPool.create("router-pool", "Router Pool");
        assertThat(p.id()).startsWith("dpl_");
        assertThat(p.code()).isEqualTo("router-pool");
        assertThat(p.name()).isEqualTo("Router Pool");
        assertThat(p.status()).isEqualTo(DispatchPoolStatus.ACTIVE);
        assertThat(p.concurrency()).isEqualTo(DispatchPool.DEFAULT_CONCURRENCY).isEqualTo(10);
        assertThat(p.rateLimit()).as("no rate limiter by default").isNull();
        assertThat(p.clientId()).isNull();
        assertThat(p.clientIdentifier()).isNull();
        assertThat(p.description()).isNull();
        assertThat(p.createdAt()).isEqualTo(p.updatedAt());
    }

    @Test
    void createRejectsAMalformedCodeAndDoesNotNormalise() {
        assertUseCaseError(() -> DispatchPool.create("Bad", "x"), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
    }

    @Test
    void copiesReplaceOneSettingAndLeaveTheOriginalUntouched() {
        var p = DispatchPool.create("p", "Name");
        var changed = p.withName("New").withDescription("d").withRateLimit(60).withConcurrency(4).withClientId("cli_x");
        assertThat(changed.name()).isEqualTo("New");
        assertThat(changed.description()).isEqualTo("d");
        assertThat(changed.rateLimit()).isEqualTo(60);
        assertThat(changed.concurrency()).isEqualTo(4);
        assertThat(changed.clientId()).isEqualTo("cli_x");
        assertThat(changed.withRateLimit(null).rateLimit()).as("null clears the limiter").isNull();
        assertThat(p.name()).as("records are immutable").isEqualTo("Name");
        assertThat(p.concurrency()).isEqualTo(10);
    }

    // ── Transitions ────────────────────────────────────────────────────────

    @Test
    void suspendActivateAndArchiveFlipTheStatusFromAnyState() {
        var p = DispatchPool.create("p", "Name");
        var suspended = p.suspend();
        assertThat(suspended.status()).isEqualTo(DispatchPoolStatus.SUSPENDED);
        assertThat(suspended.updatedAt()).isAfterOrEqualTo(p.updatedAt());
        assertThat(p.status()).as("records are immutable").isEqualTo(DispatchPoolStatus.ACTIVE);

        assertThat(suspended.activate().status()).isEqualTo(DispatchPoolStatus.ACTIVE);
        var archived = suspended.archive();
        assertThat(archived.isArchived()).isTrue();

        // Unconditional flips (spec §2, open question 1): no conflict from any state.
        assertThat(archived.archive().status()).isEqualTo(DispatchPoolStatus.ARCHIVED);
        assertThat(archived.suspend().status()).isEqualTo(DispatchPoolStatus.SUSPENDED);
        assertThat(archived.activate().status()).isEqualTo(DispatchPoolStatus.ACTIVE);
    }

    // ── Lenient reader ─────────────────────────────────────────────────────

    @Test
    void storedStatusIsReadLenientlyWithActiveAsDefault() {
        assertThat(DispatchPoolStatus.parse("SUSPENDED")).isEqualTo(DispatchPoolStatus.SUSPENDED);
        assertThat(DispatchPoolStatus.parse("ARCHIVED")).isEqualTo(DispatchPoolStatus.ARCHIVED);
        assertThat(DispatchPoolStatus.parse("ACTIVE")).isEqualTo(DispatchPoolStatus.ACTIVE);
        assertThat(DispatchPoolStatus.parse("UNKNOWN")).isEqualTo(DispatchPoolStatus.ACTIVE);
        assertThat(DispatchPoolStatus.parse(null)).isEqualTo(DispatchPoolStatus.ACTIVE);
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }
}
