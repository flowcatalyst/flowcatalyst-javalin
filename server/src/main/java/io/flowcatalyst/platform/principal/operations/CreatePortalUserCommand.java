package io.flowcatalyst.platform.principal.operations;

/// The input DTO for [CreatePortalUser] (audit `operation` =
/// `CreatePortalUserCommand`). `provider` records which auth path minted the
/// identity (e.g. `OIDC`); portal identities never carry a password.
public record CreatePortalUserCommand(String email, String name, String provider) {
}
