package io.flowcatalyst.platform.docs;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The no-I/O rules of a synced page (spec §3, §5): the slug parser's
/// accept / reject tables, title resolution, and the in-place sync update.
class AppDocTest {

    // ── AppDocSlug (spec §5 pinned table) ─────────────────────────────────

    @ParameterizedTest(name = "[{0}] ''{1}'' → ''{2}''")
    @CsvSource(delimiter = '|', value = {
            "alnum-hyphen | guide           | guide",
            "alnum-hyphen | getting-started | getting-started",
            "alnum-hyphen | v2              | v2",
            "alnum-hyphen | 2fa-setup       | 2fa-setup",
            "alnum-hyphen | a               | a",
            "trimmed      | '  guide  '     | guide",
    })
    void slugAccepts(String rule, String raw, String expected) {
        assertThat(AppDocSlug.parse(raw).value()).as(rule).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] ''{1}'' is rejected")
    @CsvSource(delimiter = '|', value = {
            "uppercase     | Getting-Started",
            "leading-dash  | -lead",
            "space         | has space",
            "underscore    | under_score",
            "dot           | dots.md",
            "empty         | ''",
            "blank         | '   '",
    })
    void slugRejects(String rule, String raw) {
        assertThatThrownBy(() -> AppDocSlug.parse(raw)).as(rule)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("SLUG_INVALID");
                });
    }

    @Test
    void nullSlugIsRejectedLikeBlank() {
        assertThatThrownBy(() -> AppDocSlug.parse(null)).isInstanceOf(UseCaseException.class);
    }

    // ── Title resolution (spec §5) ────────────────────────────────────────

    @ParameterizedTest(name = "[{0}]")
    @CsvSource(delimiter = '|', value = {
            "explicit wins              | '  Getting Started  ' | '# Other\\nbody' | getting-started | Getting Started",
            "blank explicit → heading   | '   '                 | '# Other\\nbody' | getting-started | Other",
            "absent explicit → heading  |                       | 'intro\\n  # Webhook Reference  \\nmore' | webhooks | Webhook Reference",
            "no heading → slug          |                       | 'just text\\n## h2 only' | faq | faq",
            "empty content → slug       |                       | ''              | faq | faq",
    })
    void titleFor(String rule, String explicit, String content, String slug, String expected) {
        String body = content.replace("\\n", "\n");
        assertThat(AppDoc.titleFor(explicit, body, new AppDocSlug(slug))).as(rule).isEqualTo(expected);
    }

    // ── syncedFrom ────────────────────────────────────────────────────────

    @Test
    void syncedFromKeepsIdAndCreatedAtAndRestampsTheRest() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-02-01T00:00:00Z");
        AppDoc page = AppDoc.create("app_x", new AppDocSlug("guide"), "Guide", "# Guide", 0, t0);
        assertThat(page.id()).startsWith("doc_");

        AppDoc updated = page.syncedFrom("Guide v2", "# Guide v2", 3, t1);
        assertThat(updated.id()).isEqualTo(page.id());
        assertThat(updated.createdAt()).isEqualTo(t0);
        assertThat(updated.updatedAt()).isEqualTo(t1);
        assertThat(updated.title()).isEqualTo("Guide v2");
        assertThat(updated.content()).isEqualTo("# Guide v2");
        assertThat(updated.position()).isEqualTo(3);
        assertThat(updated.slug()).isEqualTo("guide");
    }
}
