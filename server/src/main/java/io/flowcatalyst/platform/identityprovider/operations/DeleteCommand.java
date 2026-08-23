package io.flowcatalyst.platform.identityprovider.operations;

/// The input DTO for [DeleteIdentityProvider] (audit `operation` = `DeleteCommand`).
///
/// @param id the provider
public record DeleteCommand(String id) {
}
