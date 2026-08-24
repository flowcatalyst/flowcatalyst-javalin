package io.flowcatalyst.router.wire;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/// The mediation-response contract (`docs/spec/router.md` §6.6a).
///
/// The *order* of evaluation is the contract, so it is asserted directly
/// rather than inferred from the outcomes of unrelated cases.
class MediationResponseTest {

    @Test
    @DisplayName("an empty body is a plain success")
    void emptyBody() {
        assertThat(MediationResponse.resolve(200, new byte[0]))
                .isEqualTo(MediationOutcome.Success.of(200));
    }

    @ParameterizedTest(name = "a 2xx body that is not usable JSON is a plain success: {0}")
    @ValueSource(strings = {"not json at all", "<html>ok</html>", "{\"ack\":", "[]", "\"a string\"", "{}"})
    void unusableBodyIsSuccess(String body) {
        // A target that answers 2xx has accepted the message. A body we cannot
        // read is not a reason to redeliver something already accepted.
        assertThat(MediationResponse.resolve(200, body.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(MediationOutcome.Success.of(200));
    }

    @Test
    @DisplayName("ack:false defers, carrying the requested delay as the floor")
    void ackFalseDefers() {
        var outcome = MediationResponse.resolve(200, json("{\"ack\":false,\"delaySeconds\":45}"));

        assertThat(outcome).isInstanceOf(MediationOutcome.Deferred.class);
        var deferred = (MediationOutcome.Deferred) outcome;
        assertThat(deferred.delaySeconds()).isEqualTo(45);
        assertThat(deferred.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("ack:false with no delay defers with zero, not with a default")
    void ackFalseWithoutDelay() {
        var outcome = MediationResponse.resolve(202, json("{\"ack\":false}"));

        assertThat(outcome).isEqualTo(
                new MediationOutcome.Deferred(202, 0, "Target returned ack=false"));
    }

    @Test
    @DisplayName("ack:true alone is a plain success")
    void ackTrueIsSuccess() {
        assertThat(MediationResponse.resolve(200, json("{\"ack\":true}")))
                .isEqualTo(MediationOutcome.Success.of(200));
    }

    @Test
    @DisplayName("flushGroup:true succeeds and asks for the group to be suppressed")
    void flushGroup() {
        var outcome = MediationResponse.resolve(200, json("{\"ack\":true,\"flushGroup\":true,\"delaySeconds\":120}"));

        assertThat(outcome).isEqualTo(MediationOutcome.Success.flushing(200, 120));
    }

    @Test
    @DisplayName("flushGroup without a delay leaves the window for the registry to default")
    void flushGroupWithoutDelay() {
        // Zero here is not "no suppression" — GroupFlushRegistry reads it as
        // "use the default TTL". The clamping lives there, not in the parser,
        // so this must stay 0 rather than being defaulted twice.
        assertThat(MediationResponse.resolve(200, json("{\"flushGroup\":true}")))
                .isEqualTo(MediationOutcome.Success.flushing(200, 0));
    }

    @Test
    @DisplayName("ack:false beats flushGroup — a target cannot defer and discard at once")
    void ackFalseWinsOverFlushGroup() {
        // The precedence IS the contract: a target asking for the message back
        // must not also discard its siblings. Reordering the two checks would
        // silently ACK a group the target still wanted.
        var outcome = MediationResponse.resolve(200, json("{\"ack\":false,\"flushGroup\":true,\"delaySeconds\":30}"));

        assertThat(outcome).isEqualTo(
                new MediationOutcome.Deferred(200, 30, "Target returned ack=false"));
    }

    @Test
    @DisplayName("flushGroup:false is a plain success")
    void flushGroupFalse() {
        assertThat(MediationResponse.resolve(200, json("{\"flushGroup\":false}")))
                .isEqualTo(MediationOutcome.Success.of(200));
    }

    @Test
    @DisplayName("unknown fields are ignored, so the contract can grow")
    void unknownFieldsIgnored() {
        assertThat(MediationResponse.resolve(200, json("{\"ack\":false,\"somethingNew\":1}")))
                .isInstanceOf(MediationOutcome.Deferred.class);
    }

    @Test
    @DisplayName("a negative delay is treated as absent rather than inverting a backoff")
    void negativeDelayIsZero() {
        assertThat(MediationResponse.resolve(200, json("{\"ack\":false,\"delaySeconds\":-5}")))
                .isEqualTo(new MediationOutcome.Deferred(200, 0, "Target returned ack=false"));
    }

    @Test
    @DisplayName("the real status is carried, not flattened to 200")
    void carriesRealStatus() {
        // Deviation from Go, which hard-codes 200 on success (router.md Q51).
        // Internal only: it reaches logs and metrics, never a target.
        assertThat(MediationResponse.resolve(201, new byte[0]))
                .isEqualTo(MediationOutcome.Success.of(201));
        assertThat(MediationResponse.resolve(202, json("{\"flushGroup\":true}")))
                .isEqualTo(MediationOutcome.Success.flushing(202, 0));
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
