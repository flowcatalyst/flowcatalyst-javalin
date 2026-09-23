package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionDomain;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainClaimed;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;
import java.util.Objects;

/// Claims a ZONE for its owner (spec `function-zones-and-aliases.md` §1): `d`
/// covers `d` itself and every hostname whose labels end in `d`'s labels. A
/// one-label hostname is already refused by [Hostname#parse] itself
/// (`HOSTNAME_INVALID`, "at least two labels") before this operation's
/// `execute` ever runs — the exact same floor spec §1's `DOMAIN_INVALID`
/// describes for a claim ("claim a domain, not a top-level label"), so no
/// second, claim-specific check is added here: a one-label claim already
/// answers `400` (as `HOSTNAME_INVALID`, not a distinct `DOMAIN_INVALID`
/// code) via the one parser every hostname-accepting route shares.
///
/// No two claims may nest, by ANY owner — two independent `if`s, so a mutant
/// dropping either is caught by its own dedicated test rather than masked by
/// the other:
/// (a) an existing claim EQUALS `d` OR COVERS `d` ([FunctionDomainRepository#covering] —
///     its candidate list always includes `d` itself, so ONE query catches
///     both "`d` is already claimed exactly" and "an ancestor of `d` is
///     claimed"; a separate `findByHostname(d)` equality check would be
///     strictly redundant with this one, not an independent condition, so it
///     is not written — the round trip's "claiming the same hostname twice"
///     test already pins the equals case through this same check);
/// (b) an existing claim is COVERED BY `d` ([FunctionDomainRepository#anyUnder] —
///     a proper descendant of `d`, which `covering(d)` above can never see).
/// Every case throws the SAME `409 DOMAIN_TAKEN`, never naming the holder
/// (spec §6 M1: "cannot be claimed by another and the error never names the
/// holder" — applies even when the caller IS that holder, so there is no
/// oracle telling a caller who holds a hostname it does not itself already
/// know it claimed).
///
/// `Authorize`: `Checks.checkScopeAccess` against the command's OWN
/// `owner` — same split as `CreateFunction` (spec §4.1's doc): nothing exists
/// yet to protect by hiding it as a 404, so a scope mismatch here is a
/// legitimate 403.
///
/// Amended by `function-domains-no-dns.md`: a claim is verified by being made
/// — there is no DNS TXT record, no pending state, no dev-mode `.localhost`
/// special case. Every fresh claim is immediately usable by its owner.
public final class ClaimFunctionDomain {

    private ClaimFunctionDomain() {
    }

    public static Operation<ClaimCommand, DomainClaimed> of(FunctionDomainRepository domains) {
        Objects.requireNonNull(domains, "domains");
        return Operation.<ClaimCommand, DomainClaimed>named("ClaimFunctionDomain")
                .validate(cmd -> Hostname.parse(cmd.hostname()))
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.owner().clientIdOrNull()))
                .execute((cmd, ec) -> {
                    Hostname hostname = Hostname.parse(cmd.hostname());
                    if (domains.covering(hostname).isPresent()) {
                        throw domainTaken();
                    }
                    if (domains.anyUnder(hostname)) {
                        throw domainTaken();
                    }
                    FunctionDomain claimed = FunctionDomain.claim(cmd.owner(), hostname, Instant.now());
                    DomainClaimed event = DomainClaimed.of(ec, claimed);
                    return Plan.save(claimed, domains, event);
                });
    }

    private static UseCaseException domainTaken() {
        return UseCaseException.conflict("DOMAIN_TAKEN", "hostname is already claimed");
    }
}
