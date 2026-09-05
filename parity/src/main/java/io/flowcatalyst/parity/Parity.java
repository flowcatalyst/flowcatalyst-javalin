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

    public record Config(String goSrc, String goBinDir, Path scenariosDir, String onlyGlob, Path reportDir,
                          Path surfaceFile, Path expectedDiffsFile) {
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
            GoBinaries binaries = GoBinaries.resolve(scratch, config.goSrc(), config.goBinDir());

            Path jwtKeyPath = scratch.resolve("jwt-signing-key.pem");
            RsaKeys.generatePkcs8Pem(jwtKeyPath);
            String appKey = Encryption.generateKey();

            Seed.Result seed = Seed.build(binaries, pg, scratch, jwtKeyPath, appKey, config.reportDir());
            Map<String, String> sideEnv = ParityEnv.baseEnv(jwtKeyPath, appKey, Seed.ADMIN_EMAIL, Seed.ADMIN_PASSWORD);

            Map<String, String> goEnv = new LinkedHashMap<>(sideEnv);
            goEnv.put("FC_DATABASE_URL", seed.goUrl());
            GoSide go = GoSide.start(binaries.fcServer(), goEnv, config.reportDir().resolve("go.log"));

            JavaSide javaSide;
            try {
                javaSide = JavaSide.start(seed.javaUrl(), sideEnv, config.reportDir().resolve("java.log"));
            } catch (RuntimeException e) {
                go.stop();
                throw e;
            }

            try {
                return runScenarios(config, seed, go, javaSide);
            } finally {
                javaSide.stop();
                go.stop();
                LOG.info("Go fc-server for the run: start {} stop {}", go.startDuration(), go.stopDuration());
            }
        } finally {
            deleteRecursively(scratch);
        }
    }

    private static Report runScenarios(Config config, Seed.Result seed, GoSide go, JavaSide javaSide) {
        List<ScenarioLoader.Loaded> scenarios = ScenarioLoader.load(config.scenariosDir(), config.onlyGlob());
        String run = randomToken();
        ExpectedDiffs expected = config.expectedDiffsFile() != null
                ? ExpectedDiffs.load(config.expectedDiffsFile())
                : ExpectedDiffs.empty();

        List<ScenarioResult> results = new ArrayList<>();
        List<Runner.RequestedRoute> allRequested = new ArrayList<>();

        for (ScenarioLoader.Loaded loaded : scenarios) {
            Scenario scenario = loaded.scenario();
            LOG.info("running scenario {}", scenario.name());
            Vars goVars = new Vars(Seed.ADMIN_EMAIL, Seed.ADMIN_PASSWORD, run, seed.ids().clientId(), seed.ids().appId(), seed.ids().adminId());
            Vars javaVars = new Vars(Seed.ADMIN_EMAIL, Seed.ADMIN_PASSWORD, run, seed.ids().clientId(), seed.ids().appId(), seed.ids().adminId());

            Runner.RunResult goRun = new Runner(go.baseUrl()).run(scenario, goVars);
            Runner.RunResult javaRun = new Runner(javaSide.baseUrl()).run(scenario, javaVars);
            allRequested.addAll(goRun.requested());
            allRequested.addAll(javaRun.requested());

            List<StepResult> stepResults = new ArrayList<>();
            for (int i = 0; i < scenario.steps().size(); i++) {
                Step step = scenario.steps().get(i);
                stepResults.add(compareStep(scenario, step, goRun.steps().get(i), javaRun.steps().get(i),
                        goVars, javaVars, go.baseUrl(), javaSide.baseUrl(), expected));
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
