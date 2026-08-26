package io.flowcatalyst.router.wire;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// The queue wire shape (`docs/spec/router.md` §2.1).
///
/// Go and Java never run at once, but a rollback leaves Java-written messages
/// on a queue for Go to drain, so these assertions are about **both**
/// directions staying readable — not about matching Go for its own sake.
@SuppressWarnings("deprecation")
class MessageWireTest {

    @Test
    @DisplayName("an unspecified dispatch mode is NEXT_ON_ERROR, not IMMEDIATE")
    void unspecifiedDispatchModeDefaultsToOrdered() {
        // The two failure modes are not symmetric. A producer that wanted
        // concurrency and got ordering sees lower throughput and fixes it. A
        // producer that needed ordering and silently got none sees nothing —
        // the damage lands in the target's data, not in the router, and is
        // found long afterwards if ever.
        assertThat(DispatchMode.parse(null)).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(DispatchMode.parse("")).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(DispatchMode.parse("  ")).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        // A typo is a producer bug and must not quietly turn ordering off,
        // which is precisely what the old IMMEDIATE default did.
        assertThat(DispatchMode.parse("NEXT-ON-ERROR")).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(DispatchMode.parse("immediate")).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        // Opting out stays explicit and exact.
        assertThat(DispatchMode.parse("IMMEDIATE")).isEqualTo(DispatchMode.IMMEDIATE);
    }

    @Test
    @DisplayName("the ordered default does not serialise messages that have no group")
    void orderedDefaultDoesNotSerialiseGrouplessTraffic() {
        // The risk of the new default: if a groupless message took the
        // ordered path, every message without a group would queue behind the
        // same empty key and the pool would run one at a time.
        var groupless = new Message("m1", "", null, null, MediationType.HTTP,
                "https://x.test/h", null, false, DispatchMode.parse(null));

        assertThat(groupless.dispatchMode()).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        assertThat(groupless.dispatchMode().requiresOrdering()).isTrue();
        assertThat(groupless.ordered())
                .as("no group means nothing to order within, so it dispatches concurrently")
                .isFalse();

        var grouped = new Message("m2", "", null, null, MediationType.HTTP,
                "https://x.test/h", "orders", false, DispatchMode.parse(null));
        assertThat(grouped.ordered()).isTrue();
    }

    @Test
    @DisplayName("a minimal message omits every unset optional rather than emitting null")
    void omitsUnsetOptionals() throws Exception {
        var json = Json.MAPPER.writeValueAsString(minimal());

        var node = Json.MAPPER.readTree(json);
        assertThat(node.has("authToken")).isFalse();
        assertThat(node.has("signingSecret")).isFalse();
        assertThat(node.has("messageGroupId")).isFalse();
        // Value-typed omitempty: dropped when zero, matching Go.
        assertThat(node.has("poolCode")).isFalse();
        assertThat(node.has("highPriority")).isFalse();
        // Always emitted.
        assertThat(node.get("id").asText()).isEqualTo("msg_1");
        assertThat(node.get("mediationType").asText()).isEqualTo("HTTP");
        assertThat(node.get("mediationTarget").asText()).isEqualTo("https://example.test/hook");
        assertThat(node.get("dispatchMode").asText()).isEqualTo("IMMEDIATE");
    }

    @Test
    @DisplayName("an empty authToken is emitted, because absent and empty mean different things")
    void emptyAuthTokenIsNotAbsent() throws Exception {
        // Go models this as *string: nil omits, but a pointer to "" is sent
        // and becomes `Authorization: Bearer `. Collapsing the two would
        // change which requests carry the header at all.
        var node = tree(with(m -> new Message(m.id(), m.poolCode(), "", m.signingSecret(),
                m.mediationType(), m.mediationTarget(), m.messageGroupId(), m.highPriority(), m.dispatchMode())));

        assertThat(node.has("authToken")).isTrue();
        assertThat(node.get("authToken").asText()).isEmpty();
    }

    @Test
    @DisplayName("a fully populated message round-trips unchanged")
    void roundTrip() throws Exception {
        var original = new Message("msg_2", "acme-FAST", "tok", "s3cret", MediationType.HTTP,
                "https://example.test/hook", "grp-1", true, DispatchMode.BLOCK_ON_ERROR);

        var restored = Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(original), Message.class);

