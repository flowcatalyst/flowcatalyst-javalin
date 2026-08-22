package io.flowcatalyst.platform.shared.apicommon;

/// `{"id": "..."}` — returned by POST endpoints that create a single entity.
public record CreatedResponse(String id) {
}
