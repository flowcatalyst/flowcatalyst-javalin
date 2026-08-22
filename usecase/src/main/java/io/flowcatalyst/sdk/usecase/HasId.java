package io.flowcatalyst.sdk.usecase;

/// Implemented by every aggregate so the unit of work can name the row it is
/// committing without reflection. Records with an `id` component satisfy it
/// automatically:
///
/// ```java
/// public record EventType(String id, String code, ...) implements HasId {}
/// ```
public interface HasId {
    String id();
}
