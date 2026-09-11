package io.flowcatalyst.platform.portalidentity;

import io.flowcatalyst.platform.auth.oauth.PortalSubjects;
import io.flowcatalyst.platform.passwordreset.PortalPasswords;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;

import java.util.Objects;
import java.util.Optional;

/// The portal plane as the password-reset confirm (§8.5) and the token
/// endpoint (§5.8) see it: a read of one identity, and the password write
/// a reset completes.
public final class PortalIdentityAccess implements PortalPasswords, PortalSubjects {

    private final PortalIdentityRepository repo;
    private final UnitOfWork uow;

    public PortalIdentityAccess(PortalIdentityRepository repo, UnitOfWork uow) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.uow = Objects.requireNonNull(uow, "uow");
    }

    @Override
    public Optional<PortalPasswords.Identity> find(String identityId) {
        return repo.findById(identityId).map(pi -> new PortalPasswords.Identity(pi.id(), pi.email(), pi.name(),
                pi.status() == PortalIdentityStatus.ACTIVE));
    }

    @Override
    public boolean setPasswordHash(String identityId, String hash) {
        if (repo.findById(identityId).isEmpty()) {
            return false;
        }
        uow.inTransaction(tx -> {
            repo.setPasswordHash(identityId, hash, tx.dbTx());
            return null;
        });
        return true;
    }

    @Override
    public Optional<PortalSubjects.Subject> findSubject(String identityId) {
        return repo.findById(identityId).map(pi -> new PortalSubjects.Subject(pi.id(), pi.email(), pi.name(),
                pi.status() == PortalIdentityStatus.ACTIVE, pi.clientId(),
                pi.apps().stream().map(PortalAppGrant::appId).toList(), pi.updatedAt()));
    }
}
