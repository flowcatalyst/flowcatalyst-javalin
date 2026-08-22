package io.flowcatalyst.platform.process;

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

/// The aggregate's pure rules (spec §1–2): code parsing, the create
/// defaults, lenient enum reads, and every transition — no database involved.
class ProcessTest {

    // ── Code ───────────────────────────────────────────────────────────────

    @Test
    void codeParsesIntoThreeSegments() {
        assertThat(ProcessCode.parse("orders:fulfilment:pick-pack"))
                .isEqualTo(new ProcessCode("orders", "fulfilment", "pick-pack"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders:fulfilment", "orders", "", "orders:fulfilment:pick:pack"})
    void codeRejectsWrongSegmentCounts(String code) {
        assertUseCaseError(() -> ProcessCode.parse(code), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThatThrownBy(() -> ProcessCode.parse(code)).hasMessageContaining(ProcessCode.FORMAT_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders: :pick", ":fulfilment:pick", "orders:fulfilment:"})
    void codeRejectsBlankSegments(String code) {
        assertUseCaseError(() -> ProcessCode.parse(code), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThatThrownBy(() -> ProcessCode.parse(code)).hasMessageContaining(ProcessCode.EMPTY_SEGMENT_MESSAGE);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createIsCurrentUiSourcedWithTheDefaultsAndDenormalisesTheCode() {
        var p = Process.create("orders:fulfilment:pick-pack", "Pick & Pack");
        assertThat(p.id()).startsWith("prc_");
        assertThat(p.code()).isEqualTo("orders:fulfilment:pick-pack");
        assertThat(p.application()).isEqualTo("orders");
        assertThat(p.subdomain()).isEqualTo("fulfilment");
        assertThat(p.processName()).isEqualTo("pick-pack");
        assertThat(p.status()).isEqualTo(ProcessStatus.CURRENT);
        assertThat(p.source()).isEqualTo(ProcessSource.UI);
        assertThat(p.body()).as("no body is the empty string, never null").isEmpty();
        assertThat(p.diagramType()).isEqualTo(Process.DEFAULT_DIAGRAM_TYPE);
        assertThat(p.tags()).isEmpty();
        assertThat(p.description()).isNull();
        assertThat(p.createdBy()).isNull();
    }

    @Test
    void createRejectsAMalformedCode() {
        assertUseCaseError(() -> Process.create("a:b", "x"), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
    }

    @ParameterizedTest(name = "diagramType [{0}] → {1}")
    @CsvSource(value = {"null,mermaid", "'',mermaid", "'  ',mermaid", "plantuml,plantuml"}, nullValues = "null")
    void blankDiagramTypeKeepsTheDefault(String given, String expected) {
        assertThat(Process.create("a:b:c", "x").withDiagramType(given).diagramType()).isEqualTo(expected);
    }

    @Test
    void absentBodyAndTagsBecomeEmptyNotNull() {
        var p = Process.create("a:b:c", "x").withBody(null).withTags(null);
        assertThat(p.body()).isEmpty();
        assertThat(p.tags()).isEmpty();
        assertThat(Process.create("a:b:c", "x").withBody("graph TD; A-->B").withTags(List.of("core")))
                .extracting(Process::body, Process::tags).containsExactly("graph TD; A-->B", List.of("core"));
    }

    // ── Archive ────────────────────────────────────────────────────────────

    @Test
    void archiveIsOneWayUnconditionalAndLeavesTheOriginalUntouched() {
        var p = Process.create("a:b:c", "Name");
        var archived = p.archive();
        assertThat(archived.isArchived()).isTrue();
        assertThat(archived.updatedAt()).isAfterOrEqualTo(p.updatedAt());
        assertThat(p.status()).as("records are immutable").isEqualTo(ProcessStatus.CURRENT);
        assertThat(archived.archive().isArchived()).as("archiving twice is not a conflict (spec §2)").isTrue();
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesOnlyTheSuppliedFields() {
        var p = Process.create("a:b:c", "Before").withDescription("before").withBody("old").withTags(List.of("x"));

        var renamed = p.update(new Process.Changes("After", null, null, null, null));
        assertThat(renamed.name()).isEqualTo("After");
        assertThat(renamed.description()).isEqualTo("before");
        assertThat(renamed.body()).isEqualTo("old");
        assertThat(renamed.diagramType()).isEqualTo(Process.DEFAULT_DIAGRAM_TYPE);
        assertThat(renamed.tags()).containsExactly("x");
        assertThat(renamed.code()).as("code is immutable").isEqualTo("a:b:c");
        assertThat(renamed.updatedAt()).isAfterOrEqualTo(p.updatedAt());

        var all = p.update(new Process.Changes("N", "d", "graph LR", "plantuml", List.of()));
        assertThat(all).extracting(Process::name, Process::description, Process::body, Process::diagramType, Process::tags)
                .containsExactly("N", "d", "graph LR", "plantuml", List.of());
        assertThat(all.status()).isEqualTo(ProcessStatus.CURRENT);
        assertThat(all.source()).isEqualTo(ProcessSource.UI);
    }

    // ── Sync overwrite ─────────────────────────────────────────────────────

    @Test
    void syncedFromReplacesDeclarativelyButKeepsSourceStatusAndANamedDiagramType() {
        var p = Process.create("a:b:c", "Before").withSource(ProcessSource.CODE).withDescription("before")
                .withBody("old").withDiagramType("plantuml").withTags(List.of("x")).archive();

        var synced = p.syncedFrom("After", null, null, null, List.of());
        assertThat(synced.name()).isEqualTo("After");
        assertThat(synced.description()).as("absent description clears it").isNull();
        assertThat(synced.body()).as("absent body is the empty string").isEmpty();
        assertThat(synced.diagramType()).as("blank diagram type keeps the stored one").isEqualTo("plantuml");
        assertThat(synced.tags()).as("absent tags clears them").isEmpty();
        assertThat(synced.source()).as("a CODE row stays CODE").isEqualTo(ProcessSource.CODE);
        assertThat(synced.status()).as("status is untouched").isEqualTo(ProcessStatus.ARCHIVED);

        assertThat(p.syncedFrom("N", "d", "graph", "mermaid", List.of("y")).diagramType()).isEqualTo("mermaid");
    }

    // ── Lenient readers ────────────────────────────────────────────────────

    @Test
    void storedEnumValuesAreReadLenientlyWithDefaults() {
        assertThat(ProcessStatus.parse("ARCHIVED")).isEqualTo(ProcessStatus.ARCHIVED);
        assertThat(ProcessStatus.parse("UNKNOWN")).isEqualTo(ProcessStatus.CURRENT);
        assertThat(ProcessStatus.parse(null)).isEqualTo(ProcessStatus.CURRENT);

        assertThat(ProcessSource.parse("CODE")).isEqualTo(ProcessSource.CODE);
        assertThat(ProcessSource.parse("API")).isEqualTo(ProcessSource.API);
        assertThat(ProcessSource.parse("UNKNOWN")).isEqualTo(ProcessSource.UI);
        assertThat(ProcessSource.parse(null)).isEqualTo(ProcessSource.UI);
    }

    @Test
    void onlyApiAndCodeRowsAreSyncManaged() {
        assertThat(ProcessSource.API.isSyncManaged()).isTrue();
        assertThat(ProcessSource.CODE.isSyncManaged()).isTrue();
        assertThat(ProcessSource.UI.isSyncManaged()).isFalse();
    }

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