        assertThat(restored).isEqualTo(original);
    }

    @ParameterizedTest(name = "dispatchMode {0} parses to {1}")
    @CsvSource({
            "IMMEDIATE,IMMEDIATE",
            "NEXT_ON_ERROR,NEXT_ON_ERROR",
            "BLOCK_ON_ERROR,BLOCK_ON_ERROR",
            "immediate,NEXT_ON_ERROR",
            "SOMETHING_NEW,NEXT_ON_ERROR",
            "'',NEXT_ON_ERROR",
    })
    void dispatchModeParsesLeniently(String wire, DispatchMode expected) {
        assertThat(DispatchMode.parse(wire)).isEqualTo(expected);
    }

    @Test
    @DisplayName("an absent dispatchMode reads as the default, so pre-propagation messages still route")
    void absentDispatchModeIsImmediate() throws Exception {
        // Messages published before the scheduler carried the field are still
        // on queues; they must route, not fail to deserialise. What they route
        // AS changed on 2026-08-25: the default is now NEXT_ON_ERROR, so an
        // old grouped message is ordered rather than silently unordered.
        var message = Json.MAPPER.readValue(
                "{\"id\":\"msg_3\",\"mediationType\":\"HTTP\",\"mediationTarget\":\"https://x.test/h\"}",
                Message.class);

        assertThat(message.dispatchMode()).isEqualTo(DispatchMode.NEXT_ON_ERROR);
        // Still concurrent, because this one carries no group.
        assertThat(message.ordered()).isFalse();
    }

    @ParameterizedTest(name = "requiresOrdering is the predicate the pool branches on: {0}")
    @CsvSource({"IMMEDIATE,false", "NEXT_ON_ERROR,true", "BLOCK_ON_ERROR,true"})
    void requiresOrdering(DispatchMode mode, boolean ordered) {
        // Asserting the predicate rather than the field: a regression that
        // populated dispatchMode with something parsing to IMMEDIATE would
        // pass a field check and still lose ordering.
        assertThat(mode.requiresOrdering()).isEqualTo(ordered);
    }

    @Test
    @DisplayName("an ordered mode without a group is not ordered")
    void orderedNeedsAGroup() {
        // The group is what ordering is *within*. BLOCK_ON_ERROR with no group
        // would otherwise serialise every ungrouped message in the pool
        // against one shared bucket (router.md §13 Q13).
        var grouped = ordered("g");
        var ungrouped = ordered(null);

        assertThat(grouped.ordered()).isTrue();
        assertThat(ungrouped.ordered()).isFalse();
        assertThat(ungrouped.groupId()).isEmpty();
    }

    @ParameterizedTest(name = "an unsupported mediationType survives parsing: {0}")
    @ValueSource(strings = {"GRPC", "kafka", ""})
    void unsupportedMediationTypeIsCarried(String raw) {
        // Modelled as a sealed type, not an enum, so the value reaches the
        // ACK-drop diagnostic instead of throwing and creating a poison
        // message that redelivers forever.
        assertThat(MediationType.parse(raw)).isEqualTo(new MediationType.Unsupported(raw));
    }

    @Test
    @DisplayName("HTTP parses to the shared instance and writes back as HTTP")
    void httpMediationType() throws Exception {
        assertThat(MediationType.parse("HTTP")).isEqualTo(MediationType.HTTP);
        assertThat(tree(minimal()).get("mediationType").asText()).isEqualTo("HTTP");
    }

    @Test
    @DisplayName("a null mediationType from the wire normalises to HTTP inside the JVM")
    void nullMediationTypeNormalises() {
        assertThat(new Message("msg_4", null, null, null, null, "https://x.test/h", null, false, null))
                .satisfies(m -> {
                    assertThat(m.mediationType()).isEqualTo(MediationType.HTTP);
                    assertThat(m.dispatchMode()).isEqualTo(DispatchMode.NEXT_ON_ERROR);
                });
    }

    private static JsonNode tree(Message m) throws Exception {
        return Json.MAPPER.readTree(Json.MAPPER.writeValueAsString(m));
    }

    /// A minimal message with one component replaced, so the tests do not
    /// need a `with*` API on the production record.
    private static Message with(java.util.function.UnaryOperator<Message> edit) {
        return edit.apply(minimal());
    }

    /// A BLOCK_ON_ERROR message in `group` (or ungrouped when null).
    private static Message ordered(String group) {
        return new Message("msg_1", "", null, null, MediationType.HTTP,
                "https://example.test/hook", group, false, DispatchMode.BLOCK_ON_ERROR);
    }

    private static Message minimal() {
        return new Message("msg_1", "", null, null, MediationType.HTTP,
                "https://example.test/hook", null, false, DispatchMode.IMMEDIATE);
    }
}
