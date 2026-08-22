package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DotEnvTest {

    @Test
    void parsesKeysValuesCommentsExportsAndQuotes() {
        var parsed = DotEnv.parse("""
                # a comment
                FC_API_PORT=3000

                export FC_DEFAULT_BROKER=postgres
                  DB_HOST = db.internal   \s
                QUOTED="hello world"
                SINGLE='it''s'
                HASH_INSIDE=a#b
                EMPTY=
                EQUALS_IN_VALUE=a=b=c
                LONELY_QUOTE="unterminated
                =no-key
                no-equals-sign
                MIXED="x'
                DUP=first
                DUP=second
                """);

        assertThat(parsed).containsExactly(
                Map.entry("FC_API_PORT", "3000"),
                Map.entry("FC_DEFAULT_BROKER", "postgres"),
                Map.entry("DB_HOST", "db.internal"),
                Map.entry("QUOTED", "hello world"),
                Map.entry("SINGLE", "it''s"),
                Map.entry("HASH_INSIDE", "a#b"),
                Map.entry("EMPTY", ""),
                Map.entry("EQUALS_IN_VALUE", "a=b=c"),
                Map.entry("LONELY_QUOTE", "\"unterminated"),
                Map.entry("MIXED", "\"x'"),
                Map.entry("DUP", "second"));
    }

    @Test
    void missingFileIsNoOp(@TempDir Path dir) {
        var env = Map.of("A", "1");
        assertThat(DotEnv.parse(dir.resolve("nope.env"))).isEmpty();
        assertThat(DotEnv.apply(dir.resolve("nope.env"), env)).containsExactlyInAnyOrderEntriesOf(env);
    }

    @Test
    void neverOverridesExistingEnvEvenWhenEmpty(@TempDir Path dir) throws IOException {
        var file = dir.resolve(".env");
        Files.writeString(file, """
                FC_API_PORT=3000
                FC_DEFAULT_BROKER=postgres
                EXPLICITLY_EMPTY=from-file
                NEW_KEY='new'
                """);
        var real = new HashMap<String, String>();
        real.put("FC_API_PORT", "9999");
        real.put("EXPLICITLY_EMPTY", "");

        var merged = DotEnv.apply(file, real);

        assertThat(merged)
                .containsEntry("FC_API_PORT", "9999")
                .containsEntry("EXPLICITLY_EMPTY", "")
                .containsEntry("FC_DEFAULT_BROKER", "postgres")
                .containsEntry("NEW_KEY", "new")
                .hasSize(4);
        assertThat(real).as("input map untouched").hasSize(2);

        var env = Env.load(merged);
        assertThat(env.apiPort()).isEqualTo(9999);
        assertThat(env.defaultBroker()).isEqualTo("postgres");
    }
}
