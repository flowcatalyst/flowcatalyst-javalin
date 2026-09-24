package io.flowcatalyst.platform.role;

import io.flowcatalyst.platform.shared.auth.Permission;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/// The role aggregate root (spec: `docs/spec/role.md`). A role is a named,
/// global set of permission codes; principals reference it by `name`
/// (`{applicationCode}:{shortName}`), never by id, and there is no client
/// dimension — so the invariants the aggregate guards are its **source**
/// (spec §2): `CODE` roles belong to the built-in catalogue and refuse the
/// admin update / delete; and **confinement** (security-fixes S1.5): every
/// permission a role is given must belong to the role's own application —
/// its first segment is the role's [#owningApplicationCode()] — so a
/// `platform:` permission (the super-admin wildcard included) can only ever
/// sit on a `platform:` role, and an application's SDK credential cannot
/// mint platform authority by syncing `myapp:admin` with `platform:*:*:*`.
/// Every transition that sets permissions enforces it ([#withPermissions],
/// [#update], [#grant], [#syncedFromSdk], [#syncedFromCatalogue]);
/// [#revoke] does not, so a stray code already stored on a legacy row can
/// still be removed. Rows are read back through the canonical constructor
/// unchecked — a legacy row is loaded, never refused.
///
/// Immutable record: each transition returns a copy and throws
/// [UseCaseException] when an invariant is violated, so an operation is
/// just load → transition → event. `permissions` is always de-duplicated
/// and sorted (the stored set has no order; the wire shows it sorted). The
/// repository persists whatever copy it is handed and stamps `updatedAt`
/// itself.
///
/// @param id              `rol_…` TSID
/// @param applicationId   owning application (`app_…`) — stamped only by the SDK sync, else `null`
/// @param name            canonical `{applicationCode}:{shortName}`, unique, immutable
/// @param displayName     human-readable name
/// @param description     optional
/// @param applicationCode first segment of the name; `null` only on legacy rows
/// @param permissions     de-duplicated, sorted permission codes
/// @param source          `CODE` | `DATABASE` | `SDK`
/// @param clientManaged   informational "managed at client scope" flag
/// @param createdAt       creation time
/// @param updatedAt       last change
public record Role(
        String id,
        String applicationId,
        String name,
        String displayName,
        String description,
        String applicationCode,
        List<String> permissions,
        RoleSource source,
        boolean clientManaged,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    public Role {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        permissions = normalise(permissions);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `DATABASE`-sourced role named `{applicationCode}:{roleName}`
    /// with no permissions. The arguments are joined verbatim (no trimming,
    /// no prefix stripping — spec §4); the syncs strip a qualified name with
    /// [#localName] before calling this.
    public static Role create(String applicationCode, String roleName, String displayName) {
        Objects.requireNonNull(applicationCode, "applicationCode");
        Objects.requireNonNull(roleName, "roleName");
        Instant now = Instant.now();
        return new Role(EntityType.ROLE.generate(), null, applicationCode + ":" + roleName, displayName, null,
                applicationCode, List.of(), RoleSource.DATABASE, false, now, now);
    }

    /// The short role name recovered from a possibly qualified
    /// `{applicationCode}:{shortName}`: strips exactly one leading
    /// `{applicationCode}:` when present (so `hr:hr-manager` and a malformed
    /// `hr:dashboard:user` both round-trip), otherwise the input unchanged.
    public static String localName(String name, String applicationCode) {
        String prefix = applicationCode + ":";
        return name.length() > prefix.length() && name.startsWith(prefix) ? name.substring(prefix.length()) : name;
    }

    /// The app-local name — [#localName] applied to this role's own name.
    public String shortName() {
        return applicationCode == null ? name : localName(name, applicationCode);
    }

    public boolean isCode() {
        return source == RoleSource.CODE;
    }

    /// The application this role belongs to: its `applicationCode`, or — on
    /// a legacy row without one — the first segment of its name.
    public String owningApplicationCode() {
        if (applicationCode != null && !applicationCode.isBlank()) return applicationCode;
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(0, colon);
    }

    /// The confinement rule (security-fixes S1.5): every code's first
    /// segment is this role's application.
    ///
    /// @throws UseCaseException validation `PERMISSION_OUTSIDE_APPLICATION`
    private List<String> confined(List<String> codes) {
        String app = owningApplicationCode();
        for (String code : codes) {
            String first = code == null ? "" : code.split(":", 2)[0];
            if (!first.equals(app)) {
                throw UseCaseException.validation("PERMISSION_OUTSIDE_APPLICATION",
                        "Permission '" + code + "' does not belong to application '" + app
                                + "'; a role may only hold its own application's permissions");
            }
        }
        return codes;
    }

    /// Whether the role grants `permission`, honouring `*` segment wildcards
    /// in the held codes (the shared matcher, spec §1).
    public boolean hasPermission(String permission) {
        return Permission.grants(permissions, permission);
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// The admin update: every non-null field of `changes` replaces the
    /// current value (`displayName` trimmed, `permissions` wholesale).
    ///
    /// @throws UseCaseException conflict `CODE_ROLE_IMMUTABLE` for a catalogue role
    public Role update(Changes changes) {
        if (isCode()) {
            throw UseCaseException.conflict("CODE_ROLE_IMMUTABLE", "Roles with source=CODE cannot be modified");
        }
        return new Role(id, applicationId, name,
                changes.displayName() == null ? displayName : changes.displayName().strip(),
                changes.description() == null ? description : changes.description(),
                applicationCode,
                changes.permissions() == null ? permissions : confined(changes.permissions()),
                source,
                changes.clientManaged() == null ? clientManaged : changes.clientManaged(),
                createdAt, Instant.now());
    }

    /// The fields an admin update may replace; `null` = leave untouched
    /// (`permissions = []` clears the set, `null` keeps it).
    public record Changes(String displayName, String description, List<String> permissions, Boolean clientManaged) {
        public Changes {
            permissions = permissions == null ? null : List.copyOf(permissions);
        }
    }

    /// The role itself, if the admin API may delete it.
    ///
    /// @throws UseCaseException conflict `CODE_ROLE_IMMUTABLE` for a catalogue role
    public Role requireDeletable() {
        if (isCode()) {
            throw UseCaseException.conflict("CODE_ROLE_IMMUTABLE", "Roles with source=CODE cannot be deleted");
        }
        return this;
    }

    /// Adds `permission` to the set; a no-op copy when already held. Allowed
    /// on every source, including `CODE` (spec §2, open question 1).
    ///
    /// @throws UseCaseException validation `PERMISSION_OUTSIDE_APPLICATION` (confinement)
    public Role grant(String permission) {
        confined(List.of(permission));
        if (permissions.contains(permission)) {
            return this;
        }
        var next = new ArrayList<>(permissions);
        next.add(permission);
        return replacePermissions(next);
    }

    /// Removes `permission` from the set (a no-op copy when absent). Not
    /// confinement-checked: removing a stray code must always be possible.
    public Role revoke(String permission) {
        return replacePermissions(permissions.stream().filter(p -> !p.equals(permission)).toList());
    }

    /// The catalogue sync's refresh of a `CODE` role: display name,
    /// description and permissions are taken from the catalogue wholesale
    /// (spec §7.2).
    public Role syncedFromCatalogue(String newDisplayName, String newDescription, List<String> newPermissions) {
        return new Role(id, applicationId, name, newDisplayName, newDescription, applicationCode, confined(newPermissions),
                source, clientManaged, createdAt, Instant.now());
    }

    /// The SDK sync's refresh of an `SDK` role: display name, description and
    /// `clientManaged` replaced; permissions replaced **only when the batch
    /// supplies a non-empty list** — apps declare role names, permissions
    /// are curated in the UI, and an omitted list must not wipe them
    /// (spec §7.1).
    public Role syncedFromSdk(String newDisplayName, String newDescription, List<String> newPermissions, boolean newClientManaged) {
        return new Role(id, applicationId, name, newDisplayName, newDescription, applicationCode,
                newPermissions == null || newPermissions.isEmpty() ? permissions : confined(newPermissions),
                source, newClientManaged, createdAt, Instant.now());
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    public Role withDescription(String newDescription) {
        return new Role(id, applicationId, name, displayName, newDescription, applicationCode, permissions, source,
                clientManaged, createdAt, updatedAt);
    }

    /// @throws UseCaseException validation `PERMISSION_OUTSIDE_APPLICATION` (confinement)
    public Role withPermissions(List<String> newPermissions) {
        return replacePermissions(newPermissions == null ? null : confined(newPermissions));
    }

    private Role replacePermissions(List<String> newPermissions) {
        return new Role(id, applicationId, name, displayName, description, applicationCode, newPermissions, source,
                clientManaged, createdAt, Instant.now());
    }

    public Role withSource(RoleSource newSource) {
        return new Role(id, applicationId, name, displayName, description, applicationCode, permissions, newSource,
                clientManaged, createdAt, updatedAt);
    }

    public Role withApplicationId(String newApplicationId) {
        return new Role(id, newApplicationId, name, displayName, description, applicationCode, permissions, source,
                clientManaged, createdAt, updatedAt);
    }

    public Role withClientManaged(boolean newClientManaged) {
        return new Role(id, applicationId, name, displayName, description, applicationCode, permissions, source,
                newClientManaged, createdAt, updatedAt);
    }

    /// Permissions as stored and shown: no duplicates, text order, never null.
    private static List<String> normalise(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return List.of();
        return List.copyOf(new TreeSet<>(permissions));
    }
}
