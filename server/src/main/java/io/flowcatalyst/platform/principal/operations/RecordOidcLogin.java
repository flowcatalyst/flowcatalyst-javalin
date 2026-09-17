package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.operations.PrincipalEvents.FederatedClaims;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.FlowcatalystClaims;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserLoggedIn;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Records a successful OIDC login as an event-only plan (spec
/// `docs/spec/oidc-logged-in-event.md`): no aggregate change, just
/// [UserLoggedIn]. The claims are built by the caller (the OIDC bridge
/// callback) — they need the verified id_token, the unverified access-token
/// decode and the post-role-sync principal, none of which this operation
/// loads itself — and captured here so `execute` only assembles the event.
public final class RecordOidcLogin {

    private RecordOidcLogin() {
    }

    public static Operation<OidcLogin, UserLoggedIn> of(String userId, String identityProviderCode,
                                                         FlowcatalystClaims claims, FederatedClaims federated) {
        return Operation.<OidcLogin, UserLoggedIn>named("OidcLogin")
                // the login itself was already authenticated by the OIDC callback before this
                // operation runs; there is no further resource-level check to make here
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> Plan.emit(UserLoggedIn.of(ec, userId, cmd.email(), identityProviderCode, claims, federated)));
    }
}
