package io.flowcatalyst.platform.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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

    // ── Jar vs directory (spec §2: "compiled into the jar") ──────────────
    //
    // Surefire runs against target/classes, so the tests above exercise the
    // exploded-directory branch only; this one packs a corpus into a jar the
    // way maven-jar-plugin does (with directory entries) and loads through it
    // — once fresh, and once with the jar already mounted as a zip filesystem
    // by someone else (the "reuse, never close under others" branch).

    @Test
    void aJarredCorpusIsIndexedLikeADirectory(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("docs.jar");
        writeJar(jar, Map.of(
                "docs/published/20-second.md", "intro\n# Second Page\nbody",
                "docs/published/10-first.md", "# First Page",
                "docs/published/notes.txt", "not markdown",
                "docs/published/nested/30-deep.md", "# Not directly under root"));

        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            var fresh = PublishedDocs.load(loader, PublishedDocs.ROOT);
            assertThat(fresh.pages()).extracting(PublishedDocs.Page::slug, PublishedDocs.Page::title)
                    .containsExactly(tuple("first", "First Page"), tuple("second", "Second Page"));
            assertThat(fresh.content(fresh.find("second").orElseThrow())).isEqualTo("intro\n# Second Page\nbody");

            try (FileSystem alreadyMounted = FileSystems.newFileSystem(jar)) {
                var reused = PublishedDocs.load(loader, PublishedDocs.ROOT);
                assertThat(reused.pages()).extracting(PublishedDocs.Page::slug).containsExactly("first", "second");
                assertThat(alreadyMounted.isOpen()).as("the index never closes a filesystem it did not open").isTrue();
            }
        }
    }

    /// A jar with the given entries plus the directory entries a build tool writes.
    private static void writeJar(Path jar, Map<String, String> files) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); var zip = new JarOutputStream(out)) {
            for (String d : List.of("docs/", "docs/published/", "docs/published/nested/")) {
                zip.putNextEntry(new JarEntry(d));
                zip.closeEntry();
            }
            for (var e : files.entrySet()) {
                zip.putNextEntry(new JarEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    @Test
    void aMissingCorpusYieldsAnEmptyIndexNotAnError() {
        var none = PublishedDocs.load(PublishedDocs.class.getClassLoader(), "docs/no-such-dir");
        assertThat(none.pages()).isEmpty();
        assertThat(none.find("platform-overview")).isEmpty();
    }
}
