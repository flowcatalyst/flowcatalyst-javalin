package io.flowcatalyst.platform.role.operations;

import io.flowcatalyst.platform.role.Role;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

/// The role aggregate's domain events (spec §8): the type strings, the
/// source, the subject / message-group builders and one record per event.
/// Every event has a static `of(…)` factory that takes the execution
/// context plus the aggregate, so operations never assemble metadata or
/// payload fields by hand; the `data()` records are the wire payloads, field
/// names verbatim.
public final class RoleEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:role:created";
    public static final String UPDATED = "platform:admin:role:updated";
    public static final String DELETED = "platform:admin:role:deleted";
    public static final String PERMISSION_GRANTED = "platform:admin:role:permission-granted";
    public static final String PERMISSION_REVOKED = "platform:admin:role:permission-revoked";
    public static final String SYNCED = "platform:admin:roles:synced";

    /// Subject and message group of the sync rollup — the same for the SDK
    /// and the catalogue sync (spec §8, open question 7).
    public static final String SYNC_SUBJECT = "platform.roles";
    public static final String SYNC_MESSAGE_GROUP = "platform:roles";

    private RoleEvents() {
    }

    /// `platform.role.{id}` — the subject of every per-role event.
    public static String subjectFor(String roleId) {
        return EventConventions.buildSubject("platform", "role", roleId);
    }

    /// `platform:role:{id}` — one role's events are delivered in order.
    public static String groupFor(String roleId) {
        return EventConventions.buildMessageGroup("platform", "role", roleId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, Role role) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(role.id())).withMessageGroup(groupFor(role.id()));
    }

    /// Emitted on create (and per created row by both syncs).
    public record RoleCreated(EventMetadata metadata, String roleId, String name) implements DomainEvent {

        public static RoleCreated of(ExecutionContext ec, Role role) {
            return new RoleCreated(metadataFor(ec, CREATED, role), role.id(), role.name());
        }

        @Override
        public Object data() {
            return new Data(roleId, name);
        }

        private record Data(String roleId, String name) {
        }
    }

    /// Emitted on update (and per updated row by both syncs).
    public record RoleUpdated(EventMetadata metadata, String roleId, String name) implements DomainEvent {

        public static RoleUpdated of(ExecutionContext ec, Role role) {
            return new RoleUpdated(metadataFor(ec, UPDATED, role), role.id(), role.name());
        }

        @Override
        public Object data() {
            return new Data(roleId, name);
        }

        private record Data(String roleId, String name) {
        }
    }

    /// Emitted on delete (and per removed row by both syncs).
    public record RoleDeleted(EventMetadata metadata, String roleId, String name) implements DomainEvent {

        public static RoleDeleted of(ExecutionContext ec, Role role) {
            return new RoleDeleted(metadataFor(ec, DELETED, role), role.id(), role.name());
        }

        @Override
        public Object data() {
            return new Data(roleId, name);
        }

        private record Data(String roleId, String name) {
        }
    }

    /// Emitted on every grant — including a re-grant of a held permission.
    public record RolePermissionGranted(EventMetadata metadata, String roleId, String roleName, String permission)
            implements DomainEvent {

        public static RolePermissionGranted of(ExecutionContext ec, Role role, String permission) {
            return new RolePermissionGranted(metadataFor(ec, PERMISSION_GRANTED, role), role.id(), role.name(), permission);
        }

        @Override
        public Object data() {
            return new Data(roleId, roleName, permission);
        }

        private record Data(String roleId, String roleName, String permission) {
        }
    }

    /// Emitted on every revoke — including one of an absent permission.
    public record RolePermissionRevoked(EventMetadata metadata, String roleId, String roleName, String permission)
            implements DomainEvent {

        public static RolePermissionRevoked of(ExecutionContext ec, Role role, String permission) {
            return new RolePermissionRevoked(metadataFor(ec, PERMISSION_REVOKED, role), role.id(), role.name(), permission);
        }

        @Override
        public Object data() {
            return new Data(roleId, roleName, permission);
        }

        private record Data(String roleId, String roleName, String permission) {
        }
    }

    /// The rollup emitted by [SyncRoles] and [SyncPlatformRoles]: subject
    /// `platform.roles`, message group `platform:roles`. `total` is the size
    /// of the input (the batch, or the code catalogue) — not a row count
    /// after the sync. `applicationCode` / `syncedCodes` are set only by the
    /// application-scoped SDK sync and omitted from the payload otherwise.
    public record RolesSynced(EventMetadata metadata, int created, int updated, int removed, int total,
                              String applicationCode, List<String> syncedCodes) implements DomainEvent {

        public RolesSynced {
            syncedCodes = syncedCodes == null ? List.of() : List.copyOf(syncedCodes);
        }

        /// The catalogue sync's rollup — no application dimension.
        public static RolesSynced ofCatalogue(ExecutionContext ec, int created, int updated, int removed, int total) {
            return new RolesSynced(syncMetadata(ec), created, updated, removed, total, null, List.of());
        }

        /// The SDK sync's rollup for one application.
        public static RolesSynced ofApplication(ExecutionContext ec, String applicationCode, int created, int updated,
                                                int removed, int total, List<String> syncedCodes) {
            return new RolesSynced(syncMetadata(ec), created, updated, removed, total, applicationCode, syncedCodes);
        }

        private static EventMetadata syncMetadata(ExecutionContext ec) {
            return EventMetadata.of(ec, SYNCED, SOURCE, SYNC_SUBJECT).withMessageGroup(SYNC_MESSAGE_GROUP);
        }

        @Override
        public Object data() {
            return new Data(created, updated, removed, total, applicationCode, syncedCodes.isEmpty() ? null : syncedCodes);
        }

        private record Data(int created, int updated, int removed, int total, String applicationCode,
                            List<String> syncedCodes) {
        }
    }
}
