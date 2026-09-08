package io.flowcatalyst.fcdev;

import io.flowcatalyst.fcdev.PgExtensions.Donor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// [PgExtensions] over temp directories — no database, no Postgres.
///
/// What these pin, and why it matters: the Go `fcdev` and the Java `fcdev`
/// share ONE cluster data directory but each extracts its OWN copy of the
/// Postgres binaries. A cluster carrying PostGIS objects therefore starts
/// happily under the Java binary and then fails inside the first query that
/// touches one, because `$libdir/postgis-3` is only in the other tree. These
/// are the three behaviours that close that gap: find a donor, copy what is
/// missing without ever clobbering what is there, and name what is still
/// unservable.
class PgExtensionsTest {

    // ── donorCandidates ─────────────────────────────────────────────────

    /// Order is the whole point: a packaged install built for this exact
    /// Postgres major is preferred, and the sibling `fcdev` tree (where the
    /// Go binary extracts, and where a hand-transplanted PostGIS lives) is
    /// the last resort that keeps an already-working machine working.
    @Test
    void donorCandidatesPreferPackagedInstallsAndEndAtTheSiblingFcdevTree(@TempDir Path cache) {
        List<Donor> candidates = PgExtensions.donorCandidates("18", cache);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().extensions().toString())
                .as("a packaged install comes first, not the sibling tree")
                .doesNotStartWith(cache.toString());
        assertThat(candidates.getLast().modules())
                .as("the sibling fcdev tree is the final fallback")
                .isEqualTo(cache.resolve("bin").resolve("lib").resolve("postgresql"));
        assertThat(candidates.getLast().extensions())
                .isEqualTo(cache.resolve("bin").resolve("share").resolve("postgresql").resolve("extension"));
    }

    /// The major is not cosmetic — PostGIS built for one Postgres major will
    /// not load into another, so every packaged candidate must be
    /// major-qualified.
    @Test
    void donorCandidatesCarryThePostgresMajorIntoEveryPackagedPath(@TempDir Path cache) {
        List<Donor> forEighteen = PgExtensions.donorCandidates("18", cache);
        List<Donor> forSeventeen = PgExtensions.donorCandidates("17", cache);

        assertThat(forEighteen).hasSameSizeAs(forSeventeen);
        for (int i = 0; i < forEighteen.size() - 1; i++) {
            assertThat(forEighteen.get(i))
                    .as("packaged candidate %d must differ between majors", i)
                    .isNotEqualTo(forSeventeen.get(i));
        }
        assertThat(forEighteen.getLast())
                .as("only the sibling tree is major-agnostic — it IS the running major")
                .isEqualTo(forSeventeen.getLast());
    }

    // ── firstUsable ─────────────────────────────────────────────────────

    /// A directory pair that exists but has no `postgis.control` is not a
    /// donor: copying from it would produce a tree that still cannot serve
    /// the extension, which is exactly the failure being prevented.
    @Test
    void firstUsableSkipsADirectoryPairThatHasNoPostgisControl(@TempDir Path root) throws IOException {
        Donor empty = donorAt(root.resolve("empty"), false);
        Donor real = donorAt(root.resolve("real"), true);

        assertThat(PgExtensions.firstUsable(List.of(empty, real)))
                .as("skips past the incomplete one to the real donor")
                .contains(real);
        assertThat(PgExtensions.firstUsable(List.of(empty)))
                .as("no usable donor is not an error, it is simply absent")
                .isEmpty();
    }

    @Test
    void firstUsableTakesTheEarliestCompleteCandidate(@TempDir Path root) throws IOException {
        Donor first = donorAt(root.resolve("a"), true);
        Donor second = donorAt(root.resolve("b"), true);

        assertThat(PgExtensions.firstUsable(List.of(first, second))).contains(first);
    }

    @Test
    void firstUsableIgnoresACandidateWhoseDirectoriesDoNotExist(@TempDir Path root) {
        Donor missing = new Donor(root.resolve("nope/lib"), root.resolve("nope/extension"));

        assertThat(PgExtensions.firstUsable(List.of(missing))).isEmpty();
    }

    // ── mirror ──────────────────────────────────────────────────────────

    @Test
    void mirrorCopiesTheWholePostgisFamilyAndNothingElse(@TempDir Path root) throws IOException {
        Donor donor = donorAt(root.resolve("donor"), true);
        write(donor.modules(), "postgis-3.dylib");
        write(donor.modules(), "postgis_raster-3.dylib");
        write(donor.modules(), "address_standardizer-3.dylib");
        write(donor.modules(), "plpgsql.dylib");            // not family
        write(donor.modules(), "postgis-notes.txt");        // family, wrong suffix
        write(donor.extensions(), "postgis--3.6.4.sql");
        write(donor.extensions(), "address_standardizer.control");
        write(donor.extensions(), "hstore.control");        // not family

        Path modules = root.resolve("target/lib");
        Path extensions = root.resolve("target/extension");
        List<String> copied = PgExtensions.mirror(donor, modules, extensions);

        assertThat(copied).containsExactlyInAnyOrder(
                "address_standardizer-3.dylib",
                "address_standardizer.control",
                "postgis--3.6.4.sql",
                "postgis.control",
                "postgis-3.dylib",
                "postgis_raster-3.dylib");
        assertThat(copied).as("the returned list is sorted, so a log line reads predictably").isSorted();
        assertThat(modules.resolve("plpgsql.dylib")).as("not a PostGIS-family file").doesNotExist();
        assertThat(modules.resolve("postgis-notes.txt")).as("family name, but not a module").doesNotExist();
        assertThat(extensions.resolve("hstore.control")).as("not a PostGIS-family file").doesNotExist();
    }

    /// The guarantee that makes this safe to run on every start: a tree that
    /// already has PostGIS is left untouched. Without it, fcdev would
    /// overwrite a working install with whatever the donor happened to hold —
    /// a different PostGIS build than the one the cluster's objects were
    /// created against.
    ///
    /// Mutant: drop the `Files.exists(target)` skip in `mirrorDirectory` and
    /// both assertions here fail.
    @Test
    void mirrorNeverOverwritesAFileThatIsAlreadyThere(@TempDir Path root) throws IOException {
        Donor donor = donorAt(root.resolve("donor"), true);
        Files.writeString(donor.modules().resolve("postgis-3.dylib"), "donor version");

        Path modules = root.resolve("target/lib");
        Path extensions = root.resolve("target/extension");
        Files.createDirectories(modules);
        Files.writeString(modules.resolve("postgis-3.dylib"), "the one already installed");

        List<String> copied = PgExtensions.mirror(donor, modules, extensions);

        assertThat(copied).as("the pre-existing module is not re-copied").containsExactly("postgis.control");
        assertThat(modules.resolve("postgis-3.dylib")).hasContent("the one already installed");
    }

    @Test
    void mirrorIsIdempotentSoASecondStartCopiesNothing(@TempDir Path root) throws IOException {
        Donor donor = donorAt(root.resolve("donor"), true);
        write(donor.modules(), "postgis-3.dylib");
        Path modules = root.resolve("target/lib");
        Path extensions = root.resolve("target/extension");

        assertThat(PgExtensions.mirror(donor, modules, extensions)).isNotEmpty();
        assertThat(PgExtensions.mirror(donor, modules, extensions))
                .as("second run has nothing left to do").isEmpty();
    }

    @Test
    void mirrorToleratesADonorDirectoryThatDoesNotExist(@TempDir Path root) throws IOException {
        Donor donor = donorAt(root.resolve("donor"), true);
        Donor halfMissing = new Donor(root.resolve("gone"), donor.extensions());

        assertThat(PgExtensions.mirror(halfMissing, root.resolve("t/lib"), root.resolve("t/ext")))
                .containsExactly("postgis.control");
    }

    // ── missingControlFiles ─────────────────────────────────────────────

    /// The startup guard. The cluster is shared with the Go `fcdev`, so it can
    /// legitimately contain extensions this tree cannot serve; naming them at
    /// startup beats a confusing failure inside an unrelated query later.
    @Test
    void missingControlFilesNamesEveryRegisteredExtensionThisTreeCannotServe(@TempDir Path ext) throws IOException {
        Files.createDirectories(ext);
        write(ext, "hstore.control");

        assertThat(PgExtensions.missingControlFiles(Set.of("hstore", "postgis", "postgis_topology"), ext))
                .containsExactly("postgis", "postgis_topology");
    }

    @Test
    void missingControlFilesIsEmptyWhenTheTreeServesEverythingRegistered(@TempDir Path ext) throws IOException {
        Files.createDirectories(ext);
        write(ext, "postgis.control");

        assertThat(PgExtensions.missingControlFiles(Set.of("postgis"), ext)).isEmpty();
    }

    /// `plpgsql` is compiled into the server and ships no control file of the
    /// kind this check looks for, so counting it would fail every single
    /// startup — every FlowCatalyst database has it.
    ///
    /// Mutant: remove the `plpgsql` exclusion and this fails.
    @Test
    void missingControlFilesIgnoresPlpgsqlWhichIsBuiltIntoTheServer(@TempDir Path ext) throws IOException {
        Files.createDirectories(ext);

        assertThat(PgExtensions.missingControlFiles(Set.of("plpgsql"), ext)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /// A donor rooted at `base`: `base/lib` + `base/extension`, with
    /// `postgis.control` present only when `usable`.
    private static Donor donorAt(Path base, boolean usable) throws IOException {
        Path modules = base.resolve("lib");
        Path extensions = base.resolve("extension");
        Files.createDirectories(modules);
        Files.createDirectories(extensions);
        if (usable) {
            write(extensions, "postgis.control");
        }
        return new Donor(modules, extensions);
    }

    private static void write(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), name);
    }
}
