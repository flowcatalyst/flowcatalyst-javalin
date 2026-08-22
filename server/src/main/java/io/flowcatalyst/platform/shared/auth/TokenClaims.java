package io.flowcatalyst.platform.shared.auth;

import java.time.Instant;
import java.util.List;

/// The FlowCatalyst claim shape read off a verified JWT (Go
/// `sessiontoken.Claims` + the extra fields `authservice.AccessTokenClaims`
/// carries). Both the `fc_session` cookie (sessiontoken, identity only) and
/// the `Authorization: Bearer` access token (authservice, full authority)
/// decode to this record:
///
/// | claim              | field          | note                                         |
/// |--------------------|----------------|----------------------------------------------|
/// | `sub`              | subject        | required                                     |
/// | `type`             | principalType  | `USER` / `SERVICE`; absent on cookies          |
/// | `tier`             | tier           | `ANCHOR` / `PARTNER` / `CLIENT`                |
/// | `scope`            | permissions    | space-delimited permission codes             |
/// | `email`, `name`    | email, name    |                                              |
/// | `clients`, `roles`, `applications` | lists | arrays of strings                   |
/// | `all_applications` | allApplications| boolean                                      |
/// | `token_use`        | tokenUse       | `api` / `identity` / absent (legacy)         |
/// | `jti`              | jti            |                                              |
/// | `iat`              | issuedAt       |                                              |
///
/// Every optional string claim is `null` when absent (or not a string);
/// lists are empty, never `null`.
public record TokenClaims(
        String subject,
        String principalType,
        String tier,
        String email,
        String name,
        List<String> clients,
        List<String> roles,
        List<String> applications,
        boolean allApplications,
        List<String> permissions,
        String tokenUse,
        String jti,
        Instant issuedAt) {

    /// `token_use` value of an API-usable bearer.
    public static final String TOKEN_USE_API = "api";

    /// `token_use` value of an interactive-login token that carries no authority.
    public static final String TOKEN_USE_IDENTITY = "identity";

    public TokenClaims {
        clients = clients == null ? List.of() : List.copyOf(clients);
        roles = roles == null ? List.of() : List.copyOf(roles);
        applications = applications == null ? List.of() : List.copyOf(applications);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
