package io.flowcatalyst.platform.seed;

import java.util.List;
import java.util.Objects;

/// A built-in role as the seeder installs it — the subset of Go's
/// `role.Role` that `seed.PlatformRoles()` fills in. `name` is
/// `{applicationCode}:{roleName}` (e.g. `platform:admin`), `source` is
/// `CODE` so the role-sync logic can identify catalogue roles.
public record RoleDefinition(
        String name,
        String displayName,
        String description,
        String applicationCode,
        String source,
        List<String> permissions) {

    public static final String SOURCE_CODE = "CODE";

    public RoleDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(applicationCode, "applicationCode");
        Objects.requireNonNull(source, "source");
        permissions = List.copyOf(permissions);
    }

    /// The app-local role name with the `{applicationCode}:` prefix removed —
    /// Go's `Role.ShortName()`.
    public String shortName() {
        String prefix = applicationCode + ":";
        return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }
}
