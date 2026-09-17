package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [RecordOidcLogin] (spec
/// `docs/spec/oidc-logged-in-event.md`, which pins the operation name to
/// `OidcLogin`). Deliberately **without** the repo's usual `Command`
/// suffix: the audit `operation` column is the command's simple class
/// name (`SinkSupport.commandName`), so naming it plainly `OidcLogin`
/// makes that column read exactly what the spec calls the operation,
/// rather than `OidcLoginCommand`.
///
/// @param email               the login identifier (normalised email) the callback resolved
/// @param identityProviderId  the identity provider that authenticated this login
public record OidcLogin(String email, String identityProviderId) {
}
