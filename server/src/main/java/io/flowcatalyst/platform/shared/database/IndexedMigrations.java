package io.flowcatalyst.platform.shared.database;

import org.flywaydb.core.api.ClassProvider;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.resource.LoadableResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// Flyway migrations located through a committed index instead of classpath
/// scanning. Scanning enumerates a classpath *directory*, which only works
/// where the loader hands out a `file:` or `jar:` URL — a GraalVM native
/// image serves `resource:` URLs and Flyway finds nothing, so a fresh
/// database would boot into an empty schema. `db/migration.index` lists one
/// migration file per line; the files themselves are loaded by name, which
/// every loader supports.
///
/// [Migrator] uses this only inside a native image; the JVM keeps scanning.
/// `IndexedMigrationsTest` pins the index to the real directory listing, so
/// a migration added without an index line fails the build rather than
/// silently going missing from the binary.
final class IndexedMigrations implements ResourceProvider, ClassProvider<JavaMigration> {

    static final String INDEX = "db/migration.index";
    static final String DIRECTORY = "db/migration/";

    private final List<String> names;

    private IndexedMigrations(List<String> names) {
        this.names = names;
    }

    static IndexedMigrations load() {
        InputStream in = IndexedMigrations.class.getClassLoader().getResourceAsStream(INDEX);
        if (in == null) throw new IllegalStateException("missing migration index " + INDEX);
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            var names = new ArrayList<String>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#")) names.add(name);
            }
            return new IndexedMigrations(List.copyOf(names));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + INDEX, e);
        }
    }

    List<String> names() {
        return names;
    }

    @Override
    public LoadableResource getResource(String name) {
        String file = name.startsWith(DIRECTORY) ? name.substring(DIRECTORY.length()) : name;
        return names.contains(file) ? new ClasspathResource(file) : null;
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        var out = new ArrayList<LoadableResource>();
        for (String name : names) {
            if (!name.startsWith(prefix)) continue;
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) {
                    out.add(new ClasspathResource(name));
                    break;
                }
            }
        }
        return out;
    }

    /// No Java migrations: every migration is SQL (`Migrator` class doc).
    @Override
    public Collection<Class<? extends JavaMigration>> getClasses() {
        return List.of();
    }

    private static final class ClasspathResource extends LoadableResource {
        private final String name;

        ClasspathResource(String name) {
            this.name = name;
        }

        @Override
        public Reader read() {
            InputStream in = IndexedMigrations.class.getClassLoader().getResourceAsStream(DIRECTORY + name);
            if (in == null) throw new IllegalStateException("indexed migration not on the classpath: " + DIRECTORY + name);
            return new InputStreamReader(in, StandardCharsets.UTF_8);
        }

        @Override
        public String getAbsolutePath() {
            return DIRECTORY + name;
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return DIRECTORY + name;
        }

        @Override
        public String getFilename() {
            return name;
        }

        @Override
        public String getRelativePath() {
            return name;
        }
    }
}
