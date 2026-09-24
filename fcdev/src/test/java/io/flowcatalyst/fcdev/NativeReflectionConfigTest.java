package io.flowcatalyst.fcdev;

import io.flowcatalyst.platform.shared.json.Json;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// The native fcdev reads every picocli option by reflection, and GraalVM only allows the fields
/// the generated `reflect-config.json` names. That file is written by the picocli-codegen
/// processor, which must run on every compile: when it ran only under `-Pnative`, a native package
/// could reuse up-to-date classes with an OLD config and crash on the first new option
/// (`MissingReflectionRegistrationError` on `StartOptions.noFunctions`, 2026-09-24). This walks
/// the real command tree and checks every option and parameter field is registered.
class NativeReflectionConfigTest {

    @Test
    void everyPicocliFieldOfTheCommandTreeIsInTheGeneratedReflectionConfig() throws Exception {
        Map<String, Set<String>> registered = new HashMap<>();
        try (InputStream in = getClass().getResourceAsStream(
                "/META-INF/native-image/picocli-generated/fcdev/reflect-config.json")) {
            assertThat(in).as("the picocli processor ran in this (non-native) build").isNotNull();
            for (JsonNode entry : Json.MAPPER.readTree(in)) {
                Set<String> fields = new HashSet<>();
                for (JsonNode f : entry.path("fields")) fields.add(f.path("name").asString());
                registered.put(entry.path("name").asString(), fields);
            }
        }

        List<String> missing = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        walk(new CommandLine(new FcDev()), registered, missing, checked);
        assertThat(checked).as("the walk reaches the options that crashed before")
                .contains("io.flowcatalyst.fcdev.StartOptions.noFunctions")
                .hasSizeGreaterThan(40);
        assertThat(missing).isEmpty();
    }

    private static void walk(CommandLine cmd, Map<String, Set<String>> registered, List<String> missing,
                             List<String> checked) {
        for (CommandLine.Model.ArgSpec arg : cmd.getCommandSpec().args()) {
            if (arg.userObject() instanceof Field field) {
                checked.add(field.getDeclaringClass().getName() + "." + field.getName());
                Set<String> fields = registered.getOrDefault(field.getDeclaringClass().getName(), Set.of());
                if (!fields.contains(field.getName())) {
                    missing.add(field.getDeclaringClass().getName() + "." + field.getName());
                }
            }
        }
        for (CommandLine sub : cmd.getSubcommands().values()) {
            walk(sub, registered, missing, checked);
        }
    }
}
