package io.flowcatalyst.platform.passwordreset;

import io.flowcatalyst.platform.mail.Mail;
import io.flowcatalyst.platform.mail.MailService;
import io.flowcatalyst.platform.principal.InviteEmailer;
import io.flowcatalyst.platform.principal.PasswordResetEmailer;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.publicapi.EmailTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/// The link minter and mailer (`docs/spec/auth-identity.md` §8.1, §8.8;
/// Go's `principalEmailer`): every mint first deletes the subject's tokens
/// — one live token per subject across reset, invite and approval — then
/// stores the hash and mails the raw link. Reset links live 15 minutes,
/// invites 72 hours. Ruling I-Q14: an admin-triggered reset never asks
/// for the user's factor (`requires_factor = false`); the option to also
/// clear the user's factors rides on `reset2fa`.
public final class ResetLinks implements PasswordResetEmailer, InviteEmailer {

    private static final Logger LOG = LoggerFactory.getLogger(ResetLinks.class);

    static final String PORTAL_BRAND = "Portal";
    static final String PORTAL_FOOTER = "This is an automated message. Please do not reply to this email.";

    private final ResetTokenRepository tokens;
    private final MailService mail;
    private final Supplier<EmailTheme> theme;
    private final String baseUrl;
    private final Clock clock;

    /// @param theme   the live platform theme (`Branding.emailTheme()`)
    /// @param baseUrl `FC_JWT_ISSUER`, the origin the SPA's reset pages live under
    public ResetLinks(ResetTokenRepository tokens, MailService mail, Supplier<EmailTheme> theme, String baseUrl, Clock clock) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.mail = Objects.requireNonNull(mail, "mail");
        this.theme = Objects.requireNonNull(theme, "theme");
        this.baseUrl = trim(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ── employee plane ─────────────────────────────────────────────────────

    /// Silently skipped for a principal without an e-mail address.
    @Override
    public void sendResetEmail(Principal p, boolean reset2fa) {
        if (p.email() == null || p.email().isBlank()) {
            LOG.atInfo().setMessage("password reset skipped: principal has no email")
                    .addKeyValue("principal", p.id())
                    .log();
            return;
        }
        String raw = mint(p.id(), ResetToken.Purpose.RESET, reset2fa, false, null);
        sendResetLink(p.email(), resetLink(raw), platformTheme());
    }

    @Override
    public void sendInvite(Principal p) {
        sendInviteRedirect(p, null);
    }

    public void sendInviteRedirect(Principal p, String redirectUri) {
        if (p.email() == null || p.email().isBlank()) {
            LOG.atInfo().setMessage("invite skipped: principal has no email")
                    .addKeyValue("principal", p.id())
                    .log();
            return;
        }
        String raw = mint(p.id(), ResetToken.Purpose.INVITE, false, false, redirectUri);
        sendInviteLink(p.email(), setPasswordLink(raw), platformTheme());
    }

    /// The invite link without a mail — the admin hands it over.
    public String inviteLink(Principal p) {
        return setPasswordLink(mint(p.id(), ResetToken.Purpose.INVITE, false, false, null));
    }

    // ── portal plane ───────────────────────────────────────────────────────

    public void sendPortalReset(String identityId, String email, String redirectUri) {
        String raw = mint(identityId, ResetToken.Purpose.RESET, false, false, redirectUri);
        var t = portalTheme();
        send(email, "Reset your password", t.render(new EmailTheme.EmailContent("Reset your password",
                "We received a request to reset your portal password. Click the button below to choose a new one.",
                "Reset password", resetLink(raw),
                List.of("This link expires in 15 minutes.", "If you didn't request this, you can safely ignore this email."), null)));
    }

    public String portalInviteLink(String identityId, String redirectUri) {
        return setPasswordLink(mint(identityId, ResetToken.Purpose.INVITE, false, false, redirectUri));
    }

    public void sendPortalInvite(String identityId, String email, String redirectUri) {
        String link = portalInviteLink(identityId, redirectUri);
        send(email, "Join the portal", portalTheme().render(new EmailTheme.EmailContent("You've been invited to the portal",
                "A portal account has been created for you. Click the button below to choose a password and sign in.",
                "Join the portal", link, List.of("This link expires in 72 hours."), null)));
    }

    /// No token: the account signs in at its identity provider.
    public void sendPortalSsoInvite(String email, String portalUrl) {
        send(email, "You've been invited", portalTheme().render(new EmailTheme.EmailContent("You've been invited",
                "You've been given access to a customer portal. Open it and sign in with your organisation account — no password setup needed.",
                "Open the portal", portalUrl, List.of(), null)));
    }

    // ── the pieces ─────────────────────────────────────────────────────────

    String mint(String subject, ResetToken.Purpose purpose, boolean reset2fa, boolean requiresFactor, String redirectUri) {
        tokens.deleteByPrincipal(subject);
        var minted = ResetToken.mint(subject, purpose, reset2fa, requiresFactor, redirectUri, clock.instant());
        tokens.insert(minted.token());
        return minted.raw();
    }

    /// Self-service resets carry the factor requirement (§8.2 step 5).
    String mintSelfServiceReset(String principalId, boolean requiresFactor) {
        return mint(principalId, ResetToken.Purpose.RESET, false, requiresFactor, null);
    }

    void sendResetLink(String to, String link, EmailTheme t) {
        send(to, "Reset your password", t.render(new EmailTheme.EmailContent("Reset your password",
                "We received a request to reset your password. Click the button below to choose a new one.",
                "Reset password", link,
                List.of("This link expires in 15 minutes.", "If you didn't request this, you can safely ignore this email."), null)));
    }

    void sendInviteLink(String to, String link, EmailTheme t) {
        send(to, "Set your password", t.render(new EmailTheme.EmailContent("Welcome to " + t.brandName(),
                "An account has been created for you. Click the button below to set your password and sign in.",
                "Set your password", link,
                List.of("If two-factor authentication is required for your organisation, you'll be guided through setting it up.",
                        "This link expires in 72 hours."), null)));
    }

    String resetLink(String raw) {
        return baseUrl + "/auth/reset-password?token=" + raw;
    }

    String setPasswordLink(String raw) {
        return baseUrl + "/auth/set-password?token=" + raw;
    }

    EmailTheme platformTheme() {
        try {
            return theme.get();
        } catch (RuntimeException e) {
            LOG.warn("e-mail theme lookup failed; using defaults", e);
            return EmailTheme.defaults(io.flowcatalyst.platform.notify.Notifications.DEFAULT_BRAND);
        }
    }

    /// Portal mails: brand `Portal`, the platform colours, no logo, the fixed footer.
    EmailTheme portalTheme() {
        EmailTheme p = platformTheme();
        return new EmailTheme(PORTAL_BRAND, p.primaryColor(), p.accentColor(), null, null, PORTAL_FOOTER);
    }

    private void send(String to, String subject, String html) {
        mail.send(new Mail(to, subject, html));
    }

    private static String trim(String base) {
        String b = base;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    Optional<ResetToken> find(String raw) {
        return tokens.findByHash(ResetToken.hash(raw == null ? "" : raw));
    }
}
