package io.flowcatalyst.platform.notify;

import io.flowcatalyst.platform.mail.Mail;
import io.flowcatalyst.platform.mail.MailService;
import io.flowcatalyst.platform.auth.mfa.TwoFactorNotifier;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.principal.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

/// The security-notification catalogue (`docs/spec/auth-identity.md` §10):
/// every send is best-effort — a blank recipient is a no-op, a transport
/// failure is a warning, never an error to the caller. The brand name is
/// the live platform name, falling back to `FlowCatalyst` (ruling I-Q16).
public final class Notifications implements Notifier, TwoFactorNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(Notifications.class);

    public static final String DEFAULT_BRAND = "FlowCatalyst";
    static final String FOOTER = "<p style=\"color:#888;font-size:12px\">If this wasn't you, contact your administrator immediately.</p>";

    private final MailService mail;
    private final Supplier<String> brandName;

    public Notifications(MailService mail, Supplier<String> brandName) {
        this.mail = Objects.requireNonNull(mail, "mail");
        this.brandName = Objects.requireNonNull(brandName, "brandName");
    }

    String brand() {
        String n;
        try {
            n = brandName.get();
        } catch (RuntimeException e) {
            LOG.warn("brand name lookup failed; using the default", e);
            return DEFAULT_BRAND;
        }
        return n == null || n.isBlank() ? DEFAULT_BRAND : n;
    }

    void send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            return;
        }
        try {
            mail.send(new Mail(to, subject, body));
        } catch (RuntimeException e) {
            LOG.atWarn().setMessage("security notification not delivered")
                    .addKeyValue("to", to)
                    .addKeyValue("subject", subject)
                    .setCause(e)
                    .log();
        }
    }

    @Override
    public void accountCreated(String to) {
        send(to, "Your account has been created",
                "<p>Your " + brand() + " account has been created.</p>"
                        + "<p>Sign in to get started. If two-factor authentication is required for your organisation, "
                        + "you'll be guided through setting it up.</p>");
    }

    @Override
    public void passwordChanged(String to) {
        send(to, "Your password was changed", "<p>Your " + brand() + " password was just changed.</p>" + FOOTER);
    }

    public void portalPasswordChanged(String to) {
        send(to, "Your portal password was changed", "<p>Your portal password was just changed.</p>" + FOOTER);
    }

    @Override
    public void twoFactorEnrolled(String to, MfaMethod method) {
        twoFactorEnrolled(to, method == null ? "" : method.name());
    }

    @Override
    public void twoFactorMethodRemoved(String to, MfaMethod method) {
        twoFactorMethodRemoved(to, method == null ? "" : method.name());
    }

    public void twoFactorEnrolled(String to, String method) {
        send(to, "Two-factor authentication enabled",
                "<p>A new two-factor method (" + methodLabel(method) + ") was added to your account.</p>" + FOOTER);
    }

    public void twoFactorMethodRemoved(String to, String method) {
        send(to, "Two-factor method removed",
                "<p>A two-factor method (" + methodLabel(method) + ") was removed from your account.</p>" + FOOTER);
    }

    @Override
    public void twoFactorReset(String to) {
        send(to, "Two-factor authentication was reset",
                "<p>Your two-factor authentication has been reset. You'll be asked to set it up again the next time you sign in.</p>" + FOOTER);
    }

    @Override
    public void recoveryCodesRegenerated(String to) {
        send(to, "New recovery codes generated",
                "<p>A new set of two-factor recovery codes was generated for your account. Your previous codes no longer work.</p>" + FOOTER);
    }

    @Override
    public void recoveryCodeUsed(String to) {
        send(to, "A recovery code was used to sign in",
                "<p>One of your two-factor recovery codes was just used to sign in.</p>" + FOOTER);
    }

    public void newPasskey(String to) {
        send(to, "A new passkey was registered",
                "<p>A new passkey (security key / device) was registered to your account.</p>" + FOOTER);
    }

    @Override
    public void newTrustedDevice(String to, String label) {
        String body = "<p>A device was just remembered so it can skip two-factor prompts.</p>";
        if (label != null && !label.isEmpty()) {
            body += "<p style=\"color:#555\">" + escape(label) + "</p>";
        }
        send(to, "A new device was remembered", body + FOOTER);
    }

    public void resetApprovalNeeded(String to, String link) {
        send(to, "A password reset needs your approval",
                "<p>A user in your organisation has requested a password reset but has no authenticator or passkey on file, "
                        + "so it needs an administrator to approve it.</p>"
                        + "<p><a href=\"" + escape(link) + "\">Review the request</a></p>");
    }

    static String methodLabel(String method) {
        return switch (method == null ? "" : method) {
            case "TOTP" -> "authenticator app";
            case "EMAIL_PIN" -> "email code";
            default -> method == null ? "" : method;
        };
    }

    /// A User-Agent or link lands inside HTML: the five characters that
    /// would let it become markup are escaped (Go emitted the label raw).
    static String escape(String s) {
        var b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
