package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.DnsException;
import io.flowcatalyst.platform.function.FunctionDomain;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.TxtResolver;
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainVerified;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Resolves the TXT record `_flowcatalyst.<zone>` and, when a value equals
/// `fc-verify=<token>` EXACTLY, moves the domain to `Verified` (spec
/// `function-zones-and-aliases.md` §1, `function-public-routes.md` §1, §6
/// M1) — verified once for the whole zone. `cmd.hostname()` need not be the
/// zone apex: [Access#byHostname] resolves it through
/// [io.flowcatalyst.platform.function.FunctionDomainRepository#covering], so
/// verifying any hostname under the zone acts on the ONE covering claim; the
/// TXT lookup and the record shown on failure are always `d.hostname()` (the
/// zone), never the caller's raw input. A [TxOperation] (not the
/// single-event [Operation] this package's other by-hostname operations use)
/// so an ALREADY-verified domain can answer 200 with no event at all (spec
/// §1: "already verified ⇒ 200, no event") — [io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork#commit]
/// is only called on the branch that actually moves the domain, unlike
/// [Operation]'s [io.flowcatalyst.sdk.usecase.op.Plan], which always writes.
///
/// A [DnsException] from `resolver` is rethrown as [DnsUnavailableException]
/// — unchecked, so it crosses `Execute`'s functional interface — which
/// `FunctionDomainApi`'s verify handler catches specifically to answer
/// `503 DNS_UNAVAILABLE`. A resolver failure is NEVER treated as "no TXT
/// value matched" (spec §6 M1's own mutant: "treat failure as unverified").
public final class VerifyFunctionDomain {

    private static final int MAX_FOUND_VALUES = 5;
    private static final int MAX_VALUE_LENGTH = 100;

    private VerifyFunctionDomain() {
    }

    /// @param domain  the domain after this call — freshly verified, or
    ///                already-verified and untouched
    /// @param changed whether this call is the one that moved it to Verified
    ///                (false when it was already verified — spec §1: "200, no event")
    public record Result(FunctionDomain domain, boolean changed) {
        public Result {
            Objects.requireNonNull(domain, "domain");
        }
    }

    public static TxOperation<VerifyCommand, Result> of(FunctionDomainRepository domains, TxtResolver resolver) {
        Objects.requireNonNull(domains, "domains");
        Objects.requireNonNull(resolver, "resolver");
        return TxOperation.<VerifyCommand, Result>named("VerifyFunctionDomain")
                .authorize(Operation.Authorize.publicAccess()) // reach is Access.byHostname, below
                .execute((scoped, cmd, ec) -> {
                    Hostname hostname = Hostname.parse(cmd.hostname());
                    FunctionDomain d = Access.byHostname(domains, hostname, Auth.current());

                    if (d.verification() instanceof FunctionDomain.Verification.Verified) {
                        return new Result(d, false);
                    }

                    List<String> found;
                    try {
                        found = resolver.txt("_flowcatalyst." + d.hostname().value());
                    } catch (DnsException e) {
                        throw new DnsUnavailableException(e);
                    }

                    // spec §6 M1: `.equals`, never `contains`/`startsWith`/`endsWith` — a
                    // prefix, suffix, other record or case-changed value must NOT verify.
                    String expected = "fc-verify=" + d.verificationToken();
                    boolean matched = found.stream().anyMatch(value -> value.equals(expected));
                    if (!matched) {
                        List<String> shown = found.stream().limit(MAX_FOUND_VALUES).map(VerifyFunctionDomain::truncate).toList();
                        throw new UseCaseException(UseCaseError.conflict("DOMAIN_NOT_VERIFIED",
                                        "no TXT value at '_flowcatalyst." + d.hostname().value()
                                                + "' matched the verification token")
                                .withDetails(Map.of("found", shown)));
                    }

                    FunctionDomain verified = d.verified(Instant.now());
                    DomainVerified event = DomainVerified.of(ec, verified);
                    scoped.commit(verified, domains, event, cmd);
                    return new Result(verified, true);
                });
    }

    private static String truncate(String value) {
        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH);
    }
}
