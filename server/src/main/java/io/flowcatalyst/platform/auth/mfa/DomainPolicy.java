package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.TwoFactorPolicy;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/// The domain's two-factor policy as the login decision reads it
/// (`docs/spec/auth-identity.md` §6.1, Go `twofa.Policy.Evaluate`), with
/// ruling I-Q11 applied: *every* derived flag — required, allowed methods,
/// remember-device — carries the `internal` term, so an external-IdP domain
/// can never require a factor here nor hand out a trusted-device cookie.
///
/// @param internal       the identity is managed here (no mapping, or a non-OIDC provider)
/// @param mapped         a mapping row exists for the domain
/// @param requires2fa    the domain requires a second factor (internal only)
/// @param allowedMethods the domain's list; `null` when unmapped (= no restriction)
/// @param rememberEnabled remember-device permitted (internal only, explicit policy)
/// @param rememberDays   the trusted-device lifetime, `≤ 0 → 30`
public record DomainPolicy(boolean internal, boolean mapped, boolean requires2fa, List<MfaMethod> allowedMethods,
                           boolean rememberEnabled, int rememberDays) {

    public static final DomainPolicy INTERNAL_UNMAPPED = new DomainPolicy(true, false, false, null, false,
            TwoFactorPolicy.DEFAULT_REMEMBER_DEVICE_DAYS);

    /// The methods a user may enrol or verify with: the domain list when it
    /// requires a factor, else both.
    public List<MfaMethod> permittedMethods() {
        if (requires2fa && allowedMethods != null && !allowedMethods.isEmpty()) {
            return allowedMethods;
        }
        return List.of(MfaMethod.TOTP, MfaMethod.EMAIL_PIN);
    }

    public boolean permits(MfaMethod m) {
        return permittedMethods().contains(m);
    }

    /// The store-backed evaluation.
    public static final class Evaluator {
        private final EmailDomainMappingRepository mappings;

        public Evaluator(EmailDomainMappingRepository mappings) {
            this.mappings = Objects.requireNonNull(mappings, "mappings");
        }

        public DomainPolicy evaluate(String email) {
            if (email == null) {
                return INTERNAL_UNMAPPED;
            }
            int at = email.lastIndexOf('@');
            if (at < 0 || at == email.length() - 1) {
                return INTERNAL_UNMAPPED;
            }
            String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
            Optional<EmailDomainMapping> mapping = mappings.findByEmailDomain(domain);
            if (mapping.isEmpty()) {
                return INTERNAL_UNMAPPED;
            }
            EmailDomainMapping m = mapping.get();
            // A provider lookup miss reads as internal (Go: IdP lookup failure ⇒ internal).
            boolean internal = mappings.identityProvider(m.identityProviderId())
                    .map(EmailDomainMappingRepository.IdentityProviderRef::isInternal).orElse(true);
            TwoFactorPolicy tf = m.twoFactor();
            int days = tf.rememberDeviceDays() <= 0 ? TwoFactorPolicy.DEFAULT_REMEMBER_DEVICE_DAYS : tf.rememberDeviceDays();
            return new DomainPolicy(internal, true, tf.required() && internal, tf.allowedMethods(),
                    tf.rememberDeviceEnabled() && internal, days);
        }
    }
}
