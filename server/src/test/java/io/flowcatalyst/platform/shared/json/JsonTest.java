package io.flowcatalyst.platform.shared.json;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    record Stamped(Instant at, OffsetDateTime when, String note, List<String> tags, Optional<String> maybe) {
    }

    enum Colour { RED, GREEN }

    record WithEnum(Colour colour) {
    }

    @Test
    void instantIsWrittenWithSixFractionalDigitsAndZ() {
        var at = Instant.parse("2024-03-05T07:08:09.123Z");
        assertThat(Json.write(new Stamped(at, null, null, null, null)))
                .isEqualTo("{\"at\":\"2024-03-05T07:08:09.123000Z\"}");
    }

    @Test
    void nanosAreTruncatedToMicros() {
        var at = Instant.parse("2024-03-05T07:08:09.123456789Z");
        assertThat(Json.write(new Stamped(at, null, null, null, null)))
                .isEqualTo("{\"at\":\"2024-03-05T07:08:09.123456Z\"}");
    }

    @Test
    void wholeSecondsStillCarrySixZeros() {
        var at = Instant.parse("2024-03-05T07:08:09Z");
        assertThat(Json.write(new Stamped(at, null, null, null, null)))
                .isEqualTo("{\"at\":\"2024-03-05T07:08:09.000000Z\"}");
    }

    @Test
    void offsetDateTimeKeepsItsOffsetLikeGoZ0700() {
        var when = OffsetDateTime.of(2024, 3, 5, 7, 8, 9, 500_000, ZoneOffset.ofHours(2));
        assertThat(Json.write(new Stamped(null, when, null, null, null)))
                .isEqualTo("{\"when\":\"2024-03-05T07:08:09.000500+02:00\"}");
        var utc = OffsetDateTime.of(2024, 3, 5, 7, 8, 9, 0, ZoneOffset.UTC);
        assertThat(Json.write(new Stamped(null, utc, null, null, null)))
                .isEqualTo("{\"when\":\"2024-03-05T07:08:09.000000Z\"}");
    }

    @Test
    void roundTripsAnyRfc3339Precision() throws Exception {
        var millis = Json.read("{\"at\":\"2024-03-05T07:08:09.123Z\"}", Stamped.class);
        assertThat(millis.at()).isEqualTo(Instant.parse("2024-03-05T07:08:09.123Z"));
        var nanos = Json.read("{\"at\":\"2024-03-05T07:08:09.123456789Z\"}", Stamped.class);
        assertThat(nanos.at()).isEqualTo(Instant.parse("2024-03-05T07:08:09.123456Z"));
        var offset = Json.read("{\"when\":\"2024-03-05T07:08:09+02:00\"}", Stamped.class);
        assertThat(offset.when()).isEqualTo(OffsetDateTime.of(2024, 3, 5, 7, 8, 9, 0, ZoneOffset.ofHours(2)));
        var nul = Json.read("{\"at\":null}", Stamped.class);
        assertThat(nul.at()).isNull();
        // canonical: write(read(x)) == x for a microsecond input
        var micro = "{\"at\":\"2024-03-05T07:08:09.123456Z\"}";
        assertThat(Json.write(Json.read(micro, Stamped.class))).isEqualTo(micro);
    }

    @Test
    void nullFieldsAreOmittedButEmptyValuesAreWritten() {
        assertThat(Json.write(new Stamped(null, null, "", List.of(), Optional.empty())))
                .isEqualTo("{\"note\":\"\",\"tags\":[]}");
    }

    @Test
    void unknownPropertiesAreIgnored() throws Exception {
        var s = Json.read("{\"note\":\"x\",\"bogus\":1,\"nested\":{\"a\":[1,2]}}", Stamped.class);
        assertThat(s.note()).isEqualTo("x");
    }

    @Test
    void enumsUseTheirWireName() throws Exception {
        assertThat(Json.write(new WithEnum(Colour.GREEN))).isEqualTo("{\"colour\":\"GREEN\"}");
        assertThat(Json.read("{\"colour\":\"RED\"}", WithEnum.class).colour()).isEqualTo(Colour.RED);
    }

    @Test
    void malformedTimestampIsAParseError() {
        assertThatThrownBy(() -> Json.read("{\"at\":\"yesterday\"}", Stamped.class))
                .hasMessageContaining("jsontime: parse \"yesterday\"");
    }

    @Test
    void writeLineAppendsNewlineLikeGoEncoder() {
        assertThat(Json.writeLine(new WithEnum(Colour.RED))).isEqualTo("{\"colour\":\"RED\"}\n");
    }
}
