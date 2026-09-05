package io.flowcatalyst.sdk.generated;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/// The two static helpers the generated models call from `toUrlQueryString`
/// — the only part of openapi-generator's `ApiClient` anything here needed.
/// The generator's full client is not generated any more: its template is
/// Jackson 2 by construction, and the SDK's client is
/// `io.flowcatalyst.sdk.http` on Jackson 3 (owner ruling 2026-09-06: no
/// Jackson 2 in the SDK). Same semantics as the generated helpers:
/// `urlEncode` is `URLEncoder` with `+` rendered as `%20`; `valueToString`
/// renders `null` as empty, an `OffsetDateTime` as RFC 3339, anything else
/// through `String.valueOf`.
public final class ApiClient {

    private ApiClient() {
    }

    public static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String valueToString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        return String.valueOf(value);
    }
}
