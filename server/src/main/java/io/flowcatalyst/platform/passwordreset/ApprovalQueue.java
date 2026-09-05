package io.flowcatalyst.platform.passwordreset;

import io.flowcatalyst.platform.principal.Principal;

/// The approval path a reset takes instead of a token when the platform
/// requires a strong factor and the user has none (§8.6). Ruling I-Q19
/// keeps `requireStrongFactorForReset = false`, so the queue stays idle;
/// the reset-approvals unit supplies the real one.
public interface ApprovalQueue {

    void queue(Principal principal);

    static ApprovalQueue none() {
        return _ -> { };
    }
}
