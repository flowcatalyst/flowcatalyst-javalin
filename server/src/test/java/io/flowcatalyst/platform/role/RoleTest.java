package io.flowcatalyst.platform.role;

import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–2): naming, the permission set, the
/// source invariant on update / delete, the sync refreshes and the lenient
/// enum read — no database involved.
class RoleTest {

    // ── Naming ─────────────────────────────────────────────────────────────

    @Test
    void createJoinsApplicationCodeAndRoleNameVerbatim() {
        var role = Role.create("hr", "hr-manager", "HR Manager");
        assertThat(role.id()).startsWith("rol_");
        assertThat(role.name()).isEqualTo("hr:hr-manager");
        assertThat(role.applicationCode()).isEqualTo("hr");
        assertThat(role.shortName()).isEqualTo("hr-manager");
        assertThat(role.source()).isEqualTo(RoleSource.DATABASE);
        assertThat(role.permissions()).isEmpty();
        assertThat(role.clientManaged()).isFalse();
        assertThat(role.applicationId()).isNull();
        assertThat(role.description()).isNull();

        // Not trimmed and not prefix-stripped: the admin create takes the name as given (spec §4).
        assertThat(Role.create("hr", " hr:hr-manager ", "X").name()).isEqualTo("hr: hr:hr-manager ");
    }

    @ParameterizedTest(name = "localName(''{0}'', ''{1}'') = ''{2}''")
    @CsvSource({
            "hr-manager, hr, hr-manager",
            "hr:hr-manager, hr, hr-manager",
            "hr:dashboard:user, hr, dashboard:user",
            "other:hr-manager, hr, other:hr-manager",
            "hr:, hr, hr:",
    })
    void localNameStripsExactlyOneApplicationPrefix(String name, String applicationCode, String expected) {
        assertThat(Role.localName(name, applicationCode)).isEqualTo(expected);
    }

    @Test
    void shortNameFallsBackToTheFullNameWithoutAnApplicationCode() {
        var legacy = Role.create("hr", "x", "X").withSource(RoleSource.DATABASE);
        var noCode = new Role(legacy.id(), null, "hr:x", "X", null, null, List.of(), RoleSource.DATABASE, false,
                legacy.createdAt(), legacy.updatedAt());
        assertThat(noCode.shortName()).isEqualTo("hr:x");
    }

    // ── Permission set ─────────────────────────────────────────────────────

    @Test
    void permissionsAreDeduplicatedAndSortedOnEveryCopy() {
        var role = Role.create("p", "r", "R").withPermissions(List.of("p:b:c:d", "p:a:c:d", "p:b:c:d"));
        assertThat(role.permissions()).containsExactly("p:a:c:d", "p:b:c:d");
    }

    @Test
    void grantIsIdempotentAndRevokeRemoves() {
        var role = Role.create("p", "r", "R").grant("p:x:y:z");
        assertThat(role.permissions()).containsExactly("p:x:y:z");
        var again = role.grant("p:x:y:z");
        assertThat(again.permissions()).containsExactly("p:x:y:z");
        assertThat(again).as("re-grant of a held permission is a no-op copy").isSameAs(role);

        var revoked = again.grant("p:a:b:c").revoke("p:x:y:z");
        assertThat(revoked.permissions()).containsExactly("p:a:b:c");
        assertThat(revoked.revoke("not:held:at:all").permissions()).containsExactly("p:a:b:c");
    }

    @Test
    void hasPermissionHonoursSegmentWildcards() {
        // A platform role: since security-fixes S1.5 a role only holds its own
        // application's codes, so the platform code sits on a platform role.
        var role = Role.create("platform", "r", "R").withPermissions(List.of("platform:iam:role:view", "platform:*:thing:*"));
        assertThat(role.hasPermission("platform:iam:role:view")).isTrue();
        assertThat(role.hasPermission("platform:iam:role:create")).isFalse();
        assertThat(role.hasPermission("platform:any:thing:read")).isTrue();
        assertThat(role.hasPermission("platform:any:other:read")).isFalse();
        assertThat(role.hasPermission("platform:any:thing")).as("segment counts must match").isFalse();
    }

    // ── Confinement (security-fixes S1.5) ──────────────────────────────────

