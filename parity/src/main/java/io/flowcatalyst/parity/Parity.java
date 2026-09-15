package io.flowcatalyst.parity;

import io.flowcatalyst.parity.model.Scenario;
import io.flowcatalyst.parity.model.Step;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.openapi.Lockfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The whole pipeline (parity-harness spec §1–§7), shared by [ParityMain] and
/// `ParityRunTest`: build/seed, start both sides, run every scenario against
/// each, normalise + diff + coverage, write the report, stop both sides.
public final class Parity {

    private static final Logger LOG = LoggerFactory.getLogger(Parity.class);

    /// Which pair the harness runs (L1 lane, `docs/java-parity-plan.md`
    /// §3): `GO_JAVA` is the original, default pair; `RUST_JAVA` reuses the
    /// same two-sided [Diff]/[Report] with Rust as the "left" side (the
    /// `go`-named fields throughout — [DiffEntry#go()], [StepResult#goRecord()],
    /// `ReportWriter`'s `go=` column — hold the Rust side's data in that
    /// case; a third, three-way column is a later nicety, not this lane's
    /// job. See `docs/parity/l1.md` for why this was kept mechanical rather
    /// than renamed to `left`/`right` throughout.)
    public enum Sides {
        GO_JAVA, RUST_JAVA
    }

    public record Config(String goSrc, String goBinDir, String rustSrc, String rustBinDir, Sides sides,
                          Path scenariosDir, String onlyGlob, Path reportDir,
                          Path surfaceFile, Path expectedDiffsFile) {
        public Config {
            if (sides == null) sides = Sides.GO_JAVA;
        }

        /// Convenience for the original 7-arg shape (every call site before this lane) — Go-vs-Java, no Rust flags.
        public Config(String goSrc, String goBinDir, Path scenariosDir, String onlyGlob, Path reportDir,
                      Path surfaceFile, Path expectedDiffsFile) {
            this(goSrc, goBinDir, null, null, Sides.GO_JAVA, scenariosDir, onlyGlob, reportDir, surfaceFile, expectedDiffsFile);
        }
    }

    private Parity() {
    }

    public static Report run(Config config) {
        try {
            Files.createDirectories(config.reportDir());
        } catch (IOException e) {
            throw new UncheckedIOException("create " + config.reportDir(), e);
        }
        Path scratch;
        try {
            scratch = Files.createTempDirectory("parity-");
        } catch (IOException e) {
            throw new UncheckedIOException("create scratch dir", e);
        }
        try (EmbeddedPg pg = EmbeddedPg.start()) {
            // The Go/Java seeding pipeline (Seed.build) always runs, even
            // for a Rust-vs-Java comparison: Java's own scenario fixtures
            // (the admin/client/app Seed.ADMIN_EMAIL etc. name) come from
            // Go's `fcdev init`, not from Java's own Seeder — this lane
            // does not touch that pipeline (plan §3 L1: "keep Go/Java
            // seeding untouched"), it just doesn't start a GoSide when
            // Rust is the side under test.
            GoBinaries binaries = GoBinaries.resolve(scratch, config.goSrc(), config.goBinDir());

            Path jwtKeyPath = scratch.resolve("jwt-signing-key.pem");
            Path jwtPublicKeyPath = scratch.resolve("jwt-signing-key.pub.pem");
            RsaKeys.generatePkcs8Pem(jwtKeyPath, jwtPublicKeyPath);
            String appKey = Encryption.generateKey();

            Seed.Result seed = Seed.build(binaries, pg, scratch, jwtKeyPath, appKey, config.reportDir());
            Map<String, String> sideEnv = ParityEnv.baseEnv(jwtKeyPath, appKey, Seed.ADMIN_EMAIL, Seed.ADMIN_PASSWORD);

            Side left;
            Seed.Ids leftIds;
            if (config.sides() == Sides.RUST_JAVA) {
                RustBinaries rustBinaries = RustBinaries.resolve(scratch, config.rustSrc(), config.rustBinDir());
                RustSeed.Result rustSeed = RustSeed.build(rustBinaries, pg, scratch, jwtKeyPath, jwtPublicKeyPath,
                        appKey, config.reportDir());
                Map<String, String> rustEnv = new LinkedHashMap<>(sideEnv);
                rustEnv.put("FC_DATABASE_URL", rustSeed.rustUrl());
                // Pre-L0: Rust doesn't read FC_JWT_SIGNING_KEY_PATH yet
                // (plan §3 L0), so its own two-file env names are set
                // directly here too.
                rustEnv.put("FC_JWT_PRIVATE_KEY_PATH", jwtKeyPath.toString());
                rustEnv.put("FC_JWT_PUBLIC_KEY_PATH", jwtPublicKeyPath.toString());
                // Rust's webauthn-rs rejects a bare-IP rp_id against a
                // non-HTTPS origin ("rp_id is not an effective_domain of
                // rp_origin") — Go's/Java's webauthn stacks tolerate
                // ParityEnv.baseEnv()'s FC_WEBAUTHN_RP_ID=127.0.0.1, Rust's
                // does not. "localhost" host here (matching
                // SubprocessSide.start's own baseUrl) plus this RP ID
                // override keep origin/RP-id consistent for the Rust side
                // only — same fix scripts/spec-diff.sh (Rust worktree)
                // already carries for its own standalone fc-server boot.
                rustEnv.put("FC_WEBAUTHN_RP_ID", "localhost");
                left = SubprocessSide.start("rust", "localhost", rustBinaries.fcServer(), rustEnv, config.reportDir().resolve("rust.log"));
                leftIds = rustSeed.ids();
            } else {
                Map<String, String> goEnv = new LinkedHashMap<>(sideEnv);
                goEnv.put("FC_DATABASE_URL", seed.goUrl());
                left = SubprocessSide.start("go", binaries.fcServer(), goEnv, config.reportDir().resolve("go.log"));
                leftIds = seed.ids();
            }

            JavaSide javaSide;
            try {
                javaSide = JavaSide.start(seed.javaUrl(), sideEnv, config.reportDir().resolve("java.log"));
            } catch (RuntimeException e) {
                left.stop();
                throw e;
            }

            try {
                return runScenarios(config, leftIds, seed.ids(), left, javaSide);
            } finally {
                javaSide.stop();
                left.stop();
                if (left instanceof SubprocessSide sub) {
                    LOG.info("{} fc-server for the run: start {} stop {}", sub.label(), sub.startDuration(), sub.stopDuration());
                }
            }
        } finally {
            deleteRecursively(scratch);
        }
    }

