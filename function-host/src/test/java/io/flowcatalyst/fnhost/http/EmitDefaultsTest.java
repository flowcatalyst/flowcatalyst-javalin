package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.function.Caller;
import io.flowcatalyst.function.FunctionAddress;
import io.flowcatalyst.function.Request;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec `function-context.md` §3 — what `events().emit` defaults to. One
/// test per rung of the precedence, and the cases where an inbound value
/// must NOT be used.
class EmitDefaultsTest {

    private static final FunctionAddress ADDRESS = FunctionAddress.parse("a.b.c");

    private static Request request(Caller caller, String body, Map<String, List<String>> headers) {
        return new Request(ADDRESS, 1, "inv-1", "POST", "/events/x", null, null, Map.of(), Map.of(), headers,
                body.getBytes(StandardCharsets.UTF_8), "127.0.0.1", caller);
    }

    private static String envelope(String correlationJson) {
        return "{\"id\":\"evt-9\",\"type\":\"a:b:c:d\",\"attemptNumber\":1" + correlationJson + ",\"data\":{}}";
    }

    @Test
    void theInboundEventsCorrelationIdWinsOverTheHeaderAndTheInvocationId() {
        var d = InvocationRunner.emitDefaults(request(Caller.Platform.INSTANCE,
                envelope(",\"correlationId\":\"flow-7\""), Map.of("X-Correlation-Id", List.of("hdr-1"))));

        assertThat(d.correlationId()).isEqualTo("flow-7");
        assertThat(d.causationId()).isEqualTo("evt-9");
    }

    @Test
    void withoutAnInboundCorrelationIdTheHeaderIsUsed() {
        var d = InvocationRunner.emitDefaults(request(Caller.Platform.INSTANCE, envelope(""),
                Map.of("X-Correlation-Id", List.of("hdr-1"))));

        assertThat(d.correlationId()).isEqualTo("hdr-1");
        assertThat(d.causationId()).isEqualTo("evt-9");
    }

    @Test
    void withNeitherTheInvocationIdIsUsed() {
        var d = InvocationRunner.emitDefaults(request(Caller.Platform.INSTANCE, envelope(""), Map.of()));

        assertThat(d.correlationId()).isEqualTo("inv-1");
    }

    /// An `auth: none` caller can put anything in the body: an envelope-shaped
    /// body from an unverified caller must not steer correlation or causation.
    @Test
    void anUnverifiedCallersEnvelopeShapedBodyOffersNeitherValue() {
        var d = InvocationRunner.emitDefaults(request(Caller.Anonymous.INSTANCE,
                envelope(",\"correlationId\":\"forged\""), Map.of()));

        assertThat(d.correlationId()).isEqualTo("inv-1");
        assertThat(d.causationId()).isNull();
    }

    @Test
    void aVerifiedDeliveryThatIsNotAnEventEnvelopeOffersNoCausation() {
        var d = InvocationRunner.emitDefaults(request(Caller.Platform.INSTANCE, "{\"jobId\":\"sjb_1\"}", Map.of()));

        assertThat(d.causationId()).isNull();
        assertThat(d.correlationId()).isEqualTo("inv-1");
    }
}
