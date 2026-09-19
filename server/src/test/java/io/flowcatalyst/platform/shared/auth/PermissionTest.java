package io.flowcatalyst.platform.shared.auth;

import io.flowcatalyst.platform.seed.Permissions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.flowcatalyst.platform.shared.auth.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

class PermissionTest {

    @Test
    void codeSplitsIntoContextResourceAction() {
        assertThat(EVENT_TYPE_VIEW.code()).isEqualTo("platform:messaging:event-type:view");
        assertThat(EVENT_TYPE_VIEW.context()).isEqualTo("messaging");
        assertThat(EVENT_TYPE_VIEW.resource()).isEqualTo("event-type");
        assertThat(EVENT_TYPE_VIEW.action()).isEqualTo("view");
        assertThat(APP_SVC_ROLE_CREATE.context()).isEqualTo("application-service");
        assertThat(USER_ASSIGN_ROLES.action()).isEqualTo("assign-roles");
        assertThat(SUPER_ADMIN.code()).isEqualTo("platform:*:*:*");
        assertThat(SUPER_ADMIN.resource()).isEqualTo("*");
    }

    @Test
    void parseRoundTripsEveryConstantAndRejectsTheRest() {
        for (Permission p : values()) {
            assertThat(parse(p.code())).as(p.name()).contains(p);
        }
        assertThat(parse("platform:messaging:*:*")).isEmpty(); // held patterns are never required
        assertThat(parse("event-type:view")).isEmpty();
        assertThat(parse("")).isEmpty();
        assertThat(parse(null)).isEmpty();
    }

    @Test
    void codesAreUniqueAndNamesFollowTheRule() {
        Set<String> codes = new HashSet<>();
        for (Permission p : values()) {
            assertThat(codes.add(p.code())).as("duplicate code %s", p.code()).isTrue();
            assertThat(p.name()).as(p.code()).isEqualTo(expectedName(p));
        }
    }

    /// The `function` context's six permissions (spec `function-api.md` §2)
    /// are all named with the `FUNCTION_` prefix regardless of resource —
    /// deliberately, so a reader searching for "function" finds every one of
    /// them, and because the mechanical `<RESOURCE>_<ACTION>` rule would
    /// collide `platform:function:policy:manage` with
    /// `platform:function:function:manage` (both would mechanically be
    /// `..._MANAGE`). An explicit table, not a formula, because no formula
    /// produces these six names uniquely.
    private static final Map<String, String> FUNCTION_CONTEXT_NAMES = Map.of(
            "platform:function:function:view", "FUNCTION_VIEW",
            "platform:function:function:manage", "FUNCTION_MANAGE",
            "platform:function:version:publish", "FUNCTION_PUBLISH",
            "platform:function:alias:promote", "FUNCTION_PROMOTE",
            "platform:function:policy:manage", "FUNCTION_POLICY_MANAGE",
            "platform:function:host:control", "FUNCTION_HOST_CONTROL",
            "platform:function:version:invoke", "FUNCTION_VERSION_INVOKE");

    /// `<RESOURCE>_<ACTION>`, `APP_SVC_` prefix for the application-service
    /// context, `FUNCTION_CONTEXT_NAMES` for the function context,
    /// `SUPER_ADMIN` for the wildcard — the rule the enum's doc states.
    private static String expectedName(Permission p) {
        if (p.code().equals("platform:*:*:*")) return "SUPER_ADMIN";
        if (p.context().equals("function")) return FUNCTION_CONTEXT_NAMES.get(p.code());
        var name = (p.resource() + "_" + p.action()).toUpperCase(Locale.ROOT).replace('-', '_');
        return p.context().equals("application-service") ? "APP_SVC_" + name : name;
    }

    @Test
    void matchesTheSeederCatalogueExactly() {
        assertThat(Stream.of(values()).map(Permission::code).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(seederCatalogue());
    }

    /// Every code the seeder's catalogue declares: its `String` constants plus
    /// the `APPLICATION_SERVICE` list.
    private static Set<String> seederCatalogue() {
        Set<String> codes = new HashSet<>(Permissions.APPLICATION_SERVICE);
        for (var f : Permissions.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            try {
                codes.add((String) f.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError(f.getName(), e);
            }
        }
        return codes;
    }

    @Test
    void wildcardMatchingIsBySegment() {
        assertThat(matches("platform:*:*:*", "platform:iam:role:view")).isTrue();
        assertThat(matches("platform:*:*", "platform:iam:role:view")).isFalse(); // segment count
        assertThat(matches("platform:iam:role:view", "platform:iam:role:view")).isTrue();
        assertThat(matches("platform:iam:role:create", "platform:iam:role:view")).isFalse();
        assertThat(grants(List.of("platform:iam:*:view"), "platform:iam:role:view")).isTrue();
        assertThat(grants(null, "platform:iam:role:view")).isFalse();
        assertThat(grants(List.of("platform:*:*:*"), null)).isFalse();
        assertThat(ROLE_VIEW.grantedBy(List.of("platform:iam:*:view"))).isTrue();
        assertThat(ROLE_CREATE.grantedBy(List.of("platform:iam:*:view"))).isFalse();
    }
}