    /// @param leftIds  the "left" side's own client/app/admin ids (Go's, from `seed`, or Rust's, from `seed_rust`)
    /// @param javaIds  Java's own ids — always `seed`'s (Seed.build's pipeline, untouched by this lane)
    private static Report runScenarios(Config config, Seed.Ids leftIds, Seed.Ids javaIds, Side left, JavaSide javaSide) {
        List<ScenarioLoader.Loaded> scenarios = ScenarioLoader.load(config.scenariosDir(), config.onlyGlob());
        String run = randomToken();
        ExpectedDiffs expected = config.expectedDiffsFile() != null
                ? ExpectedDiffs.load(config.expectedDiffsFile())
                : ExpectedDiffs.empty();

        List<ScenarioResult> results = new ArrayList<>();
        List<Runner.RequestedRoute> allRequested = new ArrayList<>();
        Map<String, String> goRunLabels = new LinkedHashMap<>();
        Map<String, String> javaRunLabels = new LinkedHashMap<>();

        for (ScenarioLoader.Loaded loaded : scenarios) {
            Scenario scenario = loaded.scenario();
            LOG.info("running scenario {}", scenario.name());
            Vars goVars = new Vars(Seed.ADMIN_EMAIL, Seed.ADMIN_PASSWORD, run, leftIds.clientId(), leftIds.appId(), leftIds.adminId(), goRunLabels);
            Vars javaVars = new Vars(Seed.ADMIN_EMAIL, Seed.ADMIN_PASSWORD, run, javaIds.clientId(), javaIds.appId(), javaIds.adminId(), javaRunLabels);

            Runner.RunResult goRun = new Runner(left.baseUrl()).run(scenario, goVars);
            Runner.RunResult javaRun = new Runner(javaSide.baseUrl()).run(scenario, javaVars);
            allRequested.addAll(goRun.requested());
            allRequested.addAll(javaRun.requested());

            List<StepResult> stepResults = new ArrayList<>();
            for (int i = 0; i < scenario.steps().size(); i++) {
                Step step = scenario.steps().get(i);
                stepResults.add(compareStep(scenario, step, goRun.steps().get(i), javaRun.steps().get(i),
                        goVars, javaVars, left.baseUrl(), javaSide.baseUrl(), expected));
            }

            List<Lockfile.Operation> lockfileOps = Lockfile.load(Json.MAPPER).operations();
            List<Coverage.Route> lockfileRoutes = lockfileOps.stream()
                    .map(op -> new Coverage.Route(op.method(), op.path(), op.operationId())).toList();
            Set<String> unmet = new LinkedHashSet<>();
            unmet.addAll(Coverage.unmetClaims(scenario.covers(), lockfileRoutes, goRun.requested()));
            unmet.addAll(Coverage.unmetClaims(scenario.covers(), lockfileRoutes, javaRun.requested()));

            results.add(new ScenarioResult(scenario.name(), stepResults, List.copyOf(unmet)));
        }

        Lockfile lockfile = Lockfile.load(Json.MAPPER);
        List<Coverage.Route> lockfileRoutes = Coverage.lockfileRoutes(lockfile);
        List<Coverage.Route> surfaceRoutes = config.surfaceFile() != null
                ? Coverage.loadSurface(config.surfaceFile())
                : List.of();
        Coverage.Result coverage = Coverage.compute(lockfileRoutes, surfaceRoutes, allRequested);

        // Stale allow-list entries are only meaningful on a full run: under a
        // scenario filter the entries for the scenarios not run cannot match.
        List<ExpectedDiff> stale = config.onlyGlob() == null || config.onlyGlob().isBlank() ? expected.stale() : List.of();
        if (!stale.isEmpty() || config.onlyGlob() != null) {
            LOG.info("stale allow-list check: {}", config.onlyGlob() == null ? stale.size() + " stale" : "skipped (PARITY_ONLY filter active)");
        }
        Report report = new Report(results, coverage, stale);
        ReportWriter.writeJson(report, config.reportDir().resolve("report.json"));
        ReportWriter.writeMarkdown(report, config.reportDir().resolve("report.md"));
        return report;
    }