    /// Every transition that sets permissions refuses a code outside the
    /// role's application — the attack being an application role carrying the
    /// platform super-admin wildcard, or an all-applications `*:*:*:*` —
    /// while the platform wildcard on a platform role is fine.
    @Test
    void everyPermissionSettingTransitionConfinesCodesToTheRolesApplication() {
        var app = Role.create("myapp", "admin", "Admin");
        for (String foreign : List.of("platform:*:*:*", "*:*:*:*", "other:x:y:z", "myappx:a:b:c")) {
            assertUseCaseError(() -> app.withPermissions(List.of("myapp:a:b:c", foreign)),
                    UseCaseError.Validation.class, "PERMISSION_OUTSIDE_APPLICATION");
            assertUseCaseError(() -> app.update(new Role.Changes(null, null, List.of(foreign), null)),
                    UseCaseError.Validation.class, "PERMISSION_OUTSIDE_APPLICATION");
            assertUseCaseError(() -> app.grant(foreign),
                    UseCaseError.Validation.class, "PERMISSION_OUTSIDE_APPLICATION");
            assertUseCaseError(() -> app.withSource(RoleSource.SDK).syncedFromSdk("A", null, List.of(foreign), false),
                    UseCaseError.Validation.class, "PERMISSION_OUTSIDE_APPLICATION");
            assertUseCaseError(() -> app.syncedFromCatalogue("A", null, List.of(foreign)),
                    UseCaseError.Validation.class, "PERMISSION_OUTSIDE_APPLICATION");
        }
        assertThat(app.withPermissions(List.of("myapp:*:*:*")).permissions()).containsExactly("myapp:*:*:*");
        assertThat(Role.create("platform", "super-admin", "SA").withPermissions(List.of("platform:*:*:*")).permissions())
                .containsExactly("platform:*:*:*");
    }

    /// A legacy row already holding a stray code loads unchecked, and the
    /// stray code can still be revoked; with no `applicationCode` the name's
    /// first segment is the owning application.
    @Test
    void aLegacyStrayCodeLoadsAndCanBeRevoked() {
        var base = Role.create("myapp", "legacy", "L");
        var legacy = new Role(base.id(), null, "myapp:legacy", "L", null, null, List.of("myapp:a:b:c", "platform:*:*:*"),
                RoleSource.DATABASE, false, base.createdAt(), base.updatedAt());
        assertThat(legacy.owningApplicationCode()).isEqualTo("myapp");
        assertThat(legacy.revoke("platform:*:*:*").permissions()).containsExactly("myapp:a:b:c");
        assertThat(legacy.grant("myapp:d:e:f").permissions()).as("grant checks only the code it adds")
                .contains("myapp:d:e:f");
        assertUseCaseError(() -> legacy.grant("platform:iam:user:create"),
                UseCaseError.Validation.class, "PERMISSION_OUTSIDE_APPLICATION");
    }

    // ── Admin update / delete and the source invariant ─────────────────────

    @Test
    void updateReplacesOnlyTheSuppliedFieldsAndTrimsTheDisplayName() {
        var role = Role.create("p", "r", "Before").withDescription("before").withPermissions(List.of("p:a:b:c"));

        var renamed = role.update(new Role.Changes("  After  ", null, null, null));
        assertThat(renamed.displayName()).isEqualTo("After");
        assertThat(renamed.description()).isEqualTo("before");
        assertThat(renamed.permissions()).containsExactly("p:a:b:c");
        assertThat(renamed.clientManaged()).isFalse();
        assertThat(renamed.name()).as("name is immutable").isEqualTo("p:r");

        var all = role.update(new Role.Changes("X", "after", List.of("p:z:z:z", "p:y:y:y"), true));
        assertThat(all.description()).isEqualTo("after");
        assertThat(all.permissions()).containsExactly("p:y:y:y", "p:z:z:z");
        assertThat(all.clientManaged()).isTrue();

        assertThat(role.update(new Role.Changes(null, null, List.of(), null)).permissions())
                .as("an empty list clears the set; null keeps it").isEmpty();
    }

