package io.flowcatalyst.platform.shared.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.function.Function;

/// Reads any RFC 3339 timestamp (millisecond, microsecond or nanosecond
/// fraction; `Z` or numeric offset) and truncates it to microseconds — the
/// same leniency as Go's `time.Parse(time.RFC3339Nano, …)` followed by
/// `Truncate(time.Microsecond)`. JSON `null` becomes `null` (Go: zero time).
public final class MicroInstantDeserializer<T extends TemporalAccessor> extends JsonDeserializer<T> {

    private final Class<T> handled;
    private final Function<OffsetDateTime, T> convert;

    private MicroInstantDeserializer(Class<T> handled, Function<OffsetDateTime, T> convert) {
        this.handled = handled;
        this.convert = convert;
    }

    public static MicroInstantDeserializer<Instant> forInstant() {
        return new MicroInstantDeserializer<>(Instant.class, OffsetDateTime::toInstant);
    }

    public static MicroInstantDeserializer<OffsetDateTime> forOffsetDateTime() {
        return new MicroInstantDeserializer<>(OffsetDateTime.class, Function.identity());
    }

    public static MicroInstantDeserializer<ZonedDateTime> forZonedDateTime() {
        return new MicroInstantDeserializer<>(ZonedDateTime.class, OffsetDateTime::toZonedDateTime);
    }

    @Override
    public Class<T> handledType() {
        return handled;
    }

    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            return ctxt.readValue(p, handled); // let Jackson report the type mismatch its usual way
        }
        var text = p.getText().trim();
        try {
            var parsed = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .truncatedTo(ChronoUnit.MICROS);
            return convert.apply(parsed);
        } catch (DateTimeParseException e) {
            throw new InvalidFormatException(p, "jsontime: parse \"" + text + "\": " + e.getMessage(), text, handled);
        }
    }
}
