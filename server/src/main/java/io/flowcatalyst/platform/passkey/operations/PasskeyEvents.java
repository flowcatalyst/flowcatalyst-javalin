package io.flowcatalyst.platform.passkey.operations;

import io.flowcatalyst.platform.passkey.Passkey;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// Passkey events (`docs/spec/auth-identity.md` §7.5 with ruling I-Q25):
/// source `platform:iam`, subject `platform.passkey.<id>`, message group
/// `platform:passkey:<id>`. The event about a principal names it `userId`
/// (the `principalId()` accessor is the actor).
public final class PasskeyEvents {

    public static final String SOURCE = "platform:iam";
    public static final String REGISTERED = "platform:iam:passkey:registered";
    public static final String AUTHENTICATED = "platform:iam:passkey:authenticated";
    public static final String REVOKED = "platform:iam:passkey:revoked";

    private PasskeyEvents() {
    }

    public static String subjectFor(String id) {
        return "platform.passkey." + id;
    }

    public static String groupFor(String id) {
        return "platform:passkey:" + id;
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, Passkey p) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(p.id()));
    }

    public record PasskeyRegistered(EventMetadata metadata, String credentialId, String userId, String name) implements DomainEvent {
        public static PasskeyRegistered of(ExecutionContext ec, Passkey p) {
            return new PasskeyRegistered(metadataFor(ec, REGISTERED, p), p.id(), p.principalId(), p.name());
        }

        @Override
        public String messageGroup() {
            return groupFor(credentialId);
        }

        @Override
        public Object data() {
            return new Data(credentialId, userId, name);
        }

        private record Data(String credentialId, String userId, String name) {
        }
    }

    public record PasskeyAuthenticated(EventMetadata metadata, String credentialId, String userId) implements DomainEvent {
        public static PasskeyAuthenticated of(ExecutionContext ec, Passkey p) {
            return new PasskeyAuthenticated(metadataFor(ec, AUTHENTICATED, p), p.id(), p.principalId());
        }

        @Override
        public String messageGroup() {
            return groupFor(credentialId);
        }

        @Override
        public Object data() {
            return new Data(credentialId, userId);
        }

        private record Data(String credentialId, String userId) {
        }
    }

    public record PasskeyRevoked(EventMetadata metadata, String credentialId, String userId) implements DomainEvent {
        public static PasskeyRevoked of(ExecutionContext ec, Passkey p) {
            return new PasskeyRevoked(metadataFor(ec, REVOKED, p), p.id(), p.principalId());
        }

        @Override
        public String messageGroup() {
            return groupFor(credentialId);
        }

        @Override
        public Object data() {
            return new Data(credentialId, userId);
        }

        private record Data(String credentialId, String userId) {
        }
    }
}
