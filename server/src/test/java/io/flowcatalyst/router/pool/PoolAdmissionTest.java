package io.flowcatalyst.router.pool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/// The deferral reservation schedule (owner ruling 2026-09-22,
/// `docs/spec/router-hol-deferral.md` §3) — ported from Go's
/// `pool_admission_test.go`, mutation-checked there.
class PoolAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    // ── D1: consecutive slots ────────────────────────────────────────────

    @Test
    @DisplayName("D1: a pool at 0.2/s with 100 buffered reserves ~505s first, then ~5s apart "
            + "(mutant: drop nextReturn from the max)")
    void consecutiveSlotsAreOneRateDerivedSlotApart() {
        var admission = new PoolAdmission(null, FIXED);
        var rate = OptionalDouble.of(0.2); // 5s per message

        var first = admission.delay(100, rate, true);
        // wait = 100/0.2 = 500s, slot = 1/0.2 = 5s -> ~505s.
        assertThat(first.toSeconds()).isCloseTo(505, org.assertj.core.data.Offset.offset(2L));

        var prev = first;
        for (int i = 0; i < 5; i++) {
            var next = admission.delay(100, rate, true);
            assertThat(next.minus(prev).toSeconds())
                    .as("reservation %d must land one slot (5s) after the previous one", i + 2)
                    .isCloseTo(5, org.assertj.core.data.Offset.offset(1L));
            prev = next;
        }
        assertThat(admission.totalDeferred()).isEqualTo(6);
    }

    // ── D2: floor / fallback ─────────────────────────────────────────────

    @Test
    @DisplayName("D2: floored at 5s even with an empty buffer and a fast rate (mutant: the floor constant)")
    void floorsAtFiveSeconds() {
        var admission = new PoolAdmission(null, FIXED);
        var got = admission.delay(0, OptionalDouble.of(100.0), true);
        // Hardcoded, not PoolAdmission.MIN_DELAY: comparing the floor against
        // its own constant would still pass if the constant's VALUE were
        // wrong (e.g. 2s) — the spec pins 5 seconds specifically.
        assertThat(got).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("D2: with no completion in the window, falls back to 30s + 1s spacing "
            + "(mutant: the fallback/spacing constants)")
    void fallsBackWithoutARate() {
        var admission = new PoolAdmission(null, FIXED);
        var a = admission.delay(1, OptionalDouble.empty(), true);
        var b = admission.delay(1, OptionalDouble.empty(), true);

        // Hardcoded 30s/1s, not the PoolAdmission constants: pins the actual
        // spec values, not merely "whatever the constants happen to hold".
        assertThat(a).isEqualTo(Duration.ofSeconds(31));
        assertThat(b).isEqualTo(Duration.ofSeconds(32));
    }

    // ── D3: horizon clamp ────────────────────────────────────────────────

    @Test
    @DisplayName("D3: past the horizon, the reservation is clamped and jittered BACKWARD only, "
            + "never past the horizon and never below 75% of it (mutant: jitter forward, or over the whole horizon)")
    void clampsToHorizonWithBackwardJitter() {
        var horizon = Duration.ofMinutes(10);
        var admission = new PoolAdmission(horizon, FIXED);
        var rate = OptionalDouble.of(0.2); // 5s slots

        // Push the cursor well past the horizon.
        for (int i = 0; i < 200; i++) {
            admission.delay(100, rate, true);
        }

        for (int i = 0; i < 50; i++) {
            var d = admission.delay(100, rate, true);
            assertThat(d).as("never past the horizon").isLessThanOrEqualTo(horizon);
            assertThat(d.toNanos()).as("jitter only pulls a clamped reservation BACK, within the jitter fraction")
                    .isGreaterThanOrEqualTo((long) (horizon.toNanos() * 0.75));
        }
    }

    // ── D4: NATS (unordered-broker) spacing ─────────────────────────────

    @Test
    @DisplayName("D4: at 100/s an ordered broker's reservations sit on the 5s floor, an unordered one's "
            + "spread >= 1s apart (mutant: drop max(slot, 1s))")
    void spacesReservationsOnAnUnorderedBroker() {
        var ordered = new PoolAdmission(null, FIXED);
        var a = ordered.delay(0, OptionalDouble.of(100.0), true);
        var b = ordered.delay(0, OptionalDouble.of(100.0), true);
        assertThat(a).as("10ms slots both land on the 5s floor").isEqualTo(b).isEqualTo(PoolAdmission.MIN_DELAY);

        var unordered = new PoolAdmission(null, FIXED);
        var prev = unordered.delay(0, OptionalDouble.of(100.0), false);
        for (int i = 0; i < 10; i++) {
            var next = unordered.delay(0, OptionalDouble.of(100.0), false);
            if (next.compareTo(PoolAdmission.MIN_DELAY) > 0) { // once past the floor the spacing shows
                assertThat(next.minus(prev)).isGreaterThanOrEqualTo(PoolAdmission.ORDERED_SPACING);
            }
            prev = next;
        }
        assertThat(prev).as("eleven reservations on an unordered broker must have spread past the floor")
                .isGreaterThan(PoolAdmission.MIN_DELAY.plus(PoolAdmission.ORDERED_SPACING.multipliedBy(5)));
    }
}
