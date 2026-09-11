package io.flowcatalyst.platform.auth.oauth;

import java.util.List;
import java.util.Optional;

/// What the token endpoint needs from the portal plane to redeem a
/// `ptu_` authorization code (`docs/spec/auth-identity.md` §5.8,
/// `docs/spec/portal-apps.md` §5.3), wired by the portal unit; absent ⇒
/// portal codes are refused.
public interface PortalSubjects {

    /// @param clientId the identity's tenant client id (`portal-apps.md` §5.3:
    ///                  the id_token's `portal_client_id`)
    /// @param appIds   the portal apps this identity is granted, by id
    ///                  (`portal-apps.md` §5.3's grant check)
    record Subject(String id, String email, String name, boolean active, String clientId, List<String> appIds) {
        public Subject {
            appIds = appIds == null ? List.of() : List.copyOf(appIds);
        }
    }

    Optional<Subject> findSubject(String identityId);
}
