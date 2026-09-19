package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.ClientPolicy;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The function aggregate's domain events, function/policy slice only (spec
/// `function-api.md` §3 — version/alias events are B2/B3). Source
/// `platform:function`; subject `platform.function.{id}`; message group
/// `platform:function:{id}` for every event, so one aggregate's history is
/// ordered (spec §3): `{id}` is the function id for the three function
/// events, and the policy owner's [io.flowcatalyst.platform.function.FunctionOwner#key]
/// for [PolicyUpdated] — a policy is not itself a function, but it lives in
/// this same aggregate family and needs the identical ordering guarantee for
/// its own history.
public final class FunctionEvents {

    public static final String SOURCE = "platform:function";

    public static final String CREATED = "platform:function:function:created";
    public static final String UPDATED = "platform:function:function:updated";
    public static final String DELETED = "platform:function:function:deleted";
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

    /// `{owner, signerCount}` — never the signer list itself: counts, not
    /// contents (spec §3).
    public record PolicyUpdated(EventMetadata metadata, String owner, int signerCount) implements DomainEvent {

        public static PolicyUpdated of(ExecutionContext ec, ClientPolicy p) {
            return new PolicyUpdated(metadataFor(ec, POLICY_UPDATED, p.owner().key()), p.owner().toWire(), p.signers().size());
        }

        @Override
        public Object data() {
            return new Data(owner, signerCount);
        }

        private record Data(String owner, int signerCount) {
        }
    }
}
