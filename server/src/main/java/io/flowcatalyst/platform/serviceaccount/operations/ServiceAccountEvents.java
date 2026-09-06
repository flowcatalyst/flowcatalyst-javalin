package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

/// The service-account aggregate's domain events (spec §6): one event per
/// mutation, subjects `platform.serviceaccount.{id}`, message group
/// `platform:serviceaccount:{id}` so one account's events deliver in order.
///
/// Per CONVENTIONS, an event record must never shadow [DomainEvent]'s
/// `principalId()` accessor (the ACTOR) — every event here names its subject
/// `serviceAccountId`, never `principalId`.
public final class ServiceAccountEvents {

    public static final String SOURCE = "platform:iam";

    public static final String CREATED = "platform:iam:serviceaccount:created";
    public static final String UPDATED = "platform:iam:serviceaccount:updated";
    public static final String DEACTIVATED = "platform:iam:serviceaccount:deactivated";
    public static final String DELETED = "platform:iam:serviceaccount:deleted";
    public static final String ROLES_ASSIGNED = "platform:iam:serviceaccount:roles-assigned";
    public static final String TOKEN_REGENERATED = "platform:iam:serviceaccount:token-regenerated";
    public static final String SECRET_REGENERATED = "platform:iam:serviceaccount:secret-regenerated";
    public static final String TOKEN_MINTED = "platform:iam:serviceaccount:token-minted";

    private ServiceAccountEvents() {
    }

    /// `platform.serviceaccount.{id}` — the subject of every per-aggregate event.
    public static String subjectFor(String serviceAccountId) {
        return EventConventions.buildSubject("platform", "serviceaccount", serviceAccountId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, String serviceAccountId) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(serviceAccountId))
                .withMessageGroup(EventConventions.buildMessageGroup("platform", "serviceaccount", serviceAccountId));
    }

    /// Owner ruling 2026-09-06 #15: who obtained a credential for which
    /// account (spec §8 step 8). Carries the account, its SERVICE principal,
    /// the lifetime and the scope — **never the token**. `principalId()` on
    /// [DomainEvent] stays the actor (the anchor who minted).
    public record ServiceAccountTokenMinted(EventMetadata metadata, String serviceAccountId, String servicePrincipalId,
                                            long expiresInSeconds, List<String> permissions) implements DomainEvent {

        public static ServiceAccountTokenMinted of(ExecutionContext ec, String serviceAccountId, String servicePrincipalId,
                                                   long expiresInSeconds, List<String> permissions) {
            return new ServiceAccountTokenMinted(metadataFor(ec, TOKEN_MINTED, serviceAccountId), serviceAccountId,
                    servicePrincipalId, expiresInSeconds, List.copyOf(permissions));
        }

        @Override
        public Object data() {
            return new Data(serviceAccountId, servicePrincipalId, expiresInSeconds, permissions);
        }

        private record Data(String serviceAccountId, String servicePrincipalId, long expiresInSeconds, List<String> permissions) {
        }
    }

    public record ServiceAccountCreated(EventMetadata metadata, String serviceAccountId, String code, String name)
            implements DomainEvent {

        public static ServiceAccountCreated of(ExecutionContext ec, ServiceAccount sa) {
            return new ServiceAccountCreated(metadataFor(ec, CREATED, sa.id()), sa.id(), sa.code(), sa.name());
        }

        @Override
        public Object data() {
            return new Data(serviceAccountId, code, name);
        }

        private record Data(String serviceAccountId, String code, String name) {
        }
    }

    public record ServiceAccountUpdated(EventMetadata metadata, String serviceAccountId, String name) implements DomainEvent {

        public static ServiceAccountUpdated of(ExecutionContext ec, ServiceAccount sa) {
            return new ServiceAccountUpdated(metadataFor(ec, UPDATED, sa.id()), sa.id(), sa.name());
        }

        @Override
        public Object data() {
            return new Data(serviceAccountId, name);
        }

        private record Data(String serviceAccountId, String name) {
        }
    }

    public record ServiceAccountDeactivated(EventMetadata metadata, String serviceAccountId) implements DomainEvent {

        public static ServiceAccountDeactivated of(ExecutionContext ec, ServiceAccount sa) {
            return new ServiceAccountDeactivated(metadataFor(ec, DEACTIVATED, sa.id()), sa.id());
        }

        @Override
        public Object data() {
            return new Data(serviceAccountId);
        }

        private record Data(String serviceAccountId) {
        }
    }

    public record ServiceAccountDeleted(EventMetadata metadata, String serviceAccountId, String code) implements DomainEvent {

        public static ServiceAccountDeleted of(ExecutionContext ec, ServiceAccount sa) {
            return new ServiceAccountDeleted(metadataFor(ec, DELETED, sa.id()), sa.id(), sa.code());
        }

        @Override
        public Object data() {
            return new Data(serviceAccountId, code);
        }

        private record Data(String serviceAccountId, String code) {
        }
    }

    /// Payload carries the deltas only (spec §4.5) — the new full set is on
    /// the `/roles` sub-route.
    public record ServiceAccountRolesAssigned(EventMetadata metadata, String serviceAccountId, List<String> rolesAdded,
                                              List<String> rolesRemoved) implements DomainEvent {

        public ServiceAccountRolesAssigned {
            rolesAdded = rolesAdded == null ? List.of() : List.copyOf(rolesAdded);
            rolesRemoved = rolesRemoved == null ? List.of() : List.copyOf(rolesRemoved);
        }

        public static ServiceAccountRolesAssigned of(ExecutionContext ec, String serviceAccountId, List<String> added, List<String> removed) {
            return new ServiceAccountRolesAssigned(metadataFor(ec, ROLES_ASSIGNED, serviceAccountId), serviceAccountId, added, removed);
        }

        @Override
        public Object data() {
            return new Data(serviceAccountId, rolesAdded, rolesRemoved);
        }

        private record Data(String serviceAccountId, List<String> rolesAdded, List<String> rolesRemoved) {
        }
    }

    /// Never carries the plaintext — the rotated bearer reaches the caller
    /// only through the operation's disclosure sink (spec §5).
    public record ServiceAccountTokenRegenerated(EventMetadata metadata, String serviceAccountId, String code) implements DomainEvent {

        public static ServiceAccountTokenRegenerated of(ExecutionContext ec, ServiceAccount sa) {
            return new ServiceAccountTokenRegenerated(metadataFor(ec, TOKEN_REGENERATED, sa.id()), sa.id(), sa.code());
        }

        @Override
        public Object data() {
            return new Data(serviceAccountId, code);
        }

        private record Data(String serviceAccountId, String code) {
        }
    }

    /// Never carries the plaintext — same disclosure sink as above.
    public record ServiceAccountSecretRegenerated(EventMetadata metadata, String serviceAccountId, String code) implements DomainEvent {

        public static ServiceAccountSecretRegenerated of(ExecutionContext ec, ServiceAccount sa) {
            return new ServiceAccountSecretRegenerated(metadataFor(ec, SECRET_REGENERATED, sa.id()), sa.id(), sa.code());
        }

        @Override
        public Object data() {
            return new Data(serviceAccountId, code);
        }

        private record Data(String serviceAccountId, String code) {
        }
    }
}
