package io.flowcatalyst.platform.role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The role-name and permission shape (Go F18 `role/name_test.go`): two or more
/// colon-separated ASCII segments, no whitespace of any kind, rejected rather than trimmed.
class RoleNameTest {

    private static UseCaseError error(Runnable r) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(r::run);
        assertThat(t).isInstanceOf(UseCaseException.class);
        return ((UseCaseException) t).error();
    }

    @Test
    void wellFormedNamesPass() {
        for (String n : List.of("platform:admin", "platform:super-admin", "example:dispatcher",
                "logistics_portal:dashboard:user", // multi-colon: tolerated, Role#shortName round-trips it
                "orders:order.admin", "Orders:Admin", "a1:b2")) {
            RoleName.validate(n);
            assertThat(RoleName.of(n).value()).isEqualTo(n);
        }
    }

    @Test
    void malformedNamesAreRejectedWithTheOffendingValueQuoted() {
        for (String n : List.of("", " ", "example: dispatcher", "example :dispatcher", " example:dispatcher",
                "example:dispatcher ", "example:dis patcher", "example:dispatcher\n", "example:\tdispatcher",
                "example: dispatcher", "example:​dispatcher", // nbsp, zero-width space
                "dispatcher", ":dispatcher", "example:", "example::dispatcher", "example:dispatcher:",
                "example:dis;patcher", "example:diß", "exa mple:dispatcher")) {
            UseCaseError err = error(() -> RoleName.validate(n));
            assertThat(err).as("%s", n).isInstanceOf(UseCaseError.Validation.class);
            assertThat(err.code()).as("%s", n).isEqualTo("INVALID_ROLE_NAME");
            assertThat(err.message()).contains("\"" + n + "\"");
            assertThat(RoleName.isWellFormed(n)).isFalse();
        }
        assertThatThrownBy(() -> RoleName.validate(null)).isInstanceOf(UseCaseException.class);
    }

    @Test
    void permissionsUseTheSameRulePlusStar() {
        for (String p : List.of("platform:*:*:*", "orders:order:create", "platform:iam:user:view", "x")) {
            RoleName.validatePermission(p);
        }
        for (String p : List.of("", " ", "orders: order:create", "orders:order:create ", "orders::create", ":a", "a:", "a:b c")) {
            UseCaseError err = error(() -> RoleName.validatePermission(p));
            assertThat(err).as("%s", p).isInstanceOf(UseCaseError.Validation.class);
            assertThat(err.code()).as("%s", p).isEqualTo("INVALID_PERMISSION");
        }
        // '*' is a permission character, not a role-name one.
        assertThat(RoleName.isWellFormed("platform:*")).isFalse();
    }

    @Test
    void validateAllReportsTheFirstOffender() {
        RoleName.validateAll(List.of("a:b", "c:d"));
        assertThatThrownBy(() -> RoleName.validateAll(List.of("a:b", "example: dispatcher", "x y:z")))
                .hasMessageContaining("\"example: dispatcher\"").hasMessageNotContaining("x y:z");
        RoleName.validatePermissions(null);
    }

    @Test
    void storedValidatesNothingAndNamesCompareByValue() {
        assertThat(RoleName.stored("legacy: bad name").value()).isEqualTo("legacy: bad name");
        assertThat(RoleName.of("a:b")).isEqualTo(RoleName.stored("a:b")).hasSameHashCodeAs(RoleName.stored("a:b"));
        assertThat(RoleName.constant("platform:admin")).hasToString("platform:admin");
        assertThatThrownBy(() -> RoleName.constant("platform: admin")).isInstanceOf(UseCaseException.class);
    }

    @Test
    void roleIsPlatformByApplicationCodeNotByAMissingId() {
        assertThat(Role.create("platform", "admin", "A").isPlatform()).isTrue();
        assertThat(Role.create("platform", "admin", "A").withApplicationId("app_1").isPlatform()).isTrue();
        assertThat(Role.create("orders", "admin", "A").isPlatform()).isFalse();
        assertThat(Role.create("orders", "admin", "A").canonicalName().value()).isEqualTo("orders:admin");
    }
}
