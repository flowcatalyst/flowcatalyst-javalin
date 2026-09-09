package io.flowcatalyst.platform.docs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// The platform's own documentation (spec §2): the curated `docs/published/`
/// Markdown pages compiled into the jar, so the served docs always match the
/// running build. Read once, when the composition root builds it (spec §2);
/// the index is immutable afterwards.
///
/// The `NN-` filename prefix fixes the reading order and is stripped from the
/// slug; the title is the page's first `# ` heading, else the slug. Anything
/// that is not a slug in the index (prefixed forms, paths, traversal) is
/// simply absent.
public final class PublishedDocs {

    private static final Logger LOG = LoggerFactory.getLogger(PublishedDocs.class);

    /// Classpath directory of the corpus, relative to the class loader root.
    static final String ROOT = "docs/published";
    private static final Pattern ORDER_PREFIX = Pattern.compile("^\\d+-");

    /// One published page: where it lives on the classpath, its slug and title.
    public record Page(String resource, String slug, String title) {
        public Page {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(title, "title");
        }
    }

    private final ClassLoader loader;
    private final List<Page> pages;
    private final Map<String, Page> bySlug;

    private PublishedDocs(ClassLoader loader, List<Page> pages) {
        this.loader = loader;
        this.pages = List.copyOf(pages);
        var index = new LinkedHashMap<String, Page>();
        pages.forEach(p -> index.put(p.slug(), p)); // last wins on a slug clash (spec §2, open question 1)
        this.bySlug = Collections.unmodifiableMap(index);
    }

    /// The corpus under [#ROOT] on this class's class loader; an unreadable
    /// corpus is logged and yields an empty index rather than an error.
    public static PublishedDocs load() {
        return load(PublishedDocs.class.getClassLoader(), ROOT);
    }

    /// The corpus under `root` on `loader` — the seam the tests use.
    static PublishedDocs load(ClassLoader loader, String root) {
        Objects.requireNonNull(loader, "loader");
        var pages = new ArrayList<Page>();
        try {
            for (String name : listMarkdown(loader, root)) {
                String resource = root + "/" + name;
                String slug = ORDER_PREFIX.matcher(name.substring(0, name.length() - ".md".length())).replaceFirst("");
                String title = Markdown.firstHeading(read(loader, resource)).orElse(slug);
                pages.add(new Page(resource, slug, title));
            }
        } catch (IOException | URISyntaxException | UncheckedIOException
                 | FileSystemNotFoundException | ProviderNotFoundException e) {
            // The last two: a class-loader scheme NIO cannot mount (spec §2 — unreadable ⇒ empty, never a failed boot).
            LOG.atWarn().setMessage("published docs unreadable; serving none")
                    .addKeyValue("path", root)
                    .setCause(e)
                    .log();
            pages.clear();
        }
        return new PublishedDocs(loader, pages);
    }

    /// Every page in reading (filename) order.
    public List<Page> pages() {
        return pages;
    }

    /// The page for `slug`, if it is one of the index's slugs.
    public Optional<Page> find(String slug) {
        return Optional.ofNullable(bySlug.get(slug));
    }

    /// The page's Markdown, verbatim.
    public String content(Page page) {
        try {
            return read(loader, page.resource());
        } catch (IOException e) {
            throw new UncheckedIOException("published doc " + page.resource() + " unreadable", e);
        }
    }

    // ── Classpath enumeration ─────────────────────────────────────────────

    /// The `.md` file names directly under `root`, name-sorted (the spec's
    /// filename order — `Files.list` itself is unordered), whether the corpus
    /// sits in an exploded directory (`file:`) or inside a jar (`jar:`).
    private static List<String> listMarkdown(ClassLoader loader, String root) throws IOException, URISyntaxException {
        URL url = loader.getResource(root);
        if (url == null) return List.of();
        URI uri = url.toURI();
        String scheme = uri.getScheme();
        // `jar:` in the shaded jar, `resource:` in a native image — both need a
        // FileSystem mounted before a Path exists; only an exploded `file:`
        // target/classes can go straight to Path.of. Native image throws
        // FileSystemNotFoundException there, which the caller caught and turned
        // into "published docs unreadable; serving none" — so the binary served
        // zero of the five pages compiled into it, and only the binary did.
        if ("jar".equals(scheme) || "resource".equals(scheme)) {
            try {
                FileSystem fs = FileSystems.newFileSystem(uri, Map.of());
                try {
                    return names(fs.provider().getPath(uri));
                } finally {
                    // A jar we mounted is ours to close; the image's resource
                    // filesystem is process-wide and closing it would break
                    // every later reader.
                    if ("jar".equals(scheme)) {
                        fs.close();
                    }
                }
            } catch (FileSystemAlreadyExistsException _) {
                // Already mounted (tests, a second index) — reuse, never close it under others.
                return names(FileSystems.getFileSystem(uri).provider().getPath(uri));
            }
        }
        return names(Path.of(uri));
    }

    private static List<String> names(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }

    private static String read(ClassLoader loader, String resource) throws IOException {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("missing classpath resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
