package io.flowcatalyst.function;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `Webhook.event`/`Webhook.schedule` against the exact wire shapes the
/// server actually sends (`docs/spec/function-invocation.md` §7):
///
/// - subscription/dispatch-job delivery —
///   `platform/dispatchjob/processing/DeliveryPayload.build`'s `Envelope`
///   record: `{id, type, attemptNumber, source?, subject?, correlationId?,
///   messageGroup?, clientId?, clientCode?, data?}`.
/// - scheduled-job firing —
///   `platform/scheduler/jobs/JobDispatcher.WebhookEnvelope`: `{jobId,
///   jobCode, instanceId, scheduledFor?, firedAt, triggerKind,
///   correlationId?, payload?, tracksCompletion, timeoutSeconds?, concurrent}`.
class WebhookTest {

    @Test
    void parsesAFullEventEnvelope() {
        String body = """
                {"id":"djb_123","type":"billing:invoices:invoice:created","attemptNumber":2,
                 "source":"billing-service","subject":"inv_1","correlationId":"corr-1",
                 "messageGroup":"group-1","clientId":"cli_1","clientCode":"acme",
                 "data":{"invoiceId":"inv_1","amount":100}}
                """;
        Event event = Webhook.event(request(body));

        assertThat(event.id()).isEqualTo("djb_123");
        assertThat(event.type()).isEqualTo("billing:invoices:invoice:created");
        assertThat(event.attemptNumber()).isEqualTo(2);
        assertThat(event.source()).isEqualTo("billing-service");
        assertThat(event.subject()).isEqualTo("inv_1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.messageGroup()).isEqualTo("group-1");
        assertThat(event.clientId()).isEqualTo("cli_1");
        assertThat(event.clientCode()).isEqualTo("acme");
        assertThat(event.dataJson()).isEqualTo("{\"invoiceId\":\"inv_1\",\"amount\":100}");
    }

    @Test
    void everyOptionalEventFieldMayBeAbsentEntirely() {
        // NON_ABSENT drops null fields — the key is missing, not present with a null value
        // (dispatch-seam spec §5, DeliveryPayload's Envelope over Json.MAPPER).
        String body = "{\"id\":\"djb_1\",\"type\":\"t\",\"attemptNumber\":1}";
        Event event = Webhook.event(request(body));

        assertThat(event.source()).isNull();
        assertThat(event.subject()).isNull();
        assertThat(event.correlationId()).isNull();
        assertThat(event.messageGroup()).isNull();
        assertThat(event.clientId()).isNull();
        assertThat(event.clientCode()).isNull();
        assertThat(event.dataJson()).isNull();
    }

    @Test
    void nonJsonPayloadDataPassesThroughAsALiteralJsonString() {
        // DeliveryPayload.dataValue: a non-JSON payload string is embedded as the literal
        // string value of `data`, not dropped.
        String body = "{\"id\":\"djb_1\",\"type\":\"t\",\"attemptNumber\":1,\"data\":\"not json {\"}";
        Event event = Webhook.event(request(body));
        assertThat(event.dataJson()).isEqualTo("\"not json {\"");
    }

    @Test
    void eventRequiresIdAndType() {
        assertThatThrownBy(() -> Webhook.event(request("{\"type\":\"t\",\"attemptNumber\":1}")))
                .isInstanceOf(WebhookFormatException.class);
        assertThatThrownBy(() -> Webhook.event(request("{\"id\":\"i\",\"attemptNumber\":1}")))
                .isInstanceOf(WebhookFormatException.class);
        assertThatThrownBy(() -> Webhook.event(request("{\"id\":\"i\",\"type\":\"t\"}")))
                .isInstanceOf(WebhookFormatException.class);
    }

    @Test
    void eventBodyThatIsNotAJsonObjectIsRejected() {
        // A dataOnly:true subscription sends the raw payload, not this envelope —
        // Webhook.event is only for dataOnly:false; a bare array/string/number is refused.
        assertThatThrownBy(() -> Webhook.event(request("[1,2,3]"))).isInstanceOf(WebhookFormatException.class);
        assertThatThrownBy(() -> Webhook.event(request("not json"))).isInstanceOf(WebhookFormatException.class);
        assertThatThrownBy(() -> Webhook.event(request(""))).isInstanceOf(WebhookFormatException.class);
    }

    @Test
    void parsesAFullScheduleEnvelope() {
        String body = """
                {"jobId":"sjb_1","jobCode":"nightly-report","instanceId":"sji_1",
                 "scheduledFor":"2026-09-19T00:00:00.000000Z","firedAt":"2026-09-19T00:00:01.500000Z",
                 "triggerKind":"CRON","correlationId":"corr-9","payload":{"n":1},
                 "tracksCompletion":true,"timeoutSeconds":30,"concurrent":false}
                """;
        Schedule schedule = Webhook.schedule(request(body));

        assertThat(schedule.jobId()).isEqualTo("sjb_1");
        assertThat(schedule.jobCode()).isEqualTo("nightly-report");
        assertThat(schedule.instanceId()).isEqualTo("sji_1");
        assertThat(schedule.scheduledFor()).isEqualTo(Instant.parse("2026-09-19T00:00:00.000000Z"));
        assertThat(schedule.firedAt()).isEqualTo(Instant.parse("2026-09-19T00:00:01.500000Z"));
        assertThat(schedule.triggerKind()).isEqualTo("CRON");
        assertThat(schedule.correlationId()).isEqualTo("corr-9");
        assertThat(schedule.payloadJson()).isEqualTo("{\"n\":1}");
        assertThat(schedule.tracksCompletion()).isTrue();
        assertThat(schedule.timeoutSeconds()).isEqualTo(30);
        assertThat(schedule.concurrent()).isFalse();
    }

    @Test
    void everyOptionalScheduleFieldMayBeAbsentEntirely() {
        String body = """
                {"jobId":"sjb_1","jobCode":"nightly-report","instanceId":"sji_1",
                 "firedAt":"2026-09-19T00:00:01Z","triggerKind":"MANUAL",
                 "tracksCompletion":false,"concurrent":true}
                """;
        Schedule schedule = Webhook.schedule(request(body));

        assertThat(schedule.scheduledFor()).isNull();
        assertThat(schedule.correlationId()).isNull();
        assertThat(schedule.payloadJson()).isNull();
        assertThat(schedule.timeoutSeconds()).isNull();
    }

    @Test
    void scheduleRequiresItsMandatoryFields() {
        assertThatThrownBy(() -> Webhook.schedule(request(
                "{\"jobCode\":\"c\",\"instanceId\":\"i\",\"firedAt\":\"2026-09-19T00:00:00Z\","
                        + "\"triggerKind\":\"CRON\",\"tracksCompletion\":true,\"concurrent\":false}")))
                .isInstanceOf(WebhookFormatException.class);
        assertThatThrownBy(() -> Webhook.schedule(request(
                "{\"jobId\":\"j\",\"jobCode\":\"c\",\"instanceId\":\"i\","
                        + "\"triggerKind\":\"CRON\",\"tracksCompletion\":true,\"concurrent\":false}")))
                .isInstanceOf(WebhookFormatException.class);
    }

    @Test
    void scheduleRejectsAMalformedTimestamp() {
        assertThatThrownBy(() -> Webhook.schedule(request(
                "{\"jobId\":\"j\",\"jobCode\":\"c\",\"instanceId\":\"i\",\"firedAt\":\"not-a-time\","
                        + "\"triggerKind\":\"CRON\",\"tracksCompletion\":true,\"concurrent\":false}")))
                .isInstanceOf(WebhookFormatException.class);
    }

    private static Request request(String body) {
        return new Request(FunctionAddress.parse("billing.invoices.api"), 1, "invocation-1", "POST",
                "/events/invoice-created", null, null, Map.of(), Map.of(), Map.of(),
                body.getBytes(StandardCharsets.UTF_8), "10.0.0.1", Caller.Platform.INSTANCE);
    }
}
