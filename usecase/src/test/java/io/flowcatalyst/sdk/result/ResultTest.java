package io.flowcatalyst.sdk.result;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultTest {

    sealed interface Lower {
        record Missing(String id) implements Lower {
        }
    }

    sealed interface Upper {
        record Because(Lower cause, String step) implements Upper {
        }
    }

    @Test
    void mapTransformsAValueAndLeavesAnErrorAloneWithoutCallingTheFunction() {
        assertThat(Result.<Integer, Lower>ok(2).map(v -> v * 10)).isEqualTo(Result.ok(20));

        var calls = new AtomicInteger();
        Result<Integer, Lower> failed = Result.err(new Lower.Missing("a"));
        assertThat(failed.map(v -> calls.incrementAndGet())).isEqualTo(Result.err(new Lower.Missing("a")));
        assertThat(calls).as("an error short-circuits").hasValue(0);
    }

    @Test
    void flatMapChainsAndTheFirstErrorWins() {
        Result<Integer, Lower> start = Result.ok(1);
        var calls = new AtomicInteger();
        var out = start
                .flatMap(v -> Result.<Integer, Lower>err(new Lower.Missing("first")))
                .flatMap(v -> {
                    calls.incrementAndGet();
                    return Result.err(new Lower.Missing("second"));
                });
        assertThat(out).isEqualTo(Result.err(new Lower.Missing("first")));
        assertThat(calls).hasValue(0);
        assertThat(start.flatMap(v -> Result.<String, Lower>ok("v" + v))).isEqualTo(Result.ok("v1"));
    }

    @Test
    void mapErrorWrapsTheLowerErrorSoItsContextReachesTheTop() {
        Result<String, Lower> lower = Result.err(new Lower.Missing("prn_1"));
        Result<String, Upper> upper = lower.mapError(e -> new Upper.Because(e, "resolve"));

        switch (upper) {
            case Result.Ok<String, Upper> ok -> throw new AssertionError("expected an error, got " + ok);
            case Result.Err<String, Upper>(Upper.Because(Lower.Missing(String id), String step)) -> {
                assertThat(id).isEqualTo("prn_1");
                assertThat(step).isEqualTo("resolve");
            }
        }
        assertThat(Result.<String, Lower>ok("v").mapError(e -> new Upper.Because(e, "x"))).isEqualTo(Result.ok("v"));
    }

    @Test
    void orElseThrowAnswersTheValueOrTheExceptionBuiltFromTheError() {
        assertThat(Result.<String, Lower>ok("v").orElseThrow(e -> new IllegalStateException())).isEqualTo("v");
        assertThatThrownBy(() -> Result.<String, Lower>err(new Lower.Missing("x"))
                .orElseThrow(e -> new IllegalStateException("missing " + ((Lower.Missing) e).id())))
                .isInstanceOf(IllegalStateException.class).hasMessage("missing x");
    }

    @Test
    void neitherArmHoldsNull() {
        assertThatThrownBy(() -> Result.ok(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Result.err(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Result.<Integer, Lower>ok(1).flatMap(v -> null)).isInstanceOf(NullPointerException.class);
    }
}
