package io.flowcatalyst.platform.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// The embedded platform corpus (spec §2): filename order, prefix-free
/// slugs, first-heading titles, verbatim content, and nothing served for
/// anything that is not a slug.
class PublishedDocsTest {

    private static final PublishedDocs DOCS = PublishedDocs.load();

    @Test
    void readingOrderIsTheFilenameOrderAndStartsAtTheOverview() {
        assertThat(DOCS.pages()).isNotEmpty();
        assertThat(DOCS.pages().getFirst().slug()).isEqualTo("platform-overview");
        assertThat(DOCS.pages()).extracting(PublishedDocs.Page::slug).containsExactly(
                "platform-overview", "messaging-and-delivery", "identity-and-access",
                "portal-users", "applications-and-integration");
    }

    @Test
    void titlesComeFromTheFirstHeading() {
        assertThat(DOCS.find("portal-users")).map(PublishedDocs.Page::title).contains("Portal Users Architecture");
        assertThat(DOCS.find("platform-overview")).map(PublishedDocs.Page::title).contains("Platform Overview");
    }

    @Test
    void contentIsTheFileVerbatim() {
        var page = DOCS.find("platform-overview").orElseThrow();
        assertThat(DOCS.content(page)).startsWith("# Platform Overview");
    }

    @ParameterizedTest(name = "''{0}'' is not a slug")
    @ValueSource(strings = {"10-platform-overview", "nope", "../embed.go", "published/10-platform-overview", ""})
    void nonSlugsAreAbsent(String slug) {
        assertThat(DOCS.find(slug)).isEmpty();
    }

    @Test
    void aMissingCorpusYieldsAnEmptyIndexNotAnError() {
        var none = PublishedDocs.load(PublishedDocs.class.getClassLoader(), "docs/no-such-dir");
        assertThat(none.pages()).isEmpty();
        assertThat(none.find("platform-overview")).isEmpty();
    }
}
