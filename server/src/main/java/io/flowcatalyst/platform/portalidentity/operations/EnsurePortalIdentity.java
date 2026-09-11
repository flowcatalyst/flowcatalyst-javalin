package io.flowcatalyst.platform.portalidentity.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalidentity.PortalAppGrantSource;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentitySource;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityEnsured;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.Optional;

/// Creates or re-activates a portal identity for (clientId, email) (spec
/// `auth-identity.md` §5.7, §11.8; `portal-apps.md` §3.1). `Authorize:
/// Public` (the spec's own words) — this operation is reached from several
/// differently-gated entry points (the admin API, and the portal-SSO
/// callback sink running as the system actor), each of which gates itself
/// before calling in.
public final class EnsurePortalIdentity {

    private EnsurePortalIdentity() {
    }

    public static Operation<EnsureCommand, PortalIdentityEnsured> of(
            PortalIdentityRepository repo, ClientRepository clients, PortalAppRepository apps) {
        return Operation.<EnsureCommand, PortalIdentityEnsured>named("EnsurePortalIdentity")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.clientId(), "CLIENT_ID_REQUIRED", "clientId is required");
                    UseCaseException.requireNonBlank(cmd.email(), "EMAIL_REQUIRED", "email is required");
                    if (!isValidEmailShape(cmd.email())) {
                        throw UseCaseException.validation("EMAIL_INVALID", "email is not valid");
                    }
                })
                .authorize(Operation.Authorize.publicAccess()) // reached from several gated entry points (spec §5.7)
                .execute((cmd, ec) -> {
                    clients.findById(cmd.clientId())
                            .orElseThrow(() -> UseCaseException.resourceNotFound("Client", cmd.clientId()));

                    PortalApp app = null;
                    if (cmd.portalAppId() != null && !cmd.portalAppId().isBlank()) {
                        app = apps.findById(cmd.portalAppId())
                                .filter(a -> a.clientId().equals(cmd.clientId()))
                                .orElseThrow(() -> UseCaseException.resourceNotFound("PortalApp", cmd.portalAppId()));
                        if (!app.active()) {
                            throw UseCaseException.validation("PORTAL_APP_INACTIVE",
                                    "portal app '" + app.code() + "' is inactive");
                        }
                    }

                    String email = PortalIdentity.normalizeEmail(cmd.email());
                    PortalIdentitySource source = "JIT".equals(cmd.source()) ? PortalIdentitySource.JIT : PortalIdentitySource.INVITE;

                    Optional<PortalIdentity> existing = repo.findByClientAndEmail(cmd.clientId(), email);
                    boolean created = existing.isEmpty();
                    PortalIdentity identity = existing
                            .map(pi -> pi.ensureActive(cmd.name()))
                            .orElseGet(() -> PortalIdentity.create(cmd.clientId(), email, cmd.name(), source));

                    if (app != null) {
                        PortalAppGrantSource grantSource = source == PortalIdentitySource.JIT
                                ? PortalAppGrantSource.JIT : PortalAppGrantSource.INVITE;
                        identity = identity.grant(app.id(), grantSource);
                    }

                    return Plan.save(identity, repo, PortalIdentityEnsured.of(ec, identity, created, app));
                });
    }

    /// `@` at an index > 0 and not last (spec §5.7).
    private static boolean isValidEmailShape(String email) {
        int at = email.indexOf('@');
        return at > 0 && at != email.length() - 1;
    }
}
