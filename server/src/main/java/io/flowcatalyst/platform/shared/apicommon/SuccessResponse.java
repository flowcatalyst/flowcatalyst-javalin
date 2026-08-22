package io.flowcatalyst.platform.shared.apicommon;

import com.fasterxml.jackson.annotation.JsonInclude;

/// `{"success": true, "message"?: "..."}` — oauth-client activate/deactivate.
/// `message` is omitted when empty.
public record SuccessResponse(
        boolean success,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String message) {

    public static SuccessResponse ok() {
        return new SuccessResponse(true, null);
    }

    public static SuccessResponse ok(String message) {
        return new SuccessResponse(true, message);
    }
}
