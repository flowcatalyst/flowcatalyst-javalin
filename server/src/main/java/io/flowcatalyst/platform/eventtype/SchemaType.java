package io.flowcatalyst.platform.eventtype;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import tools.jackson.databind.JsonNode;

import java.util.Locale;

/// The payload language of a schema. The constant name is the stored string.
/// Add-schema mints any of them (bug 3, `docs/go-mirror/2026-10-10-platform-bug-fixes.md`);
/// an event type's initial version is always `JSON_SCHEMA`.
public enum SchemaType {
    JSON_SCHEMA, XSD, PROTO;

    /// The wire reader for an add-schema `schemaType`: `null` or blank is
    /// `JSON_SCHEMA`; case-insensitive; the legacy aliases `XML_SCHEMA` and
    /// `PROTOBUF` are accepted as on the stored side.
    ///
    /// @throws UseCaseException validation `INVALID_SCHEMA_TYPE`
    public static SchemaType parseWire(String s) {
        if (s == null || s.isBlank()) return JSON_SCHEMA;
        try {
            return parse(s.strip().toUpperCase(Locale.ROOT));
        } catch (UnrecognisedSchemaTypeException e) {
            throw UseCaseException.validation("INVALID_SCHEMA_TYPE", "schemaType must be JSON_SCHEMA, XSD or PROTO");
        }
    }

    /// The MIME type a version of this type gets when none is given.
    public String defaultMimeType() {
        return switch (this) {
            case JSON_SCHEMA -> SpecVersion.JSON_SCHEMA_MIME_TYPE;
            case XSD -> "application/xml";
            case PROTO -> "application/x-protobuf";
        };
    }

    /// The stored form of a schema of this type. A JSON Schema is the JSON
    /// document itself: a JSON string holding a document (an object or an
    /// array) is unwrapped, so a schema typed into a text field (the SPA's
    /// add-schema textarea) is not stored double-encoded; any other string is
    /// refused. XSD and PROTO schemas are text and must arrive as a JSON string.
    ///
    /// @throws UseCaseException validation `INVALID_SCHEMA`
    public JsonNode normaliseContent(JsonNode raw) {
        boolean isString = raw != null && raw.isString();
        if (this != JSON_SCHEMA) {
            if (!isString) {
                throw UseCaseException.validation("INVALID_SCHEMA", "an XSD or PROTO schema is text: send it as a JSON string");
            }
            return raw;
        }
        if (!isString) return raw;
        String inner = raw.asString().strip();
        if (inner.isEmpty() || (inner.charAt(0) != '{' && inner.charAt(0) != '[')) {
            throw UseCaseException.validation("INVALID_SCHEMA", "a JSON Schema must be a JSON document");
        }
        try {
            return Json.MAPPER.readTree(inner);
        } catch (RuntimeException e) {
            throw UseCaseException.validation("INVALID_SCHEMA", "a JSON Schema must be a JSON document");
        }
    }

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
