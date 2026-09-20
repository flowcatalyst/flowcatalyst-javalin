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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/// Claims a hostname for its owner (spec `function-public-routes.md` §1). A
/// hostname already claimed by anyone conflicts (`DOMAIN_TAKEN`) — the
/// message never names the current holder (spec §6 M1: "cannot be claimed by
/// another and the error never names the holder" — applies even when the
/// caller IS that holder, so there is no oracle telling a caller who holds a
/// hostname it does not itself already know it claimed).
///
/// `Authorize`: `Checks.checkScopeAccess` against the command's OWN
/// `owner` — same split as `CreateFunction` (spec §4.1's doc): nothing exists
/// yet to protect by hiding it as a 404, so a scope mismatch here is a
/// legitimate 403.
///
/// `devMode` is resolved ONCE by the composition root from `Env` and handed
/// in here — never read from the process environment inside the operation
/// (spec §1: "dev mode taken from Env and passed into the operation
/// factory"). When `true` AND the claimed hostname's last label is exactly
/// `localhost` (spec §1, §6 M3 — a LABEL match, not a string suffix: `x.localhost`
/// qualifies, `evil.localhost.example.com` does not, because ITS last label is
/// `com`), the domain is verified at claim time, no DNS.
public final class ClaimFunctionDomain {

    private ClaimFunctionDomain() {
    }

    public static Operation<ClaimCommand, DomainClaimed> of(FunctionDomainRepository domains, boolean devMode) {
        Objects.requireNonNull(domains, "domains");
        return Operation.<ClaimCommand, DomainClaimed>named("ClaimFunctionDomain")
                .validate(cmd -> Hostname.parse(cmd.hostname()))
                .authorize(cmd -> Checks.checkScopeAccess(Auth.current(), cmd.owner().clientIdOrNull()))
                .execute((cmd, ec) -> {
                    Hostname hostname = Hostname.parse(cmd.hostname());
                    if (domains.findByHostname(hostname).isPresent()) {
                        throw UseCaseException.conflict("DOMAIN_TAKEN", "hostname is already claimed");
                    }
                    String token = generateToken();
                    Instant now = Instant.now();
                    FunctionDomain claimed = FunctionDomain.claim(cmd.owner(), hostname, token, now);
                    if (devMode && isDevLocalhost(hostname)) {
                        claimed = claimed.verified(now);
                    }
                    DomainClaimed event = DomainClaimed.of(ec, claimed);
                    return Plan.save(claimed, domains, event);
                });
    }

    /// spec §1, §6 M3: the hostname's LAST LABEL is exactly `localhost` — a
    /// label-boundary comparison, not `String#endsWith("localhost")` (which
    /// would also match a hostname like `foo.notlocalhost`, whose last label
    /// is `notlocalhost`, not `localhost`). [Hostname#parse] already
    /// guarantees at least two labels, so `x.localhost` qualifies and a
    /// bare `localhost` can never reach here at all.
    private static boolean isDevLocalhost(Hostname hostname) {
        String value = hostname.value();
        int lastDot = value.lastIndexOf('.');
        String lastLabel = lastDot < 0 ? value : value.substring(lastDot + 1);
        return lastLabel.equals("localhost");
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
