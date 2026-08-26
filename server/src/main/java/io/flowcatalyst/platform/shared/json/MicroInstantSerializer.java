package io.flowcatalyst.platform.shared.json;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;

/// Writes a timestamp in the platform's fixed wire format — the Go
/// `jsontime.Layout` `2006-01-02T15:04:05.000000Z07:00`: ISO-8601 with
/// **exactly six** fractional digits and `Z` for UTC (or `±HH:MM` for any
/// other offset). Values are truncated to microseconds first so the shape is
/// canonical regardless of the source precision.
///
/// Registered for [Instant], [OffsetDateTime] and [ZonedDateTime]; an
/// [Instant] is always UTC and therefore always ends in `Z`.
public final class MicroInstantSerializer<T extends TemporalAccessor> extends ValueSerializer<T> {

    /// `yyyy-MM-dd'T'HH:mm:ss.SSSSSS` followed by `Z` or `±HH:MM`.
    public static final DateTimeFormatter LAYOUT = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendFraction(ChronoField.MICRO_OF_SECOND, 6, 6, true)
            .appendOffset("+HH:MM", "Z")
            .toFormatter();

    private final Class<T> handled;

    public MicroInstantSerializer(Class<T> handled) {
        this.handled = handled;
    }

    @Override
    public Class<T> handledType() {
        return handled;
    }

    @Override
    public void serialize(T value, JsonGenerator gen, SerializationContext serializers) throws JacksonException {
        gen.writeString(format(value));
    }

    /// The wire string for any supported temporal. Exposed so log lines and
    /// tests can render exactly what the JSON carries.
    public static String format(TemporalAccessor value) {
        return switch (value) {
            case Instant i -> LAYOUT.format(i.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
            case OffsetDateTime o -> LAYOUT.format(o.truncatedTo(ChronoUnit.MICROS));
            case ZonedDateTime z -> LAYOUT.format(z.truncatedTo(ChronoUnit.MICROS).toOffsetDateTime());
            default -> throw new IllegalArgumentException("unsupported temporal: " + value.getClass().getName());
        };
    }
}
