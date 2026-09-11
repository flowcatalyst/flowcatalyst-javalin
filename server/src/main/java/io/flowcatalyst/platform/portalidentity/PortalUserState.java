package io.flowcatalyst.platform.portalidentity;

/// The portal identity's derived, never-stored state (spec `portal-apps.md`
/// §2.3, Part A decision J3), computed by [PortalIdentity#state(Instant)] at
/// request time.
public enum PortalUserState {
    INVITED,
    INVITE_EXPIRED,
    ACTIVE,
    SUSPENDED
}
