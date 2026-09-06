package io.flowcatalyst.platform.shared.apicommon;

import io.flowcatalyst.http.Exchange;

/// The bare `{id}` path input shared by get/update/delete handlers (Go
/// `apicommon.IDInput`).
public record IdInput(String id) {

    /// Reads the `{id}` path parameter of the matched route.
    public static IdInput from(Exchange ctx) {
        return new IdInput(ctx.pathParam("id"));
    }
}
