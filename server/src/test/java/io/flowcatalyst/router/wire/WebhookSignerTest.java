package io.flowcatalyst.router.wire;

import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// Conformance vector for the delivery signature.
///
/// These values are the wire contract with every FlowCatalyst receiver — the
/// SDK validators verify against exactly this formula. They are committed,
/// never regenerated (CONVENTIONS §6): if a change here is needed, every
/// deployed receiver is affected and that is the conversation to have first.
class WebhookSignerTest {

    /// `docs/spec/router.md` §6.4. Computed from the pinned formula and
    /// independently reproduced with `openssl dgst -sha256 -hmac`.
    private static final String SECRET = "test-secret-do-not-use-in-prod";
    private static final String TIMESTAMP = "2026-01-01T00:00:00.000Z";
    private static final String MESSAGE_ID = "msg_TEST123456";
    private static final String BODY = "{\"messageId\":\"msg_TEST123456\"}";
    private static final String SIGNATURE =
            "4c53b4f3224c8c870cd4f12e03a6903233153f26777708dd3afab915b2eb3819";

    @Test
    @DisplayName("the golden vector signs to the published signature")
    void goldenVector() {
        var signature = WebhookSigner.sign(SECRET, TIMESTAMP, BODY.getBytes(StandardCharsets.UTF_8));

        assertThat(signature).isEqualTo(SIGNATURE);
    }

    @Test
    @DisplayName("a message builds the exact body the golden vector signs")
    void bodyMatchesTheVector() {
        var body = message(MESSAGE_ID).deliveryBody();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(BODY);
        assertThat(body).hasSize(30);
        assertThat(WebhookSigner.sign(SECRET, TIMESTAMP, body)).isEqualTo(SIGNATURE);
    }

    @Test
    @DisplayName("the timestamp is 24 characters with exactly three fractional digits")
    void timestampFormat() {
        // Sub-millisecond precision must be truncated, not rounded or widened:
        // the receiver parses a fixed-width field.
        var stamped = WebhookSigner.timestamp(Instant.parse("2026-01-01T00:00:00.000123456Z"));

        assertThat(stamped).isEqualTo("2026-01-01T00:00:00.000Z").hasSize(24);
    }

    @Test
    @DisplayName("the signature covers the body, so a changed body changes it")
    void signatureCoversBody() {
        var one = WebhookSigner.sign(SECRET, TIMESTAMP, message("msg_A").deliveryBody());
        var two = WebhookSigner.sign(SECRET, TIMESTAMP, message("msg_B").deliveryBody());

        assertThat(one).isNotEqualTo(two);
    }

    @Test
    @DisplayName("the signature covers the timestamp, so a replay at another time fails")
    void signatureCoversTimestamp() {
        var body = message(MESSAGE_ID).deliveryBody();

        assertThat(WebhookSigner.sign(SECRET, TIMESTAMP, body))
                .isNotEqualTo(WebhookSigner.sign(SECRET, "2026-01-01T00:00:01.000Z", body));
    }

    @Test
    @DisplayName("Go's HTML escaping is reproduced in the signed bytes")
    void htmlEscapingMatchesGo() {
        // encoding/json escapes < > & even though JSON does not require it.
        // A receiver recomputes the HMAC over the bytes it received, so the
        // escaping must match or every such message fails verification.
        var body = new String(message("a<b>c&d").deliveryBody(), StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("{\"messageId\":\"a\\u003cb\\u003ec\\u0026d\"}");
    }

    @Test
    @DisplayName("quotes and backslashes in an id are escaped, not emitted raw")
    void controlCharactersEscaped() {
        var body = new String(message("a\"b\\c\nd").deliveryBody(), StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("{\"messageId\":\"a\\\"b\\\\c\\nd\"}");
    }

    private static Message message(String id) {
        return new Message(id, "", null, null, MediationType.HTTP,
                "https://example.test/hook", null, false, DispatchMode.IMMEDIATE);
    }
}
