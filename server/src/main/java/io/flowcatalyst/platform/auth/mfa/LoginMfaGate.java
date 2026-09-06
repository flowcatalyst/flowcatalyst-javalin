package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.platform.auth.login.MfaChallenge;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.http.Exchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// The `/auth/login` decision after the password verifies
/// (`docs/spec/auth-identity.md` §6.2; Go `login/twofactor.go:76-145`):
///
/// | case | answer |
/// |---|---|
/// | federated identity | proceed |
/// | usable factors, remember allowed and the device cookie verifies | proceed (stamps `last_used_at`) |
/// | usable factors otherwise | `mfa_required` + pending token |
/// | none usable, domain does not require one | proceed |
/// | none usable, domain requires one | `enrollment_required` + enrol token |
///
/// `usable = confirmed ∩ allowed`, the intersection applied only when the
/// domain requires a factor. Any store or mint error propagates and the
/// caller fails closed (500, no cookie).
public final class LoginMfaGate implements MfaChallenge {

    private final Mfa mfa;
    private final DomainPolicy.Evaluator policy;
    private final MfaToken tokens;
    private final TrustedDeviceCookie deviceCookie;

    public LoginMfaGate(Mfa mfa, DomainPolicy.Evaluator policy, MfaToken tokens, TrustedDeviceCookie deviceCookie) {
        this.mfa = Objects.requireNonNull(mfa, "mfa");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.deviceCookie = Objects.requireNonNull(deviceCookie, "deviceCookie");
    }

    @Override
    public Optional<Challenge> evaluate(Principal p, Exchange ctx) {
        if (p.isFederated()) {
            return Optional.empty();
        }
        DomainPolicy dp = policy.evaluate(p.email());
        List<MfaMethod> usable = usable(mfa.confirmed(p.id()), dp);
        if (!usable.isEmpty()) {
            if (dp.rememberEnabled() && mfa.verifyTrustedDevice(p.id(), deviceCookie.read(ctx))) {
                return Optional.empty();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "mfa_required");
            body.put("mfaToken", tokens.mint(p.id(), MfaToken.Purpose.PENDING));
            body.put("methods", usable.stream().map(MfaMethod::name).toList());
            body.put("rememberDeviceAllowed", dp.rememberEnabled());
            return Optional.of(new Challenge(body));
        }
        if (!dp.requires2fa()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "enrollment_required");
        body.put("enrollToken", tokens.mint(p.id(), MfaToken.Purpose.ENROLL));
        body.put("allowedMethods", dp.permittedMethods().stream().map(MfaMethod::name).toList());
        return Optional.of(new Challenge(body));
    }

    /// Confirmed factors, narrowed to the domain's list only when the
    /// domain requires a factor.
    static List<MfaMethod> usable(List<MfaMethod> confirmed, DomainPolicy dp) {
        if (!dp.requires2fa() || dp.allowedMethods() == null || dp.allowedMethods().isEmpty()) {
            return confirmed;
        }
        var out = new ArrayList<MfaMethod>();
        for (MfaMethod m : confirmed) {
            if (dp.allowedMethods().contains(m)) {
                out.add(m);
            }
        }
        return out;
    }
}
