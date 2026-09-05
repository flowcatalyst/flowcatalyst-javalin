package io.flowcatalyst.platform.notify;

import io.flowcatalyst.platform.mail.Mail;
import io.flowcatalyst.platform.mail.MailException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/auth-identity.md` §10: subjects and bodies as the catalogue
/// fixes them, the brand fallback per ruling I-Q16, and best-effort
/// delivery — a blank recipient or a failing transport never reaches the
/// caller.
class NotificationsTest {

    private final List<Mail> sent = new ArrayList<>();
    private final Notifications n = new Notifications(sent::add, () -> "Acme Portal");

    @Test
    void theBrandedNoticesCarryTheLivePlatformName() {
        n.accountCreated("a@example.com");
        n.passwordChanged("a@example.com");
        assertThat(sent).extracting(Mail::subject).containsExactly("Your account has been created", "Your password was changed");
        assertThat(sent.get(0).html()).isEqualTo("<p>Your Acme Portal account has been created.</p>"
                + "<p>Sign in to get started. If two-factor authentication is required for your organisation, you'll be guided through setting it up.</p>");
        assertThat(sent.get(0).html()).as("no footer on account-created").doesNotContain("If this wasn't you");
        assertThat(sent.get(1).html()).isEqualTo("<p>Your Acme Portal password was just changed.</p>" + Notifications.FOOTER);
    }

    @Test
    void theBrandFallsBackToFlowCatalystWithACapitalC() {
        var blank = new Notifications(sent::add, () -> "  ");
        blank.passwordChanged("a@example.com");
        assertThat(sent.getFirst().html()).as("ruling I-Q16").contains("Your FlowCatalyst password");
        var failing = new Notifications(sent::add, () -> { throw new IllegalStateException("db down"); });
        failing.passwordChanged("a@example.com");
        assertThat(sent.get(1).html()).contains("Your FlowCatalyst password");
    }

    @Test
    void everyOtherNoticeHasItsSubjectAndCopy() {
        n.portalPasswordChanged("a@example.com");
        n.twoFactorEnrolled("a@example.com", "TOTP");
        n.twoFactorMethodRemoved("a@example.com", "EMAIL_PIN");
        n.twoFactorReset("a@example.com");
        n.recoveryCodesRegenerated("a@example.com");
        n.recoveryCodeUsed("a@example.com");
        n.newPasskey("a@example.com");
        n.newTrustedDevice("a@example.com", "Mozilla/5.0 <script>");
        n.newTrustedDevice("a@example.com", "");
        n.resetApprovalNeeded("admin@example.com", "https://x.example/authentication/reset-approvals/rar_1");
        assertThat(sent).extracting(Mail::subject).containsExactly(
                "Your portal password was changed", "Two-factor authentication enabled", "Two-factor method removed",
                "Two-factor authentication was reset", "New recovery codes generated", "A recovery code was used to sign in",
                "A new passkey was registered", "A new device was remembered", "A new device was remembered",
                "A password reset needs your approval");
        assertThat(sent.get(1).html()).contains("(authenticator app)");
        assertThat(sent.get(2).html()).contains("(email code)");
        assertThat(sent.get(7).html()).contains("<p style=\"color:#555\">Mozilla/5.0 &lt;script&gt;</p>").as("the label is escaped");
        assertThat(sent.get(8).html()).doesNotContain("color:#555");
        assertThat(sent.get(9).html()).contains("<a href=\"https://x.example/authentication/reset-approvals/rar_1\">Review the request</a>")
                .doesNotContain("If this wasn't you");
        assertThat(sent.get(0).html()).as("the portal notice carries no brand").doesNotContain("Acme");
    }

    @Test
    void aBlankRecipientOrAFailingTransportIsSwallowed() {
        n.passwordChanged("");
        n.passwordChanged(null);
        assertThat(sent).isEmpty();
        var broken = new Notifications(m -> { throw new MailException("smtp down"); }, () -> "X");
        broken.passwordChanged("a@example.com"); // must not throw
    }
}
