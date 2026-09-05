package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.time.Instant;

/// The OAuth-client aggregate's domain events (spec `auth-core.md` §6.3):
/// the type strings, the source, the subject builder and one record per
/// event. Every event has a static `of(…)` factory that takes the execution
/// context plus the aggregate, so operations never assemble metadata or
/// payload fields by hand; the `data()` records are the wire payloads,
/// field names verbatim.
public final class OAuthClientEvents {

    public static final String SOURCE = "platform:admin";

    public static final String CREATED = "platform:admin:oauth-client:created";
    public static final String UPDATED = "platform:admin:oauth-client:updated";
    public static final String ACTIVATED = "platform:admin:oauth-client:activated";
    public static final String DEACTIVATED = "platform:admin:oauth-client:deactivated";
    public static final String DELETED = "platform:admin:oauth-client:deleted";
    public static final String SECRET_ROTATED = "platform:admin:oauth-client:secret-rotated";
    public static final String PREVIOUS_SECRET_REVOKED = "platform:admin:oauth-client:previous-secret-revoked";

    private OAuthClientEvents() {
    }

    /// `platform.oauthclient.{id}` — the subject of every per-aggregate event.
    public static String subjectFor(String oauthClientId) {
        return EventConventions.buildSubject("platform", "oauthclient", oauthClientId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, OAuthClient c) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(c.id()));
    }

    public record OAuthClientCreated(EventMetadata metadata, String oauthClientId, String clientId, String clientName)
            implements DomainEvent {

        public static OAuthClientCreated of(ExecutionContext ec, OAuthClient c) {
            return new OAuthClientCreated(metadataFor(ec, CREATED, c), c.id(), c.clientId(), c.clientName());
        }

        @Override
        public Object data() {
            return new Data(oauthClientId, clientId, clientName);
        }

        private record Data(String oauthClientId, String clientId, String clientName) {
        }
    }

    public record OAuthClientUpdated(EventMetadata metadata, String oauthClientId, String clientName)
            implements DomainEvent {

        public static OAuthClientUpdated of(ExecutionContext ec, OAuthClient c) {
            return new OAuthClientUpdated(metadataFor(ec, UPDATED, c), c.id(), c.clientName());
        }

        @Override
        public Object data() {
            return new Data(oauthClientId, clientName);
        }

        private record Data(String oauthClientId, String clientName) {
        }
    }

    public record OAuthClientActivated(EventMetadata metadata, String oauthClientId) implements DomainEvent {

        public static OAuthClientActivated of(ExecutionContext ec, OAuthClient c) {
            return new OAuthClientActivated(metadataFor(ec, ACTIVATED, c), c.id());
        }

        @Override
        public Object data() {
            return new Data(oauthClientId);
        }

        private record Data(String oauthClientId) {
        }
    }

    public record OAuthClientDeactivated(EventMetadata metadata, String oauthClientId) implements DomainEvent {

        public static OAuthClientDeactivated of(ExecutionContext ec, OAuthClient c) {
            return new OAuthClientDeactivated(metadataFor(ec, DEACTIVATED, c), c.id());
        }

        @Override
        public Object data() {
            return new Data(oauthClientId);
        }

        private record Data(String oauthClientId) {
        }
    }

    public record OAuthClientDeleted(EventMetadata metadata, String oauthClientId, String clientId) implements DomainEvent {

        public static OAuthClientDeleted of(ExecutionContext ec, OAuthClient c) {
            return new OAuthClientDeleted(metadataFor(ec, DELETED, c), c.id(), c.clientId());
        }

        @Override
        public Object data() {
            return new Data(oauthClientId, clientId);
        }

        private record Data(String oauthClientId, String clientId) {
        }
    }

    /// `previousSecretExpiresAt` is `null` (and omitted on the wire) on an
    /// immediate cutover — the caller's way of telling the two apart.
    public record OAuthClientSecretRotated(EventMetadata metadata, String oauthClientId, Instant previousSecretExpiresAt)
            implements DomainEvent {

        public static OAuthClientSecretRotated of(ExecutionContext ec, OAuthClient c, Instant previousSecretExpiresAt) {
            return new OAuthClientSecretRotated(metadataFor(ec, SECRET_ROTATED, c), c.id(), previousSecretExpiresAt);
        }

        @Override
        public Object data() {
            return new Data(oauthClientId, previousSecretExpiresAt);
        }

        private record Data(String oauthClientId, Instant previousSecretExpiresAt) {
        }
    }

    /// Emitted whether or not an overlap was actually in flight — revoking is
    /// idempotent (spec §6.3, A-22), so the event fires every call.
    public record OAuthClientPreviousSecretRevoked(EventMetadata metadata, String oauthClientId, boolean dropped)
            implements DomainEvent {

        public static OAuthClientPreviousSecretRevoked of(ExecutionContext ec, OAuthClient c, boolean dropped) {
            return new OAuthClientPreviousSecretRevoked(metadataFor(ec, PREVIOUS_SECRET_REVOKED, c), c.id(), dropped);
        }

        @Override
        public Object data() {
            return new Data(oauthClientId, dropped);
        }

        private record Data(String oauthClientId, boolean dropped) {
        }
    }
}
