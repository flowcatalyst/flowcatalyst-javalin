package io.flowcatalyst.server;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvReaderTest {

    private static EnvReader of(String... kv) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return new EnvReader(m);
    }

    @Test
    void getTreatsUnsetAsEmpty() {
        var e = of("SET", "value", "EMPTY", "");
        assertThat(e.get("SET")).isEqualTo("value");
        assertThat(e.get("EMPTY")).isEmpty();
        assertThat(e.get("UNSET")).isEmpty();
        assertThat(e.lookup("EMPTY")).contains("");
        assertThat(e.lookup("UNSET")).isEmpty();
    }

    @Test
    void orFallsBackOnEmptyAndUnset() {
        var e = of("SET", "value", "EMPTY", "");
        assertThat(e.or("SET", "def")).isEqualTo("value");
        assertThat(e.or("EMPTY", "def")).isEqualTo("def");
        assertThat(e.or("UNSET", "def")).isEqualTo("def");
    }

    @Test
    void firstSetHonoursPriorityAndSkipsEmptyNames() {
        var e = of("B", "b", "C", "c", "EMPTY", "");
        assertThat(e.firstSet("A", "B", "C")).contains("b");
        assertThat(e.firstSet("EMPTY", "", "C")).contains("c");
        assertThat(e.firstSet("A", "EMPTY")).isEmpty();
        assertThat(e.firstSet("A").orElse("def")).isEqualTo("def");
    }

    @Test
    void integerParsesSignedDecimalAndFallsBackOnGarbage() {
        var e = of("INT", "42", "NEG", "-7", "PLUS", "+3", "GARBAGE", "nope", "SPACED", " 5", "EMPTY", "");
        assertThat(e.integer("INT", 1)).isEqualTo(42);
        assertThat(e.integer("NEG", 1)).isEqualTo(-7);
        assertThat(e.integer("PLUS", 1)).isEqualTo(3);
        assertThat(e.integer("GARBAGE", 1)).isEqualTo(1);
        assertThat(e.integer("SPACED", 1)).isEqualTo(1);
        assertThat(e.integer("EMPTY", 1)).isEqualTo(1);
        assertThat(e.integer("UNSET", 1)).isEqualTo(1);
    }

    @Test
    void integerAliasFallsThroughOnUnparseablePrimary() {
        var e = of("P", "x", "A", "9");
        assertThat(e.integerAlias("P", "A", 1)).isEqualTo(9);
        assertThat(of("P", "4", "A", "9").integerAlias("P", "A", 1)).isEqualTo(4);
        assertThat(of("A", "9").integerAlias("P", "A", 1)).isEqualTo(9);
        assertThat(of().integerAlias("P", "A", 1)).isEqualTo(1);
        assertThat(of("A", "bad").integerAlias("P", "A", 1)).isEqualTo(1);
    }

    @Test
    void boolVocabulary() {
        for (var t : new String[]{"1", "true", "TRUE", "True", "yes", "YES", "on", "On", " true ", "\ton\n"}) {
            assertThat(of("B", t).bool("B", false)).as(t).isTrue();
        }
        for (var f : new String[]{"0", "false", "FALSE", "no", "No", "off", "OFF", " off "}) {
            assertThat(of("B", f).bool("B", true)).as(f).isFalse();
        }
        for (var junk : new String[]{"", "maybe", "2", "enabled", "y", "t"}) {
            assertThat(of("B", junk).bool("B", true)).as(junk).isTrue();
            assertThat(of("B", junk).bool("B", false)).as(junk).isFalse();
        }
        assertThat(of().bool("B", true)).isTrue();
    }

    @Test
    void boolAliasPrimaryDecidesEvenWhenUnparseable() {
        assertThat(of("P", "garbage", "A", "true").boolAlias("P", "A", false))
                .as("set-but-unparseable primary yields the default, alias not consulted").isFalse();
        assertThat(of("A", "true").boolAlias("P", "A", false)).isTrue();
        assertThat(of("P", "false", "A", "true").boolAlias("P", "A", true)).isFalse();
        assertThat(of("P", "", "A", "yes").boolAlias("P", "A", false)).as("empty primary falls through").isTrue();
        assertThat(of().boolAlias("P", "A", true)).isTrue();
    }

    @Test
    void uint32Semantics() {
        assertThat(of("U", "300").uint32("U", 9)).isEqualTo(300);
        assertThat(of("U", "-1").uint32("U", 9)).isEqualTo(9);
        assertThat(of("U", "+1").uint32("U", 9)).isEqualTo(9);
        assertThat(of("U", "4294967295").uint32("U", 9)).isEqualTo(4294967295L);
        assertThat(of("U", "4294967296").uint32("U", 9)).isEqualTo(9);
        assertThat(of().uint32("U", 9)).isEqualTo(9);
    }

    @Test
    void uintOkForm() {
        assertThat(of("U", "15").uint("U")).hasValue(15);
        assertThat(of("U", "x").uint("U")).isEmpty();
        assertThat(of("U", "-3").uint("U")).isEmpty();
        assertThat(of("U", "").uint("U")).isEmpty();
        assertThat(of().uint("U")).isEmpty();
        var big = of("U", "18446744073709551615").uint("U");
        assertThat(big).isPresent();
        assertThat(Long.toUnsignedString(big.getAsLong())).isEqualTo("18446744073709551615");
        assertThat(of("U", "18446744073709551616").uint("U")).isEmpty();
    }

    @Test
    void mapIsCopiedDefensively() {
        var m = new HashMap<>(Map.of("A", "1"));
        var e = new EnvReader(m);
        m.put("A", "2");
        assertThat(e.get("A")).isEqualTo("1");
    }
}