    @Test
    void codeRolesRefuseUpdateAndDeleteButAcceptGrantAndRevoke() {
        var code = Role.create("platform", "admin", "Admin").withSource(RoleSource.CODE);
        assertUseCaseError(() -> code.update(new Role.Changes("X", null, null, null)),
                UseCaseError.Conflict.class, "CODE_ROLE_IMMUTABLE");
        assertThatThrownBy(() -> code.update(new Role.Changes("X", null, null, null)))
                .hasMessageContaining("Roles with source=CODE cannot be modified");
        assertUseCaseError(code::requireDeletable, UseCaseError.Conflict.class, "CODE_ROLE_IMMUTABLE");
        assertThatThrownBy(code::requireDeletable).hasMessageContaining("Roles with source=CODE cannot be deleted");

        assertThat(code.grant("platform:x:y:z").permissions()).containsExactly("platform:x:y:z");
        assertThat(code.grant("platform:x:y:z").revoke("platform:x:y:z").permissions()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATABASE", "SDK"})
    void nonCodeRolesAreEditableAndDeletable(String source) {
        var role = Role.create("p", "r", "R").withSource(RoleSource.parse(source));
        assertThat(role.update(new Role.Changes("X", null, null, null)).displayName()).isEqualTo("X");
        assertThat(role.requireDeletable()).isSameAs(role);
    }

    // ── Sync refreshes ─────────────────────────────────────────────────────

    @Test
    void catalogueSyncReplacesEverythingButKeepsClientManaged() {
        var role = Role.create("platform", "x", "Old").withSource(RoleSource.CODE).withClientManaged(true)
                .withPermissions(List.of("platform:old:old:old"));
        var synced = role.syncedFromCatalogue("New", "desc", List.of("platform:new:new:new"));
        assertThat(synced.displayName()).isEqualTo("New");
        assertThat(synced.description()).isEqualTo("desc");
        assertThat(synced.permissions()).containsExactly("platform:new:new:new");
        assertThat(synced.clientManaged()).isTrue();
        assertThat(synced.source()).isEqualTo(RoleSource.CODE);
    }

    @Test
    void sdkSyncPreservesStoredPermissionsWhenTheBatchSuppliesNone() {
        var role = Role.create("hr", "editor", "Old").withSource(RoleSource.SDK).withPermissions(List.of("hr:doc:edit:*"));
        var kept = role.syncedFromSdk("New", null, List.of(), true);
        assertThat(kept.permissions()).containsExactly("hr:doc:edit:*");
        assertThat(kept.displayName()).isEqualTo("New");
        assertThat(kept.clientManaged()).isTrue();
        var replaced = role.syncedFromSdk("New", null, List.of("hr:doc:read:*"), false);
        assertThat(replaced.permissions()).containsExactly("hr:doc:read:*");
    }

    // ── Enum + catalogue entry ─────────────────────────────────────────────

    @ParameterizedTest(name = "''{0}'' → {1}")
    @CsvSource({"CODE, CODE", "SDK, SDK", "DATABASE, DATABASE"})
    void storedSourceParsesTheThreeRecognisedValues(String raw, RoleSource expected) {
        assertThat(RoleSource.parse(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "''{0}''")
    @ValueSource(strings = {"bogus", ""})
    void storedSourceRejectsAnythingElseInsteadOfDefaultingToDatabase(String raw) {
        assertThatThrownBy(() -> RoleSource.parse(raw)).isInstanceOf(RoleSource.UnrecognisedRoleSourceException.class);
    }

    @Test
    void storedSourceRejectsNull() {
        assertThatThrownBy(() -> RoleSource.parse(null)).isInstanceOf(RoleSource.UnrecognisedRoleSourceException.class);
    }

    /// [RoleSource#parseWire] is the wire-only lenient reader (the
    /// `/by-source/{source}` path segment) — untouched by X-06.
    @ParameterizedTest(name = "''{0}'' → {1}")
    @CsvSource({"CODE, CODE", "SDK, SDK", "DATABASE, DATABASE", "bogus, DATABASE", ", DATABASE"})
    void wireSourceParsesLeniently(String raw, RoleSource expected) {
        assertThat(RoleSource.parseWire(raw)).isEqualTo(expected);
    }

    @Test
    void permissionDefinitionSplitsTheFourSegments() {
        var p = Permission.define("platform:iam:role:view", "See roles");
        assertThat(p.id()).startsWith("prm_");
        assertThat(p.subdomain()).isEqualTo("platform");
        assertThat(p.context()).isEqualTo("iam");
        assertThat(p.aggregate()).isEqualTo("role");
        assertThat(p.action()).isEqualTo("view");
        assertThat(p.category()).isEqualTo("platform:iam:role");
        assertThat(p.description()).isEqualTo("See roles");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a:b:c", "a:b:c:d:e", "", "plain"})
    void permissionDefinitionRejectsOtherSegmentCounts(String code) {
        assertUseCaseError(() -> Permission.define(code, null), UseCaseError.Validation.class, "INVALID_PERMISSION_CODE");
        assertThatThrownBy(() -> Permission.define(code, null)).hasMessageContaining(Permission.FORMAT_MESSAGE);
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }
}
