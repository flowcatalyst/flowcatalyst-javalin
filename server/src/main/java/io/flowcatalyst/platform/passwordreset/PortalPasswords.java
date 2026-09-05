package io.flowcatalyst.platform.passwordreset;

import java.util.Optional;

/// What the confirm flow needs from the portal plane (§8.5), wired by the
/// portal unit; until then no portal token can be confirmed.
public interface PortalPasswords {

    /// A portal identity as the confirm flow sees it.
    record Identity(String id, String email, String name, boolean active) {
    }

    Optional<Identity> find(String identityId);

    /// True when a row was updated.
    boolean setPasswordHash(String identityId, String hash);

    static PortalPasswords notWired() {
        return new PortalPasswords() {
            @Override
            public Optional<Identity> find(String identityId) {
                return Optional.empty();
            }

            @Override
            public boolean setPasswordHash(String identityId, String hash) {
                return false;
            }
        };
    }
}