    private static StepResult compareStep(Scenario scenario, Step step, Runner.StepOutcome goOutcome,
                                           Runner.StepOutcome javaOutcome, Vars goVars, Vars javaVars,
                                           String goBaseUrl, String javaBaseUrl, ExpectedDiffs expected) {
        if (goOutcome instanceof Runner.StepOutcome.Failed || javaOutcome instanceof Runner.StepOutcome.Failed) {
            StepRecord goRecord = recordOf(goOutcome);
            StepRecord javaRecord = recordOf(javaOutcome);
            String error = errorMessageOf(goOutcome, javaOutcome);
            return new StepResult(step.id(), StepStatus.ERROR, List.of(), List.of(), goRecord, javaRecord, error);
        }

        var goRan = (Runner.StepOutcome.Ran) goOutcome;
        var javaRan = (Runner.StepOutcome.Ran) javaOutcome;
        Normalised goNorm = Normaliser.normalise(goRan.record(), goVars, goBaseUrl, step);
        Normalised javaNorm = Normaliser.normalise(javaRan.record(), javaVars, javaBaseUrl, step);
        List<DiffEntry> diffs = Diff.compare(goNorm, javaNorm);

        List<DiffEntry> unaccepted = new ArrayList<>();
        for (DiffEntry d : diffs) {
            if (!expected.accepts(scenario.name(), step.id(), d.pointer())) unaccepted.add(d);
        }
        StepStatus status = diffs.isEmpty() ? StepStatus.OK : unaccepted.isEmpty() ? StepStatus.ACCEPTED : StepStatus.DIFF;
        StepRecord goRecord = status == StepStatus.OK ? null : goRan.record();
        StepRecord javaRecord = status == StepStatus.OK ? null : javaRan.record();
        return new StepResult(step.id(), status, diffs, unaccepted, goRecord, javaRecord, null);
    }

    private static StepRecord recordOf(Runner.StepOutcome outcome) {
        return switch (outcome) {
            case Runner.StepOutcome.Ran ran -> ran.record();
            case Runner.StepOutcome.Failed failed -> failed.record();
        };
    }

    private static String errorMessageOf(Runner.StepOutcome go, Runner.StepOutcome javaSide) {
        String goMessage = go instanceof Runner.StepOutcome.Failed(var _, var message, var _) ? message : null;
        String javaMessage = javaSide instanceof Runner.StepOutcome.Failed(var _, var message, var _) ? message : null;
        if (goMessage != null && javaMessage != null) return "go: " + goMessage + " | java: " + javaMessage;
        if (goMessage != null) return "go: " + goMessage;
        return "java: " + javaMessage;
    }

    private static String randomToken() {
        byte[] bytes = new byte[6];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(12);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("could not delete {}", p, e);
                }
            });
        } catch (IOException e) {
            LOG.warn("could not walk {} for cleanup", dir, e);
        }
    }
}
