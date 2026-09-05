package io.flowcatalyst.platform.portalidentity.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentity;
import io.flowcatalyst.platform.portalidentity.PortalIdentityRepository;
import io.flowcatalyst.platform.portalidentity.PortalIdentitySource;
import io.flowcatalyst.platform.portalidentity.operations.PortalIdentityEvents.PortalIdentityEnsured;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.util.Optional;

/// Creates or re-activates a portal identity for (clientId, email) (spec
/// `auth-identity.md` §5.7, §11.8). `Authorize: Public` (the spec's own
/// words) — this operation is reached from several differently-gated entry
/// points (the admin API, and later the portal-SSO callback sink running as
/// the system actor), each of which gates itself before calling in.
public final class EnsurePortalIdentity {

    private EnsurePortalIdentity() {
    }

    public static Operation<EnsureCommand, PortalIdentityEnsured> of(PortalIdentityRepository repo, ClientRepository clients) {
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

                    String email = PortalIdentity.normalizeEmail(cmd.email());
                    PortalIdentitySource source = "JIT".equals(cmd.source()) ? PortalIdentitySource.JIT : PortalIdentitySource.INVITE;

                    Optional<PortalIdentity> existing = repo.findByClientAndEmail(cmd.clientId(), email);
                    boolean created = existing.isEmpty();
                    PortalIdentity identity = existing
                            .map(pi -> pi.ensureActive(cmd.name()))
                            .orElseGet(() -> PortalIdentity.create(cmd.clientId(), email, cmd.name(), source));

                    return Plan.save(identity, repo, PortalIdentityEnsured.of(ec, identity, created));
                });
    }

    /// `@` at an index > 0 and not last (spec §5.7).
    private static boolean isValidEmailShape(String email) {
        int at = email.indexOf('@');
        return at > 0 && at != email.length() - 1;
    }
}
