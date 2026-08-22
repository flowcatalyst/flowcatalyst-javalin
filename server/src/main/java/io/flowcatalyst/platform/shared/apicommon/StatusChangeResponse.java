package io.flowcatalyst.platform.shared.apicommon;

/// `{"message": "..."}` — lifecycle endpoints (deactivate, send-password-reset, …).
public record StatusChangeResponse(String message) {
}
