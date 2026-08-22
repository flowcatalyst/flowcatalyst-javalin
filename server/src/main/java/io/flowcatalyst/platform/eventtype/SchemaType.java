package io.flowcatalyst.platform.eventtype;

/// The payload language of a schema. Only `JSON_SCHEMA` is minted today; the
/// others are read from legacy rows. The constant name is the stored string.
public enum SchemaType {
    JSON_SCHEMA, XSD, PROTO;

    /// Lenient reader for stored values, with the legacy aliases
    /// `XML_SCHEMA` → `XSD` and `PROTOBUF` → `PROTO`; unknown → `JSON_SCHEMA`.
    public static SchemaType parse(String s) {
        return switch (s == null ? "" : s) {
            case "XSD", "XML_SCHEMA" -> XSD;
            case "PROTO", "PROTOBUF" -> PROTO;
            default -> JSON_SCHEMA;
        };
    }
}
