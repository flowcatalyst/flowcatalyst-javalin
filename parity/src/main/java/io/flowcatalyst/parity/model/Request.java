package io.flowcatalyst.parity.model;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/// A scenario step's request (spec §3). Exactly one of [#body] / [#form] is
/// set: `body` is sent as `application/json`, `form` as
/// `application/x-www-form-urlencoded` (the OAuth token endpoint).
///
/// @param method  HTTP method, upper-case
/// @param path    the request path; `${…}` substitution applies
/// @param query   query parameters; substitution applies to values
/// @param headers extra headers; substitution applies to values
/// @param auth    a bearer token — sugar for an `Authorization: Bearer …` header; substitution
///                applies
/// @param body    JSON body, or `null`
/// @param form    form body, or `null`
public record Request(String method, String path, Map<String, String> query, Map<String, String> headers,
                       String auth, JsonNode body, Map<String, String> form) {
    public Request {
        query = query == null ? Map.of() : Map.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        form = form == null ? Map.of() : Map.copyOf(form);
        if (body != null && !form.isEmpty()) {
            throw new IllegalArgumentException("a request may carry body or form, never both");
        }
    }
}
