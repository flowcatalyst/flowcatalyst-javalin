package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec `function-registry.md` §6.2):
/// `publish`, `markReady`'s idempotence, `retire`'s one-way transition and
/// `loadable` — no database involved.
class FunctionVersionTest {

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

    private static FunctionVersion published() {
        return FunctionVersion.publish("fnc_1", 1, "oci://artifact", Digest.parse("sha256:" + "a".repeat(64)),
                null, null, null, manifest(), "prn_publisher", Instant.now());
    }

    @Test
    void publishStartsPublished() {
        FunctionVersion v = published();
        assertThat(v.state()).isInstanceOf(FunctionVersion.VersionState.Published.class);
        assertThat(v.loadable()).isTrue();
    }

    // ── markReady (spec §6.2, §8 M12) ────────────────────────────────────────

    @Test
    void markReadyMovesPublishedToReady() {
        FunctionVersion v = published();
        Instant readyAt = v.publishedAt().plusSeconds(1);
        FunctionVersion ready = v.markReady(readyAt);
        assertThat(ready.state()).isInstanceOf(FunctionVersion.VersionState.Ready.class);
        assertThat(((FunctionVersion.VersionState.Ready) ready.state()).at()).isEqualTo(readyAt);
    }

    @Test
    void markReadyOnAnAlreadyReadyVersionKeepsTheFirstReadyAtAndReturnsTheSameInstance() {
        FunctionVersion v = published();
        Instant firstReadyAt = v.publishedAt().plusSeconds(1);
        FunctionVersion ready = v.markReady(firstReadyAt);
        FunctionVersion readyAgain = ready.markReady(firstReadyAt.plusSeconds(60));
        assertThat(readyAgain).as("same instance — never resurfaces a later instant").isSameAs(ready);
        assertThat(((FunctionVersion.VersionState.Ready) readyAgain.state()).at()).isEqualTo(firstReadyAt);
    }

    @Test
    void markReadyNeverResurrectsARetiredVersion() {
        FunctionVersion v = published();
        Instant retiredAt = v.publishedAt().plusSeconds(10);
        FunctionVersion retired = v.retire(retiredAt);
        FunctionVersion afterMarkReady = retired.markReady(retiredAt.plusSeconds(60));
        assertThat(afterMarkReady).isSameAs(retired);
        assertThat(afterMarkReady.state()).isInstanceOf(FunctionVersion.VersionState.Retired.class);
    }

    // ── retire ─────────────────────────────────────────────────────────────

    @Test
    void retireFromPublishedOrReadyMovesToRetired() {
        FunctionVersion published = published();
        FunctionVersion retiredFromPublished = published.retire(Instant.now());
        assertThat(retiredFromPublished.state()).isInstanceOf(FunctionVersion.VersionState.Retired.class);
        assertThat(retiredFromPublished.loadable()).isFalse();

        FunctionVersion ready = published().markReady(Instant.now());
        FunctionVersion retiredFromReady = ready.retire(Instant.now());
        assertThat(retiredFromReady.state()).isInstanceOf(FunctionVersion.VersionState.Retired.class);
    }

    @Test
    void retiringARetiredVersionConflicts() {
        FunctionVersion retired = published().retire(Instant.now());
        assertThatThrownBy(() -> retired.retire(Instant.now()))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Conflict.class);
                    assertThat(err.code()).isEqualTo("VERSION_ALREADY_RETIRED");
                });
    }

    // ── toString masks the signature bundle (CONVENTIONS.md §8) ─────────────

    @Test
    void toStringNeverPrintsTheSignatureBundleInFull() {
        String bundle = "SECRET-BUNDLE-CONTENT-THAT-MUST-NEVER-APPEAR-VERBATIM";
        FunctionVersion v = FunctionVersion.publish("fnc_1", 1, "oci://artifact",
                Digest.parse("sha256:" + "a".repeat(64)), bundle, "ref", new SignerIdentity("iss", "sub"),
                manifest(), "prn_publisher", Instant.now());
        String printed = v.toString();
        assertThat(printed).doesNotContain(bundle);
        assertThat(printed).contains(String.valueOf(bundle.length()) + " chars");
    }
}
