package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.DurationConverter;
import io.flowcatalyst.platform.function.api.FunctionApi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/// `fn watch <dir> [--jar <glob>] [--manifest manifest.json]` (spec §2, §4
/// E8): a debounced (500 ms) file watch on `<dir>`; every (coalesced) change
/// runs a deploy cycle (publish + promote, [Publisher]/[Promoter] — the same
/// path `fn deploy` uses, including its `VERSION_DIGEST_EXISTS` recovery).
/// One line per cycle; a failing cycle prints its error and the watch
/// continues — never exits. Ctrl-C (an interrupt while blocked on the watch
/// service) exits 0. `--jar`'s glob is resolved fresh at EVERY cycle (default
/// `*.jar`); it must match exactly one file, else that cycle is skipped with
/// a message and the watch keeps going. The manifest is assumed to live in
/// `<dir>` too (`--manifest`'s default and every example in the spec are a
/// bare filename) — see the final report for this scope note.
@Command(name = "watch", description = "Watch a directory and deploy on every change", sortOptions = false)
public final class WatchCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", paramLabel = "<dir>", description = "the directory to watch")
    String dir;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
    String address;

    @Option(names = "--jar", paramLabel = "<glob>", defaultValue = "*.jar",
            description = "glob for the jar within <dir> — must match exactly one file at deploy time (default: ${DEFAULT-VALUE})")
    String jarGlob;

    @Option(names = "--manifest", paramLabel = "<file>", defaultValue = "manifest.json",
            description = "the manifest file, relative to <dir> unless absolute (default: ${DEFAULT-VALUE})")
    String manifestName;

    @Option(names = "--wait", paramLabel = "<duration>", defaultValue = "60s", converter = DurationConverter.class,
            description = "how long each cycle's promote waits for READY (default: ${DEFAULT-VALUE})")
    Duration wait;

    @Option(names = "--client", paramLabel = "<id>", description = "owning client id, when creating a client-owned function")
    String client;

    @Option(names = "--no-create", description = "fail a cycle instead of creating the function when its address is unknown")
    boolean noCreate;

    @Mixin
    AddressOptions addressOpts;

    @Spec
    CommandSpec spec;

    /// Test seams: a short debounce so a test window is bounded (no real
    /// 500 ms+ waits), an injectable clock for the "live in N.Ns" line and
    /// the deploy's own promote-wait polling, and a cycle cap so a test can
    /// let the loop run to completion instead of interrupting a thread.
    Duration debounce = Duration.ofMillis(500);
    LongSupplier clockMillis = System::currentTimeMillis;
    LongConsumer sleepMillis = ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };
    int maxCycles = Integer.MAX_VALUE;

    @Override
    public Integer call() {
        FnCommand root = FnCommand.of(spec);
        return FnCommand.runSafely(spec, () -> {
            String addr = addressOpts.resolve(address);
            Path dirPath = Path.of(dir);
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                dirPath.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                runLoop(root, ws, dirPath, addr);
            }
            return 0;
        });
    }

    /// The loop: one deploy cycle up front, then one more per debounced
    /// change, until [#awaitDebouncedChange] says the watch ended (Ctrl-C)
    /// or [#maxCycles] is reached (test seam only — production never sets
    /// it, so this is otherwise unbounded, matching "watch forever").
    void runLoop(FnCommand root, WatchService ws, Path dir, String address) {
        int cycles = 0;
        deployCycle(root, dir, address);
        cycles++;
        while (cycles < maxCycles) {
            if (!awaitDebouncedChange(ws)) {
                return;
            }
            deployCycle(root, dir, address);
            cycles++;
        }
    }

    /// Blocks for the first event, then keeps draining/resetting the key
    /// until [#debounce] has passed with no further event — coalescing a
    /// burst (e.g. two quick writes from one save) into ONE signal.
    ///
    /// @return `false` when the watch ended (an interrupt — Ctrl-C — or the
    ///         service was closed): the loop must stop, never deploy again
    boolean awaitDebouncedChange(WatchService ws) {
        WatchKey key;
        try {
            key = ws.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ClosedWatchServiceException e) {
            return false;
        }
        key.pollEvents();
        long quietUntil = clockMillis.getAsLong() + debounce.toMillis();
        while (true) {
            long remaining = quietUntil - clockMillis.getAsLong();
            if (remaining <= 0) {
                break;
            }
            WatchKey more;
            try {
                more = ws.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                key.reset();
                return false;
            }
            if (more != null) {
                more.pollEvents();
                if (more != key) {
                    more.reset();
                }
                quietUntil = clockMillis.getAsLong() + debounce.toMillis();
            }
        }
        key.reset();
        return true;
    }

    /// One publish+promote cycle — never throws: every failure is printed
    /// (one line) and the watch continues (spec §4 E8).
    void deployCycle(FnCommand root, Path dir, String address) {
        var out = spec.commandLine().getOut();
        long start = clockMillis.getAsLong();
        try {
            Path jar = resolveJar(dir);
            Path manifestFile = resolveManifest(dir);
            var opts = new Publisher.Options(jar.toString(), manifestFile.toString(), null, null, client, noCreate);

            int version;
            try {
                version = Publisher.publish(spec, root, address, opts).version();
            } catch (FnClientException e) {
                Object existing = "VERSION_DIGEST_EXISTS".equals(e.code()) ? e.details().get("version") : null;
                if (!(existing instanceof Number n)) {
                    throw e;
                }
                version = n.intValue();
            }

            FunctionApi.PromoteResponse promoted;
            try {
                promoted = Promoter.promote(spec, root, root.client(), address, version, wait, clockMillis, sleepMillis);
            } catch (FnClientException e) {
                // Same reasoning as DeployCommand: the recovered version can
                // already be live (an unchanged file saved again) — that is
                // success, not a cycle failure.
                if (!"ALIAS_UNCHANGED".equals(e.code())) {
                    throw e;
                }
                double already = (clockMillis.getAsLong() - start) / 1000.0;
                out.printf("v%d already live (%.1f s)%n", version, already);
                return;
            }
            if (promoted == null) {
                out.printf("v%d: timed out waiting for READY%n", version);
                return;
            }
            double seconds = (clockMillis.getAsLong() - start) / 1000.0;
            out.printf("v%d live in %.1f s%n", promoted.version(), seconds);
        } catch (SkipCycle skip) {
            out.println(skip.getMessage());
        } catch (FnClientException e) {
            out.println(e.oneLine());
        } catch (IOException e) {
            out.println(e.getMessage());
        } catch (RuntimeException e) {
            out.println(e.getMessage());
        }
    }

    private Path resolveJar(Path dir) throws IOException {
        PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + jarGlob);
        List<Path> matches;
        try (var stream = Files.list(dir)) {
            matches = stream.filter(p -> matcher.matches(p.getFileName())).sorted().toList();
        }
        if (matches.size() != 1) {
            throw new SkipCycle("jar glob \"" + jarGlob + "\" matched " + matches.size() + " file(s) in " + dir
                    + " (need exactly one) — waiting for the next change");
        }
        return matches.get(0);
    }

    private Path resolveManifest(Path dir) {
        Path candidate = Path.of(manifestName);
        return candidate.isAbsolute() ? candidate : dir.resolve(manifestName);
    }

    /// A cycle that never reached the network — printed and skipped, exactly
    /// like any other cycle failure.
    private static final class SkipCycle extends RuntimeException {
        SkipCycle(String message) {
            super(message);
        }
    }
}
