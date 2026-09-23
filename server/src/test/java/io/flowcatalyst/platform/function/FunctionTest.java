package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec `function-registry.md` §6.1): create
/// defaults, `describe`/`disable`/`enable`, and every `promote` outcome — no
/// database involved.
class FunctionTest {

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static final String MINIMAL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice",
              "endpoints": [ { "path": "/events/invoice-created", "auth": "webhook" } ],
              "subscriptions": [ { "eventType": "billing:invoices:invoice:created", "path": "/events/invoice-created" } ]
            }
            """;

    private static Manifest manifest() {
        return Manifest.parseStrict(Json.MAPPER.readTree(MINIMAL_JVM), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    private static Function newFunction() {
        return Function.create("app_1", FunctionAddress.parse("billing.invoices.create"), FunctionOwner.ofClientId("clt_1"), Runtime.JVM, null);
    }

    private static FunctionVersion versionOf(Function f, int version) {
        return FunctionVersion.publish(f.id(), version, "oci://artifact:" + version,
                Digest.parse("sha256:" + "a".repeat(64)), null, null, null, manifest(), "prn_publisher", Instant.now());
    }

    private static void assertCode(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    // ── create ─────────────────────────────────────────────────────────────

    @Test
    void createIsActiveWithNoAliases() {
        Function f = newFunction();
        assertThat(f.id()).startsWith("fnc_");
        assertThat(f.address()).isEqualTo(FunctionAddress.parse("billing.invoices.create"));
        assertThat(f.status()).isEqualTo(FunctionStatus.ACTIVE);
        assertThat(f.aliases()).isEmpty();
        assertThat(f.liveVersionId()).isEmpty();
        assertThat(f.isLive("fnv_anything")).isFalse();
    }

    @Test
    void blankDescriptionBecomesNull() {
        Function f = Function.create("app_1", FunctionAddress.parse("billing.invoices.create"), FunctionOwner.ofClientId("clt_1"), Runtime.JVM, "   ");
        assertThat(f.description()).isNull();
    }

    // ── describe ───────────────────────────────────────────────────────────

    @Test
    void describeReplacesDescriptionAndRestampsUpdatedAt() {
        Function f = newFunction();
        Instant later = f.updatedAt().plusSeconds(5);
        Function described = f.describe("a new description", later);
        assertThat(described.description()).isEqualTo("a new description");
        assertThat(described.updatedAt()).isEqualTo(later);
        assertThat(f.description()).as("original untouched").isNull();
    }

    // ── disable / enable ──────────────────────────────────────────────────

    @Test
    void disableThenEnableRoundTrips() {
        Function f = newFunction();
        Function disabled = f.disable(Instant.now());
        assertThat(disabled.status()).isEqualTo(FunctionStatus.DISABLED);
        Function enabled = disabled.enable(Instant.now());
        assertThat(enabled.status()).isEqualTo(FunctionStatus.ACTIVE);
    }

    @Test
    void disablingADisabledFunctionConflicts() {
        Function disabled = newFunction().disable(Instant.now());
        assertCode(() -> disabled.disable(Instant.now()), UseCaseError.Conflict.class, "FUNCTION_ALREADY_DISABLED");
    }

    @Test
    void enablingAnActiveFunctionConflicts() {
        Function f = newFunction();
        assertCode(() -> f.enable(Instant.now()), UseCaseError.Conflict.class, "FUNCTION_ALREADY_ACTIVE");
    }

    // ── promote ────────────────────────────────────────────────────────────

    /// spec `function-zones-and-aliases.md` §2: any alias matching
    /// `fn_aliases`' check constraint is now accepted — this pins that a
    /// well-formed NAMED alias (not `live`) is no longer rejected outright.
    @Test
    void promoteANamedAliasSucceeds() {
        Function f = newFunction();
        FunctionVersion v = versionOf(f, 1);
        Function.Promoted promoted = f.promote("canary", v, "prn_1", Instant.now());
        assertThat(promoted.previousVersionId()).isNull();
        assertThat(promoted.function().aliases()).hasSize(1);
        assertThat(promoted.function().aliases().get(0).alias()).isEqualTo("canary");
        assertThat(promoted.function().liveVersionId()).as("a named alias never touches live").isEmpty();
    }

    /// mutant: accept an alias name the `fn_aliases` check constraint would
    /// reject (uppercase, leading/trailing `-`, or over 63 characters).
    @Test
    void promoteWithAnInvalidAliasNameIsRejected() {
        Function f = newFunction();
        FunctionVersion v = versionOf(f, 1);
        assertCode(() -> f.promote("QA", v, "prn_1", Instant.now()), UseCaseError.Validation.class, "ALIAS_INVALID");
        assertCode(() -> f.promote("-x", v, "prn_1", Instant.now()), UseCaseError.Validation.class, "ALIAS_INVALID");
        assertCode(() -> f.promote("x-", v, "prn_1", Instant.now()), UseCaseError.Validation.class, "ALIAS_INVALID");
        assertCode(() -> f.promote("a".repeat(64), v, "prn_1", Instant.now()),
                UseCaseError.Validation.class, "ALIAS_INVALID");
    }

    /// mutant: compare `ALIAS_UNCHANGED` against `live`'s current target
    /// instead of the alias actually being promoted — before the fix, once
    /// `live` pointed at v1, promoting the UNRELATED `qa` alias to v1 for the
    /// first time would wrongly conflict.
    @Test
    void promotingADifferentNamedAliasToLivesCurrentVersionIsNotAliasUnchanged() {
        Function f = newFunction();
        FunctionVersion v1 = versionOf(f, 1);
        Function afterLive = f.promote(Function.LIVE, v1, "prn_1", Instant.now()).function();

        Function.Promoted qaPromoted = afterLive.promote("qa", v1, "prn_1", Instant.now());
        assertThat(qaPromoted.previousVersionId()).as("qa had no previous target of its own").isNull();
        assertThat(qaPromoted.function().aliases()).hasSize(2);
    }

    @Test
    void promotingTheSameNamedAliasToItsCurrentVersionConflicts() {
        Function f = newFunction();
        FunctionVersion v1 = versionOf(f, 1);
        Function afterQa = f.promote("qa", v1, "prn_1", Instant.now()).function();
        assertCode(() -> afterQa.promote("qa", v1, "prn_1", Instant.now()),
                UseCaseError.Conflict.class, "ALIAS_UNCHANGED");
    }

    // ── removeAlias ────────────────────────────────────────────────────────

    @Test
    void removingLiveIsProtected() {
        Function f = newFunction();
        FunctionVersion v1 = versionOf(f, 1);
        Function afterLive = f.promote(Function.LIVE, v1, "prn_1", Instant.now()).function();
        assertCode(() -> afterLive.removeAlias(Function.LIVE, Instant.now()),
                UseCaseError.Conflict.class, "ALIAS_PROTECTED");
        assertThat(afterLive.aliases()).as("mutant: remove live anyway").hasSize(1);
    }

    @Test
    void removingAnUnknownAliasIs404() {
        Function f = newFunction();
        assertCode(() -> f.removeAlias("nosuch", Instant.now()), UseCaseError.NotFound.class, "Alias_NOT_FOUND");
    }

    @Test
    void removingANamedAliasDropsItAndKeepsOthers() {
        Function f = newFunction();
        FunctionVersion v1 = versionOf(f, 1);
        Function withBoth = f.promote(Function.LIVE, v1, "prn_1", Instant.now()).function()
                .promote("qa", v1, "prn_1", Instant.now()).function();
        assertThat(withBoth.aliases()).hasSize(2);

        Function.Removed removed = withBoth.removeAlias("qa", Instant.now());
        assertThat(removed.versionId()).isEqualTo(v1.id());
        assertThat(removed.function().aliases()).as("mutant: drop the wrong alias, or drop nothing")
                .hasSize(1).extracting(Function.FunctionAlias::alias).containsExactly(Function.LIVE);
    }

    @Test
    void promoteAVersionOfAnotherFunctionIsRejected() {
        Function f = newFunction();
        Function other = Function.create("app_1", FunctionAddress.parse("billing.invoices.other"), FunctionOwner.ofClientId("clt_1"), Runtime.JVM, null);
        FunctionVersion v = versionOf(other, 1);
        assertCode(() -> f.promote(Function.LIVE, v, "prn_1", Instant.now()),
                UseCaseError.Validation.class, "VERSION_NOT_OF_FUNCTION");
    }

    @Test
    void promoteARetiredVersionConflicts() {
        Function f = newFunction();
        FunctionVersion retired = versionOf(f, 1).retire(Instant.now());
        assertCode(() -> f.promote(Function.LIVE, retired, "prn_1", Instant.now()),
                UseCaseError.Conflict.class, "VERSION_RETIRED");
    }

    @Test
    void promoteOnADisabledFunctionConflicts() {
        Function f = newFunction().disable(Instant.now());
        FunctionVersion v = versionOf(f, 1);
        assertCode(() -> f.promote(Function.LIVE, v, "prn_1", Instant.now()),
                UseCaseError.Conflict.class, "FUNCTION_DISABLED");
    }

    @Test
    void firstPromotionHasNoPreviousVersionAndCreatesTheLiveAlias() {
        Function f = newFunction();
        FunctionVersion v1 = versionOf(f, 1);
        Function.Promoted promoted = f.promote(Function.LIVE, v1, "prn_1", Instant.now());
        assertThat(promoted.previousVersionId()).isNull();
        assertThat(promoted.function().liveVersionId()).contains(v1.id());
        assertThat(promoted.function().isLive(v1.id())).isTrue();
        assertThat(promoted.function().aliases()).hasSize(1);
    }

    @Test
    void secondPromotionReturnsThePreviousVersionAndReplacesTheAlias() {
        Function f = newFunction();
        FunctionVersion v1 = versionOf(f, 1);
        FunctionVersion v2 = versionOf(f, 2);
        Function afterFirst = f.promote(Function.LIVE, v1, "prn_1", Instant.now()).function();
        Function.Promoted second = afterFirst.promote(Function.LIVE, v2, "prn_2", Instant.now());
        assertThat(second.previousVersionId()).isEqualTo(v1.id());
        assertThat(second.function().liveVersionId()).contains(v2.id());
        assertThat(second.function().aliases()).as("still exactly one live alias, not two").hasSize(1);
    }

    @Test
    void promotingTheAlreadyLiveVersionConflicts() {
        Function f = newFunction();
        FunctionVersion v1 = versionOf(f, 1);
        Function afterFirst = f.promote(Function.LIVE, v1, "prn_1", Instant.now()).function();
        assertCode(() -> afterFirst.promote(Function.LIVE, v1, "prn_1", Instant.now()),
                UseCaseError.Conflict.class, "ALIAS_UNCHANGED");
    }
}
