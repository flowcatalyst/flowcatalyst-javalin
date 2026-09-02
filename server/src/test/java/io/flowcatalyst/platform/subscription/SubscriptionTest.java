package io.flowcatalyst.platform.subscription;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–2): code and endpoint parsing, the
/// defaults, the pattern matching the fan-out relies on, the two idempotent
/// transitions and the lenient enum readers — no database involved.
class SubscriptionTest {

    // ── Code ───────────────────────────────────────────────────────────────

    @Test
    void codeIsTrimmedAndLowerCased() {
        assertThat(SubscriptionCode.parse("  Orders-Hook  ")).isEqualTo(new SubscriptionCode("orders-hook"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void codeRejectsBlank(String raw) {
        assertUseCaseError(() -> SubscriptionCode.parse(raw), UseCaseError.Validation.class, "CODE_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1orders", "orders_hook", "orders hook", "-orders", "orders.hook"})
    void codeRejectsTheWrongShape(String raw) {
        assertUseCaseError(() -> SubscriptionCode.parse(raw), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThatThrownBy(() -> SubscriptionCode.parse(raw)).hasMessageContaining(SubscriptionCode.FORMAT_MESSAGE);
    }

    // ── Endpoint ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"https://hooks.example.test/orders", "http://localhost:8080/x", "https://x"})
    void endpointAcceptsHttpAndHttpsUrls(String raw) {
        assertThat(EndpointUrl.parse(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ftp://files.example.test", "hooks.example.test/orders", "https://", "HTTP://x"})
    void endpointRejectsAnythingElse(String raw) {
        assertUseCaseError(() -> EndpointUrl.parse(raw), UseCaseError.Validation.class, "INVALID_ENDPOINT");
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsActiveUiSourcedPlatformWideWithEveryDefault() {
        var s = Subscription.create("orders-hook", "Orders", "https://hooks.example.test/orders");
        assertThat(s.id()).startsWith("sub_");
        assertThat(s.code()).isEqualTo("orders-hook");
        assertThat(s.status()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(s.source()).isEqualTo(SubscriptionSource.UI);
        assertThat(s.clientId()).isNull();
        assertThat(s.applicationCode()).isNull();
        assertThat(s.eventTypes()).isEmpty();
        assertThat(s.customConfig()).isEmpty();
        assertThat(s.maxAgeSeconds()).isEqualTo(Subscription.DEFAULT_MAX_AGE_SECONDS).isEqualTo(86_400);
        assertThat(s.delaySeconds()).isEqualTo(Subscription.DEFAULT_DELAY_SECONDS).isZero();
        assertThat(s.sequence()).isEqualTo(Subscription.DEFAULT_SEQUENCE).isEqualTo(99);
        assertThat(s.mode()).isEqualTo(DispatchMode.IMMEDIATE);
        assertThat(s.timeoutSeconds()).isEqualTo(Subscription.DEFAULT_TIMEOUT_SECONDS).isEqualTo(30);
        assertThat(s.maxRetries()).isEqualTo(Subscription.DEFAULT_MAX_RETRIES).isEqualTo(3);
        assertThat(s.dataOnly()).isEqualTo(Subscription.DEFAULT_DATA_ONLY).isTrue();
        assertThat(s.createdBy()).isNull();
        assertThat(s.clientScoped()).isFalse();
    }

    // ── Transitions ────────────────────────────────────────────────────────

    @Test
    void pauseAndResumeFlipTheStatusAndAreIdempotent() {
        var s = Subscription.create("orders-hook", "Orders", "https://hooks.example.test/orders");
        var paused = s.pause();
        assertThat(paused.isPaused()).isTrue();
        assertThat(paused.updatedAt()).isAfterOrEqualTo(s.updatedAt());
        assertThat(s.isActive()).as("records are immutable").isTrue();
        assertThat(paused.pause().isPaused()).as("pausing twice is not an error").isTrue();

        var resumed = paused.resume();
        assertThat(resumed.isActive()).isTrue();
        assertThat(resumed.resume().isActive()).as("resuming twice is not an error").isTrue();
    }

    @Test
    void copiesReplaceOneFieldAndKeepTheRest() {
        var s = Subscription.create("orders-hook", "Orders", "https://hooks.example.test/orders")
                .withEventTypes(List.of(EventTypeBinding.of("orders:order:created")))
                .withCustomConfig(List.of(new ConfigEntry("X-Env", "test")))
                .withDispatchPool("dpl_1", "pool-one")
                .withMode(DispatchMode.BLOCK_ON_ERROR)
                .withDataOnly(false);
        assertThat(s.eventTypes()).containsExactly(EventTypeBinding.of("orders:order:created"));
        assertThat(s.customConfig()).containsExactly(new ConfigEntry("X-Env", "test"));
        assertThat(s.dispatchPoolId()).isEqualTo("dpl_1");
        assertThat(s.dispatchPoolCode()).isEqualTo("pool-one");
        assertThat(s.mode()).isEqualTo(DispatchMode.BLOCK_ON_ERROR);
        assertThat(s.dataOnly()).isFalse();
        assertThat(s.withDispatchPoolId("dpl_2").dispatchPoolCode()).as("admin pool id does not touch the code").isEqualTo("pool-one");
        assertThat(s.withEventTypes(List.of()).eventTypes()).as("bindings may be emptied wholesale").isEmpty();
        assertThat(s.withConnectionId(null).connectionId()).isNull();
    }

    // ── Matching (spec §1) ─────────────────────────────────────────────────

    /// The fan-out contract (spec §1 "Matching"): equal segment count, `*` is
    /// one whole segment, everything else is a literal, case-sensitive match.
    @ParameterizedTest(name = "{0} vs {1} → {2}")
    @CsvSource({
            // equal segment count — literal and wildcard segments
            "orders:order:created, orders:order:created, true",
            "orders:order:*,       orders:order:created, true",
            "orders:*:created,     orders:order:created, true",
            "*:order:created,      orders:order:created, true",
            "*:*:*,                orders:order:created, true",
            "*,                    orders, true",
            // segment count differs — a trailing `*` never absorbs extra segments, nor does a missing one
            "orders:order:*,       orders:order:created:v1, false",
            "orders:*,             orders:order:created, false",
            "*,                    orders:order, false",
            "orders:order:created:*, orders:order:created, false",
            "orders:order,         orders:order:created, false",
            // mismatch — literals are exact, partial wildcards and `**` are literals, case matters
            "orders:order:created, orders:order:shipped, false",
            "orders:order:create*, orders:order:created, false",
            "orders:**,            orders:order, false",
            "orders:order:created, ORDERS:order:created, false",
            // empty segments count
            "orders::created,      orders::created, true",
            "orders:*:created,     orders::created, true",
            "orders::created,      orders:order:created, false"})
    void bindingMatchesWholeSegmentsOnly(String pattern, String code, boolean expected) {
        assertThat(EventTypeBinding.of(pattern).matches(code)).isEqualTo(expected);
    }

    @Test
    void bindingNeverMatchesANullCode() {
        assertThat(EventTypeBinding.of("*").matches(null)).isFalse();
        assertThat(EventTypeBinding.of("").matches(null)).isFalse();
    }

    @Test
    void subscriptionMatchesWhenAnyBindingMatches() {
        var s = Subscription.create("orders-hook", "Orders", "https://hooks.example.test/orders")
                .withEventTypes(List.of(EventTypeBinding.of("orders:order:created"), EventTypeBinding.of("billing:*:*")));
        assertThat(s.matchesEventType("orders:order:created")).isTrue();
        assertThat(s.matchesEventType("billing:invoice:sent")).isTrue();
        assertThat(s.matchesEventType("orders:order:shipped")).isFalse();
        assertThat(s.matchesEventType(null)).isFalse();
        assertThat(Subscription.create("x", "X", "https://x").matchesEventType("orders:order:created"))
                .as("no bindings match nothing").isFalse();
    }

    @Test
    void platformWideSubscriptionMatchesEveryClientAndClientBoundOnlyItsOwn() {
        var platform = Subscription.create("orders-hook", "Orders", "https://hooks.example.test/orders");
        assertThat(platform.matchesClient("cli_1")).isTrue();
        assertThat(platform.matchesClient(null)).as("client-less events too").isTrue();

        var bound = platform.withClientId("cli_1");
        assertThat(bound.matchesClient("cli_1")).isTrue();
        assertThat(bound.matchesClient("cli_2")).isFalse();
        assertThat(bound.matchesClient(null)).as("a client-bound subscription never matches a client-less event").isFalse();
    }

    // ── Lenient readers ────────────────────────────────────────────────────

    @Test
    void storedEnumValuesAreReadLenientlyWithDefaults() {
        assertThat(SubscriptionStatus.parse("PAUSED")).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(SubscriptionStatus.parse("paused")).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(SubscriptionStatus.parse(null)).isEqualTo(SubscriptionStatus.ACTIVE);

        assertThat(SubscriptionSource.parse("CODE")).isEqualTo(SubscriptionSource.CODE);
        assertThat(SubscriptionSource.parse("API")).isEqualTo(SubscriptionSource.API);
        assertThat(SubscriptionSource.parse("anything")).isEqualTo(SubscriptionSource.UI);
        assertThat(SubscriptionSource.API.isSyncManaged()).isTrue();
        assertThat(SubscriptionSource.CODE.isSyncManaged()).isTrue();
        assertThat(SubscriptionSource.UI.isSyncManaged()).isFalse();

        assertThat(DispatchMode.parse("NEXT_ON_ERROR")).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(DispatchMode.parse("BLOCK_ON_ERROR")).isEqualTo(DispatchMode.BLOCK_ON_ERROR);
        assertThat(DispatchMode.parse("IMMEDIATE")).isEqualTo(DispatchMode.IMMEDIATE);
        // unrecognised ("immediate" is lowercase, not the stored constant) and absent both fall
        // back to the ordering-safe default, never to IMMEDIATE (X-01/A-09; dispatch-seam spec §2).
        assertThat(DispatchMode.parse("immediate")).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(DispatchMode.parse(null)).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(DispatchMode.IMMEDIATE.requiresOrdering()).isFalse();
        assertThat(DispatchMode.BLOCK_ON_ERROR.requiresOrdering()).isTrue();
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
