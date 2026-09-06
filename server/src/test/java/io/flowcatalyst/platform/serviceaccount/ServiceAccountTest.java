package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §2, §4): code parsing, the auth-type
/// parser table, and every transition — no database involved.
class ServiceAccountTest {

    // ── ServiceAccountCode ───────────────────────────────────────────────────

    @Test
    void codeNormalisesCaseAndWhitespace() {
        assertThat(ServiceAccountCode.parse("  SACreate-Happy  ").value()).isEqualTo("sacreate-happy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void codeRejectsBlank(String raw) {
        assertUseCaseError(() -> ServiceAccountCode.parse(raw), UseCaseError.Validation.class, "CODE_REQUIRED");
    }

    @ParameterizedTest
    @CsvSource({"bad_code", "9starts-digit", "-leading-hyphen", "Has Space"})
    void codeRejectsInvalidFormat(String raw) {
        assertUseCaseError(() -> ServiceAccountCode.parse(raw), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
    }

    @ParameterizedTest
    @CsvSource({"my-service", "a", "a1-2b", "service123"})
    void codeAcceptsLowercaseAlphanumericAndHyphens(String raw) {
        assertThat(ServiceAccountCode.parse(raw).value()).isEqualTo(raw);
    }

    /// Owner ruling 2026-09-06 #16: `app:<code>` is the reserved namespace of an
    /// application's own service account — the value object admits it, the
    /// user-chosen path refuses it.
    @Test
    void appNamespaceIsAdmittedByTheValueObjectAndRefusedForUserChosenCodes() {
        assertThat(ServiceAccountCode.parse("app:orders").value()).isEqualTo("app:orders");
        assertThat(ServiceAccountCode.parse(" APP:Orders ").value()).isEqualTo("app:orders");
        assertUseCaseError(() -> ServiceAccountCode.parse("app:"), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertUseCaseError(() -> ServiceAccountCode.parse("app:9x"), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertUseCaseError(() -> ServiceAccountCode.parseUserChosen("app:orders"), UseCaseError.Validation.class, "RESERVED_CODE");
        assertThat(ServiceAccountCode.parseUserChosen("orders").value()).isEqualTo("orders");
    }

    // ── WebhookAuthType ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "BEARER_TOKEN", "BASIC_AUTH", "API_KEY", "HMAC_SIGNATURE"})
    void authTypeKnownValuesRoundTrip(String value) {
        assertThat(WebhookAuthType.parse(value)).isEqualTo(WebhookAuthType.valueOf(value));
    }

    @Test
    void authTypeBlankOrNullMeansNone() {
        assertThat(WebhookAuthType.parse(null)).isEqualTo(WebhookAuthType.NONE);
        assertThat(WebhookAuthType.parse("")).isEqualTo(WebhookAuthType.NONE);
    }

    /// X-06 (spec §11, drift `6cbe708`): a misspelled or corrupted auth type
    /// must never silently become `NONE` — that would ship an unauthenticated
    /// webhook with nothing to say why.
    @ParameterizedTest
    @ValueSource(strings = {"BEARER_TOEKN", "bearer_token", "BEARERTOKEN", "garbage", " NONE", "none"})
    void authTypeRejectsUnrecognisedValuesLoudly(String bad) {
        assertThatThrownBy(() -> WebhookAuthType.parse(bad)).isInstanceOf(WebhookAuthType.UnrecognisedAuthTypeException.class);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsActiveWithNoCredentialsNoClientsNoRoles() {
        var sa = ServiceAccount.create(ServiceAccountCode.parse("svc-create"), "  Svc  ");
        assertThat(sa.id()).startsWith("sac_");
        assertThat(sa.code()).isEqualTo("svc-create");
        assertThat(sa.name()).as("name is trimmed").isEqualTo("Svc");
        assertThat(sa.active()).isTrue();
        assertThat(sa.clientIds()).isEmpty();
        assertThat(sa.roles()).isEmpty();
        assertThat(sa.lastUsedAt()).isNull();
        assertThat(sa.webhookCredentials().authType()).isEqualTo(WebhookAuthType.NONE);
    }

    // ── Deactivate ─────────────────────────────────────────────────────────

    @Test
    void deactivateFlipsActiveAndBumpsUpdatedAt() {
        var sa = ServiceAccount.create(ServiceAccountCode.parse("svc-deact"), "Svc");
        var before = sa.updatedAt();
        var deactivated = sa.deactivate();
        assertThat(deactivated.active()).isFalse();
        assertThat(deactivated.updatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void deactivateIsIdempotentAndAlwaysWrites() {
        var sa = ServiceAccount.create(ServiceAccountCode.parse("svc-deact2"), "Svc").deactivate();
        var again = sa.deactivate();
        assertThat(again.active()).isFalse();
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesOnlyTheGivenFields() {
        var sa = ServiceAccount.create(ServiceAccountCode.parse("svc-upd"), "Original")
                .withDescription("orig desc").withScope("anchor").withClientIds(List.of("clt_a"));

        var updated = sa.update(new ServiceAccount.Changes("  New Name  ", null, null, null, null));
        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.description()).as("untouched fields survive").isEqualTo("orig desc");
        assertThat(updated.scope()).isEqualTo("anchor");
        assertThat(updated.clientIds()).containsExactly("clt_a");
    }

    @Test
    void updateDistinguishesNullClientIdsFromEmpty() {
        var sa = ServiceAccount.create(ServiceAccountCode.parse("svc-upd2"), "Svc").withClientIds(List.of("clt_a"));

        var untouched = sa.update(new ServiceAccount.Changes(null, null, null, null, null));
        assertThat(untouched.clientIds()).as("null clientIds means untouched").containsExactly("clt_a");

        var cleared = sa.update(new ServiceAccount.Changes(null, null, null, List.of(), null));
        assertThat(cleared.clientIds()).as("an explicit empty list clears it").isEmpty();
    }

    @Test
    void updateReplacesWebhookCredentialsWholesaleNotMerged() {
        var sa = ServiceAccount.create(ServiceAccountCode.parse("svc-upd3"), "Svc")
                .withWebhookCredentials(WebhookCredentials.bearer("fc_old", "old-secret"));

        var updated = sa.update(new ServiceAccount.Changes(null, null, null, null,
                new WebhookCredentials(WebhookAuthType.API_KEY, null, null, null, "X-Api-Key", null, null, null)));

        assertThat(updated.webhookCredentials().authType()).isEqualTo(WebhookAuthType.API_KEY);
        assertThat(updated.webhookCredentials().token()).as("old token is not merged in").isNull();
        assertThat(updated.webhookCredentials().headerName()).isEqualTo("X-Api-Key");
    }

    // ── Credential rotation ──────────────────────────────────────────────────

    @Test
    void withTokenForcesBearerAuthTypeAndKeepsTheSigningSecret() {
        var sa = ServiceAccount.create(ServiceAccountCode.parse("svc-rot"), "Svc")
                .withWebhookCredentials(new WebhookCredentials(WebhookAuthType.NONE, null, null, null, null, "keep-me", null, null));

        var rotated = sa.withToken("fc_new_token");
        assertThat(rotated.webhookCredentials().authType()).isEqualTo(WebhookAuthType.BEARER_TOKEN);
        assertThat(rotated.webhookCredentials().token()).isEqualTo("fc_new_token");
        assertThat(rotated.webhookCredentials().signingSecret()).as("signing secret untouched by a token rotation").isEqualTo("keep-me");
    }

    @Test
    void withSigningSecretLeavesAuthTypeAlone() {
        var sa = ServiceAccount.create(ServiceAccountCode.parse("svc-rot2"), "Svc"); // authType NONE
        var rotated = sa.withSigningSecret("new-secret");
        assertThat(rotated.webhookCredentials().authType()).as("authType is left as-is").isEqualTo(WebhookAuthType.NONE);
        assertThat(rotated.webhookCredentials().signingSecret()).isEqualTo("new-secret");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }
}
