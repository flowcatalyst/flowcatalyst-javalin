package io.flowcatalyst.parity;

import io.flowcatalyst.parity.model.Scenario;
import io.flowcatalyst.platform.shared.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// Loads `parity/scenarios/<group>/<name>.json` files (parity-harness spec
/// §3) in a stable order, optionally filtered by `--only <glob>` against the
/// path relative to `scenariosDir` (e.g. `smoke/*.json`).
public final class ScenarioLoader {

    private ScenarioLoader() {
    }

    public record Loaded(String relativePath, Scenario scenario) {
    }

    public static List<Loaded> load(Path scenariosDir, String onlyGlob) {
        if (!Files.isDirectory(scenariosDir)) {
            throw new IllegalArgumentException("no such scenarios directory: " + scenariosDir);
        }
        PathMatcher matcher = onlyGlob == null || onlyGlob.isBlank()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + onlyGlob);

        List<Path> files;
        try (var stream = Files.walk(scenariosDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("walk " + scenariosDir, e);
        }

        List<Loaded> out = new ArrayList<>();
        for (Path file : files) {
            String relative = scenariosDir.relativize(file).toString().replace('\\', '/');
            if (matcher != null && !matcher.matches(Path.of(relative))) continue;
            try {
                Scenario scenario = Json.MAPPER.readValue(Files.readAllBytes(file), Scenario.class);
                out.add(new Loaded(relative, scenario));
            } catch (IOException e) {
                throw new UncheckedIOException("read " + file, e);
            }
        }
        return out.stream().sorted(Comparator.comparing(Loaded::relativePath)).toList();
    }
}
