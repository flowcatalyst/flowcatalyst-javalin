package io.flowcatalyst.platform.eventtype;

/// The payload language of a schema. Only `JSON_SCHEMA` is minted today; the
/// others are read from legacy rows. The constant name is the stored string.
public enum SchemaType {
    JSON_SCHEMA, XSD, PROTO;

    /// Strict reader for stored values (X-06), with the legacy aliases
    /// `XML_SCHEMA` → `XSD` and `PROTOBUF` → `PROTO` accepted deliberately —
    /// real values the column has held, not wire input — but any other
    /// value is rejected. See [EventTypeRepository]'s row mapper, which
    /// wraps [UnrecognisedSchemaTypeException] in
    /// [CorruptEventTypeException] carrying the row id.
    ///
    /// @throws UnrecognisedSchemaTypeException `s` is `null` or not one of
    ///                                         the recognised/legacy values
    public static SchemaType parse(String s) {
        return switch (s) {
            case "JSON_SCHEMA" -> JSON_SCHEMA;
            case "XSD", "XML_SCHEMA" -> XSD;
            case "PROTO", "PROTOBUF" -> PROTO;
            case null, default -> throw new UnrecognisedSchemaTypeException(s);
        };
    }

    /// Thrown by [#parse] for a stored value outside the recognised/legacy
    /// set — X-06: never a silent default.
    public static final class UnrecognisedSchemaTypeException extends RuntimeException {
        public UnrecognisedSchemaTypeException(String raw) {
            super("unrecognised schema type: " + raw);
        }
    }
}
