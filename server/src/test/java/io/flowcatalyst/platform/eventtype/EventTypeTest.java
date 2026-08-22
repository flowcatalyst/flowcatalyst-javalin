package io.flowcatalyst.platform.eventtype;

import com.fasterxml.jackson.databind.JsonNode;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/// The aggregate's pure rules (spec §1–2): code parsing, lenient enum reads,
/// and every state transition with its error code — no database involved.
class EventTypeTest {

    private static final JsonNode SCHEMA = Json.MAPPER.createObjectNode().put("type", "object");

    // ── Code ───────────────────────────────────────────────────────────────

    @Test
    void codeParsesIntoFourSegments() {
        var code = EventTypeCode.parse("orders:fulfillment:shipment:shipped");
        assertThat(code).isEqualTo(new EventTypeCode("orders", "fulfillment", "shipment", "shipped"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders:fulfillment:shipment", "orders:fulfillment", "orders", "",
            "orders:fulfillment:shipment:shipped:extra"})
    void codeRejectsWrongSegmentCounts(String code) {
        assertUseCaseError(() -> EventTypeCode.parse(code), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThatThrownBy(() -> EventTypeCode.parse(code)).hasMessageContaining(EventTypeCode.FORMAT_MESSAGE);
    }

    @Test
    void codeRejectsBlankSegmentsNamingTheSegment() {
        assertThatThrownBy(() -> EventTypeCode.parse("orders: :shipment:shipped"))
                .isInstanceOf(UseCaseException.class)
                .hasMessageContaining("Event type code part 'subdomain' cannot be empty");
        assertThatThrownBy(() -> EventTypeCode.parse("orders:fulfillment:shipment:"))
                .hasMessageContaining("Event type code part 'event' cannot be empty");
        assertThat(EventTypeCode.eventNameOf("not-a-code")).as("stored legacy codes never throw").isEmpty();
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsCurrentUiSourcedAndDenormalisesTheCode() {
        var et = EventType.create("orders:fulfillment:shipment:shipped", "Shipment Shipped");
        assertThat(et.id()).startsWith("evt_");
        assertThat(et.code()).isEqualTo("orders:fulfillment:shipment:shipped");
        assertThat(et.application()).isEqualTo("orders");
        assertThat(et.subdomain()).isEqualTo("fulfillment");
        assertThat(et.aggregate()).isEqualTo("shipment");
        assertThat(et.eventName()).isEqualTo("shipped");
        assertThat(et.status()).isEqualTo(EventTypeStatus.CURRENT);
        assertThat(et.source()).isEqualTo(EventTypeSource.UI);
        assertThat(et.clientScoped()).isFalse();
        assertThat(et.specVersions()).isEmpty();
        assertThat(et.createdBy()).isNull();
    }

    @Test
    void createRejectsAMalformedCode() {
        assertUseCaseError(() -> EventType.create("a:b:c", "x"), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
    }

    // ── Archive ────────────────────────────────────────────────────────────

    @Test
    void archiveIsOneWayAndLeavesTheOriginalUntouched() {
        var et = EventType.create("a:b:c:d", "Name");
        var archived = et.archive();
        assertThat(archived.isArchived()).isTrue();
        assertThat(archived.updatedAt()).isAfterOrEqualTo(et.updatedAt());
        assertThat(et.status()).as("records are immutable").isEqualTo(EventTypeStatus.CURRENT);
        assertUseCaseError(archived::archive, UseCaseError.Conflict.class, "ALREADY_ARCHIVED");
    }

    // ── Schema versions ────────────────────────────────────────────────────

    @Test
    void addSchemaVersionAppendsAFinalisingVersionOncePerVersionString() {
        var et = EventType.create("a:b:c:d", "Name").addSchemaVersion("1.0", SCHEMA);
        assertThat(et.specVersions()).extracting(SpecVersion::version).containsExactly("1.0");
        assertThat(et.specVersion("1.0")).get().satisfies(sv -> {
            assertThat(sv.status()).isEqualTo(SpecVersionStatus.FINALISING);
            assertThat(sv.schemaContent()).isEqualTo(SCHEMA);
            assertThat(sv.mimeType()).isEqualTo(SpecVersion.JSON_SCHEMA_MIME_TYPE);
            assertThat(sv.id()).startsWith("sch_");
        });
        assertUseCaseError(() -> et.addSchemaVersion("1.0", SCHEMA), UseCaseError.Conflict.class, "VERSION_EXISTS");
        assertThat(et.addSchemaVersion("2.0", null).specVersions()).hasSize(2);
    }

    @Test
    void finaliseMovesFinalisingToCurrentAndDeprecatesTheSameMajorSibling() {
        var et = EventType.create("a:b:c:d", "Name").addSchemaVersion("1.0", SCHEMA);

        var first = et.finaliseSchema("1.0");
        assertThat(first.deprecatedVersion()).as("no CURRENT sibling yet").isNull();
        assertThat(first.eventType().specVersion("1.0")).get().extracting(SpecVersion::status).isEqualTo(SpecVersionStatus.CURRENT);
        assertUseCaseError(() -> first.eventType().finaliseSchema("1.0"), UseCaseError.Conflict.class, "NOT_FINALISING");

        var withMore = first.eventType().addSchemaVersion("1.1", SCHEMA).addSchemaVersion("2.0", SCHEMA);
        var second = withMore.finaliseSchema("1.1");
        assertThat(second.deprecatedVersion()).isEqualTo("1.0");
        assertThat(second.eventType().specVersions()).extracting(SpecVersion::version, SpecVersion::status)
                .containsExactly(
                        tuple("1.0", SpecVersionStatus.DEPRECATED),
                        tuple("1.1", SpecVersionStatus.CURRENT),
                        tuple("2.0", SpecVersionStatus.FINALISING));

        var third = second.eventType().finaliseSchema("2.0");
        assertThat(third.deprecatedVersion()).as("other majors are untouched").isNull();
        assertUseCaseError(() -> third.eventType().finaliseSchema("9.9"), UseCaseError.NotFound.class, "SpecVersion_NOT_FOUND");
    }

    @Test
    void deprecateOnlyAcceptsACurrentVersion() {
        var finalising = EventType.create("a:b:c:d", "Name").addSchemaVersion("1.0", SCHEMA);
        assertUseCaseError(() -> finalising.deprecateSchema("1.0"), UseCaseError.Conflict.class, "STILL_FINALISING");
        assertUseCaseError(() -> finalising.deprecateSchema("9.9"), UseCaseError.NotFound.class, "SpecVersion_NOT_FOUND");

        var deprecated = finalising.finaliseSchema("1.0").eventType().deprecateSchema("1.0");
        assertThat(deprecated.specVersion("1.0")).get().extracting(SpecVersion::isDeprecated).isEqualTo(true);
        assertUseCaseError(() -> deprecated.deprecateSchema("1.0"), UseCaseError.Conflict.class, "ALREADY_DEPRECATED");
    }

    @Test
    void specVersionMajorIsTheTextBeforeTheFirstSeparator() {
        assertThat(SpecVersion.initial("evt_x", "2.3.4-alpha", null).major()).isEqualTo("2");
        assertThat(SpecVersion.initial("evt_x", "7", null).major()).isEqualTo("7");
        assertThat(SpecVersion.initial("evt_x", "1+build", null).major()).isEqualTo("1");
    }

    // ── Lenient readers ────────────────────────────────────────────────────

    @Test
    void storedEnumValuesAreReadLenientlyWithDefaults() {
        assertThat(EventTypeStatus.parse("ARCHIVED")).isEqualTo(EventTypeStatus.ARCHIVED);
        assertThat(EventTypeStatus.parse("UNKNOWN")).isEqualTo(EventTypeStatus.CURRENT);
        assertThat(EventTypeStatus.parse(null)).isEqualTo(EventTypeStatus.CURRENT);

        assertThat(EventTypeSource.parse("CODE")).isEqualTo(EventTypeSource.CODE);
        assertThat(EventTypeSource.parse("API")).isEqualTo(EventTypeSource.API);
        assertThat(EventTypeSource.parse("UNKNOWN")).isEqualTo(EventTypeSource.UI);

        assertThat(SpecVersionStatus.parse("CURRENT")).isEqualTo(SpecVersionStatus.CURRENT);
        assertThat(SpecVersionStatus.parse("DEPRECATED")).isEqualTo(SpecVersionStatus.DEPRECATED);
        assertThat(SpecVersionStatus.parse("anything")).isEqualTo(SpecVersionStatus.FINALISING);

        assertThat(SchemaType.parse("XSD")).isEqualTo(SchemaType.XSD);
        assertThat(SchemaType.parse("XML_SCHEMA")).isEqualTo(SchemaType.XSD);
        assertThat(SchemaType.parse("PROTO")).isEqualTo(SchemaType.PROTO);
        assertThat(SchemaType.parse("PROTOBUF")).isEqualTo(SchemaType.PROTO);
        assertThat(SchemaType.parse("UNKNOWN")).isEqualTo(SchemaType.JSON_SCHEMA);
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
