package io.flowcatalyst.platform.subscription.operations;

import java.util.List;

/// One subscription definition in a [SyncSubscriptionsCommand] batch (spec §7,
/// `docs/spec/code-first-connections.md` §3).
///
/// @param code             stored as given — no trim, no lowercase, no pattern check (spec open question 3)
/// @param name             required, as given
/// @param description      optional
/// @param target           delivery endpoint; non-blank, format not checked (spec open question 3)
/// @param connectionId     optional; must exist when given (`CONNECTION_NOT_FOUND`); absent **clears** an existing link
/// @param connectionCode   optional; names a connection by its code — stable
///                         across environments, unlike `connectionId`. Resolved
///                         within an EXPLICIT namespace with no fallback
///                         (`code-first-connections.md` §3): by default a
///                         connection owned by THIS application;
///                         [#sharedConnection] switches to the shared
///                         (application-less) namespace. Within that
///                         namespace a client-scoped sync prefers its own
///                         client's connection, falling back to a global one;
///                         a client-less sync only resolves a global one. Not
///                         found → `404 CONNECTION_NOT_FOUND`. When both this
///                         and `connectionId` are given they must name the
///                         same connection (`400 CONNECTION_MISMATCH`).
/// @param sharedConnection resolves `connectionCode` among the shared
///                         (application-less) connections instead of this
///                         application's own; requires `connectionCode`
///                         (`400 SHARED_CONNECTION_REQUIRES_CODE` without one)
/// @param eventTypes       at least one binding; replaces the stored bindings
/// @param dispatchPoolCode optional; resolved among platform-wide pools, silently ignored when unknown (spec open question 6)
/// @param mode             accepted for wire compatibility and **ignored** (spec §7)
/// @param maxRetries       optional; replaced only when present
/// @param timeoutSeconds   optional; replaced only when present
/// @param dataOnly         plain boolean — absent on the wire means `false` (spec open question 4)
public record SyncSubscriptionInput(
        String code,
        String name,
        String description,
        String target,
        String connectionId,
        String connectionCode,
        boolean sharedConnection,
        List<SyncEventTypeBindingInput> eventTypes,
        String dispatchPoolCode,
        String mode,
        Integer maxRetries,
        Integer timeoutSeconds,
        boolean dataOnly) {

    public SyncSubscriptionInput {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }
}
