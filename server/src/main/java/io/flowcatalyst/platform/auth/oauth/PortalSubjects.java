package io.flowcatalyst.platform.auth.oauth;

import java.util.Optional;

/// What the token endpoint needs from the portal plane to redeem a
/// `ptu_` authorization code (`docs/spec/auth-identity.md` §5.8), wired
/// by the portal unit; absent ⇒ portal codes are refused.
public interface PortalSubjects {

    record Subject(String id, String email, String name, boolean active) {
    }

    Optional<Subject> findSubject(String identityId);
}
