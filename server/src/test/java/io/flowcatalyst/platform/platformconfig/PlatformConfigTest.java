package io.flowcatalyst.platform.platformconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// The aggregates' pure rules (spec §1–2): coordinate → scope derivation,
/// the defaults of a fresh value / grant, the `set` and `grant` transitions
/// and the lenient enum reads — no database involved.
class PlatformConfigTest {

    private static final ConfigCoordinate GLOBAL = ConfigCoordinate.global("platform", "login", "theme");

    // ── Coordinate ─────────────────────────────────────────────────────────

    @Test
    void coordinateWithoutClientIsGlobal() {
        assertThat(GLOBAL.scope()).isEqualTo(ConfigScope.GLOBAL);
        assertThat(GLOBAL.clientId()).isNull();
        assertThat(ConfigCoordinate.of("platform", "login", "theme", null)).isEqualTo(GLOBAL);
        assertThat(GLOBAL.path()).isEqualTo("platform/login/theme");
    }

    @Test
    void coordinateWithClientIsClientScoped() {
        var c = ConfigCoordinate.of("platform", "smtp", "host", "cli_123");
        assertThat(c.scope()).isEqualTo(ConfigScope.CLIENT);
        assertThat(c.clientId()).isEqualTo("cli_123");
    }

    // ── Config value ───────────────────────────────────────────────────────

    @Test
    void createIsPlainAtTheCoordinateWithNoDescription() {
        var c = PlatformConfig.create(GLOBAL, "{}");
        assertThat(c.id()).startsWith("pcf_").hasSize(17);
        assertThat(c.applicationCode()).isEqualTo("platform");
        assertThat(c.section()).isEqualTo("login");
        assertThat(c.property()).isEqualTo("theme");
        assertThat(c.scope()).isEqualTo(ConfigScope.GLOBAL);
        assertThat(c.clientId()).isNull();
        assertThat(c.valueType()).isEqualTo(PlatformConfig.DEFAULT_VALUE_TYPE).isEqualTo(ConfigValueType.PLAIN);
        assertThat(c.value()).isEqualTo("{}");
        assertThat(c.description()).isNull();
        assertThat(c.isSecret()).isFalse();
        assertThat(c.coordinate()).isEqualTo(GLOBAL);
        assertThat(c.createdAt()).isEqualTo(c.updatedAt());
    }

    @Test
    void createForAClientDerivesClientScope() {
        var c = PlatformConfig.create(ConfigCoordinate.of("app", "s", "p", "cli_1"), "v");
        assertThat(c.scope()).isEqualTo(ConfigScope.CLIENT);
        assertThat(c.clientId()).isEqualTo("cli_1");
        assertThat(c.coordinate()).isEqualTo(ConfigCoordinate.of("app", "s", "p", "cli_1"));
    }

    @Test
    void setReplacesValueAndDescriptionAndKeepsTheTypeWhenNoneIsGiven() {
        var first = PlatformConfig.create(GLOBAL, "a").set("a", ConfigValueType.SECRET, "the secret");
        assertThat(first.valueType()).isEqualTo(ConfigValueType.SECRET);
        assertThat(first.description()).isEqualTo("the secret");

        var second = first.set("b", null, null);
        assertThat(second.id()).as("same aggregate").isEqualTo(first.id());
        assertThat(second.value()).isEqualTo("b");
        assertThat(second.valueType()).as("null keeps the current type").isEqualTo(ConfigValueType.SECRET);
        assertThat(second.description()).as("null clears the description").isNull();
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.coordinate()).isEqualTo(GLOBAL);
    }

    @Test
    void setAcceptsAnEmptyValue() {
        assertThat(PlatformConfig.create(GLOBAL, "x").set("", null, null).value()).isEmpty();
    }

    @Test
    void withValueOnlyChangesTheValue() {
        var c = PlatformConfig.create(GLOBAL, "hunter2").set("hunter2", ConfigValueType.SECRET, null);
        var masked = c.withValue("***");
        assertThat(masked.value()).isEqualTo("***");
        assertThat(masked.valueType()).isEqualTo(ConfigValueType.SECRET);
        assertThat(masked.updatedAt()).isEqualTo(c.updatedAt());
    }

    // ── Access grant ───────────────────────────────────────────────────────

    @Test
    void createIsAReadOnlyGrant() {
        var a = ConfigAccess.create("app", "auditor");
        assertThat(a.id()).startsWith("cfa_").hasSize(17);
        assertThat(a.applicationCode()).isEqualTo("app");
        assertThat(a.roleCode()).isEqualTo("auditor");
        assertThat(a.canRead()).isTrue();
        assertThat(a.canWrite()).isFalse();
    }

    @Test
    void grantEscalatesAndDeEscalatesWriteButAlwaysKeepsRead() {
        var a = ConfigAccess.create("app", "ops");
        var writer = a.grant(true);
        assertThat(writer.id()).isEqualTo(a.id());
        assertThat(writer.canRead()).isTrue();
        assertThat(writer.canWrite()).isTrue();
        var reader = writer.grant(false);
        assertThat(reader.canRead()).isTrue();
        assertThat(reader.canWrite()).isFalse();
        assertThat(reader.createdAt()).isEqualTo(a.createdAt());
    }

    // ── Lenient enum reads ─────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"GLOBAL,GLOBAL", "CLIENT,CLIENT", "banana,GLOBAL"})
    void scopeParsesLeniently(String stored, ConfigScope expected) {
        assertThat(ConfigScope.parse(stored)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"secret", "BANANA"})
    void unknownValueTypeReadsAsPlain(String stored) {
        assertThat(ConfigValueType.parse(stored)).isEqualTo(ConfigValueType.PLAIN);
    }

    @Test
    void secretValueTypeIsRecognised() {
        assertThat(ConfigValueType.parse("SECRET")).isEqualTo(ConfigValueType.SECRET);
        assertThat(ConfigScope.parse(null)).isEqualTo(ConfigScope.GLOBAL);
    }
}
