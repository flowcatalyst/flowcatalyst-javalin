package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.ClientPolicy;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The function aggregate's domain events (spec `function-api.md` §3).
/// Source `platform:function`; subject `platform.function.{id}`; message
/// group `platform:function:{id}` for every event below EXCEPT
/// [PolicyUpdated] (spec §3): `{id}` is the function id for the
/// function/version/alias events. The policy event has no function — its
/// subject is `platform.function-policy.{owner key}` and its group
/// `platform:function-policy:{owner key}` (spec §3's parenthesis), built by
/// [#policyMetadataFor] from [io.flowcatalyst.platform.function.FunctionOwner#key],
/// never [#metadataFor].
public final class FunctionEvents {

    public static final String SOURCE = "platform:function";

    public static final String CREATED = "platform:function:function:created";
    public static final String UPDATED = "platform:function:function:updated";
    public static final String DELETED = "platform:function:function:deleted";
    public static final String VERSION_READY = "platform:function:version:ready";
    public static final String POLICY_UPDATED = "platform:function:policy:updated";

    private FunctionEvents() {
    }

    private static String subjectFor(String id) {
        return EventConventions.buildSubject("platform", "function", id);
    }

    private static String messageGroupFor(String id) {
        return EventConventions.buildMessageGroup("platform", "function", id);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, String id) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(id)).withMessageGroup(messageGroupFor(id));
    }

    /// [PolicyUpdated] alone (spec §3's parenthesis): subject
    /// `platform.function-policy.{ownerKey}`, group
    /// `platform:function-policy:{ownerKey}` — never [#metadataFor], which
    /// would group a policy write into the (nonexistent) function's stream.
    private static EventMetadata policyMetadataFor(ExecutionContext ec, String ownerKey) {
        String subject = EventConventions.buildSubject("platform", "function-policy", ownerKey);
        String messageGroup = EventConventions.buildMessageGroup("platform", "function-policy", ownerKey);
        return EventMetadata.of(ec, POLICY_UPDATED, SOURCE, subject).withMessageGroup(messageGroup);
    }

    /// `{functionId, address, applicationId, clientId?, runtime}` (spec §3).
    public record FunctionCreated(EventMetadata metadata, String functionId, String address, String applicationId,
                                  String clientId, String runtime) implements DomainEvent {

        public static FunctionCreated of(ExecutionContext ec, Function f) {
            return new FunctionCreated(metadataFor(ec, CREATED, f.id()), f.id(), f.address().render(),
                    f.applicationId(), f.owner().clientIdOrNull(), f.runtime().name());
        }

        @Override
        public Object data() {
            return new Data(functionId, address, applicationId, clientId, runtime);
        }

        private record Data(String functionId, String address, String applicationId, String clientId, String runtime) {
        }
    }

    /// `{functionId, address, description?, status}` (spec §3).
    public record FunctionUpdated(EventMetadata metadata, String functionId, String address, String description,
                                  String status) implements DomainEvent {

        public static FunctionUpdated of(ExecutionContext ec, Function f) {
            return new FunctionUpdated(metadataFor(ec, UPDATED, f.id()), f.id(), f.address().render(),
                    f.description(), f.status().name());
        }

        @Override
        public Object data() {
            return new Data(functionId, address, description, status);
        }

        private record Data(String functionId, String address, String description, String status) {
        }
    }

    /// `{functionId, address}` (spec §3).
    public record FunctionDeleted(EventMetadata metadata, String functionId, String address) implements DomainEvent {

        public static FunctionDeleted of(ExecutionContext ec, Function f) {
            return new FunctionDeleted(metadataFor(ec, DELETED, f.id()), f.id(), f.address().render());
        }

        @Override
        public Object data() {
            return new Data(functionId, address);
        }

        private record Data(String functionId, String address) {
        }
    }

    /// `{functionId, address, versionId, version, hostId}` (spec §3) — a
    /// host's heartbeat marking a version `READY` (`MarkVersionReady`, spec
    /// §6.2). Grouped with the function's own message group, NOT the
    /// policy's carve-out (spec §3: "for every event below" excludes only
    /// [PolicyUpdated]).
    public record VersionReady(EventMetadata metadata, String functionId, String address, String versionId,
                               int version, String hostId) implements DomainEvent {

        public static VersionReady of(ExecutionContext ec, Function f, FunctionVersion v, String hostId) {
            return new VersionReady(metadataFor(ec, VERSION_READY, f.id()), f.id(), f.address().render(), v.id(),
                    v.version(), hostId);
        }

        @Override
        public Object data() {
            return new Data(functionId, address, versionId, version, hostId);
        }

        private record Data(String functionId, String address, String versionId, int version, String hostId) {
        }
    }

    /// `{owner, signerCount}` — never the signer list itself: counts, not
    /// contents (spec §3).
    public record PolicyUpdated(EventMetadata metadata, String owner, int signerCount) implements DomainEvent {

        public static PolicyUpdated of(ExecutionContext ec, ClientPolicy p) {
            return new PolicyUpdated(policyMetadataFor(ec, p.owner().key()), p.owner().toWire(), p.signers().size());
        }

        @Override
        public Object data() {
            return new Data(owner, signerCount);
        }

        private record Data(String owner, int signerCount) {
        }
    }
}
