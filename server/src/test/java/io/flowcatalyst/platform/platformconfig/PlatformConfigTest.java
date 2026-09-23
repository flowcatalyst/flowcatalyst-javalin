package io.flowcatalyst.platform.platformconfig;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The config-value aggregate's pure rules (spec §1–2): coordinate → scope
/// derivation, the defaults of a fresh value, the `set` transition and the
/// lenient enum reads — no database involved. The access-grant aggregate
/// (`ConfigAccess`) is withdrawn (`docs/spec/config-permissions.md` §A.3).
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

    /// The whole derivation rule (spec §1.1): scope is a function of the
    /// client id's presence, never chosen on its own.
    @ParameterizedTest(name = "clientId={0} → {1}")
    @CsvSource(nullValues = "<null>", value = {
            "<null>,  GLOBAL",
            "cli_123, CLIENT"})
    void scopeIsDerivedFromThePresenceOfAClientId(String clientId, ConfigScope expected) {
        var c = ConfigCoordinate.of("platform", "smtp", "host", clientId);
        assertThat(c.scope()).isEqualTo(expected);
        assertThat(PlatformConfig.create(c, "v").scope()).as("the entity carries the derived scope").isEqualTo(expected);
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
    void maskedReplacesOnlyTheValueWithTheMask() {
        var c = PlatformConfig.create(GLOBAL, "hunter2").set("hunter2", ConfigValueType.SECRET, "smtp");
        var masked = c.masked();
        assertThat(masked.value()).isEqualTo(PlatformConfig.MASKED_VALUE).isEqualTo("***");
        assertThat(masked.valueType()).isEqualTo(ConfigValueType.SECRET);
        assertThat(masked.description()).isEqualTo("smtp");
        assertThat(masked.updatedAt()).isEqualTo(c.updatedAt());
        assertThat(masked.id()).isEqualTo(c.id());
    }

    // ── Stored enum reads are strict (X-06) ─────────────────────────────────

    @ParameterizedTest
    @CsvSource({"GLOBAL,GLOBAL", "CLIENT,CLIENT"})
    void scopeParsesTheTwoRecognisedValues(String stored, ConfigScope expected) {
        assertThat(ConfigScope.parse(stored)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"banana"})
    void scopeRejectsAnythingElseInsteadOfDefaultingToGlobal(String stored) {
        assertThatThrownBy(() -> ConfigScope.parse(stored)).isInstanceOf(ConfigScope.UnrecognisedConfigScopeException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"secret", "BANANA"})
    void unknownStoredValueTypeRejectsInsteadOfDefaultingToPlain(String stored) {
        assertThatThrownBy(() -> ConfigValueType.parse(stored)).isInstanceOf(ConfigValueType.UnrecognisedConfigValueTypeException.class);
    }

    @Test
    void secretValueTypeIsRecognised() {
        assertThat(ConfigValueType.parse("SECRET")).isEqualTo(ConfigValueType.SECRET);
        assertThat(ConfigValueType.parse("PLAIN")).isEqualTo(ConfigValueType.PLAIN);
    }

    /// [ConfigValueType#parseWire] reads the set command's `valueType` field:
    /// owner ruling 2026-09-06 #19 (X-06 at the wire) — an unknown value is a
    /// validation error, never a silent PLAIN.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"secret", "BANANA"})
    void unknownWireValueTypeIsAValidationError(String given) {
        assertThatThrownBy(() -> ConfigValueType.parseWire(given))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error().code())
                .isEqualTo("INVALID_VALUE_TYPE");
        assertThat(ConfigValueType.parseWire("PLAIN")).isEqualTo(ConfigValueType.PLAIN);
        assertThat(ConfigValueType.parseWire("SECRET")).isEqualTo(ConfigValueType.SECRET);
    }
}
