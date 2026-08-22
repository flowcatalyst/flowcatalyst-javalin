package io.flowcatalyst.platform.application.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ClientConfig;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

/// The application aggregate's domain events (spec §8): the type strings,
/// the source, the subject / message-group builders and one record per
/// event. Every event has a static `of(…)` factory that takes the execution
/// context plus the aggregate, so operations never assemble metadata or
/// payload fields by hand; the `data()` records are the wire payloads,
/// field names verbatim.
///
/// Every per-application event carries message group
/// `platform:application:{id}` so one application's events are delivered
/// in order; the client rollup is grouped per client instead.
public final class ApplicationEvents {

    public static final String SOURCE = "platform:iam";

    public static final String CREATED = "platform:iam:application:created";
    public static final String UPDATED = "platform:iam:application:updated";
    public static final String ACTIVATED = "platform:iam:application:activated";
    public static final String DEACTIVATED = "platform:iam:application:deactivated";
    public static final String DELETED = "platform:iam:application:deleted";
    public static final String SERVICE_ACCOUNT_PROVISIONED = "platform:iam:application:service-account-provisioned";
    public static final String ENABLED_FOR_CLIENT = "platform:iam:application:enabled-for-client";
    public static final String DISABLED_FOR_CLIENT = "platform:iam:application:disabled-for-client";
    public static final String CLIENT_APPLICATIONS_UPDATED = "platform:iam:client:applications-updated";

    private ApplicationEvents() {
    }

    /// `platform.application.{id}` — the subject of every per-application event.
    public static String subjectFor(String applicationId) {
        return EventConventions.buildSubject("platform", "application", applicationId);
    }

    /// `platform:application:{id}` — the message group of every per-application event.
    public static String groupFor(String applicationId) {
        return EventConventions.buildMessageGroup("platform", "application", applicationId);
    }

    /// `platform.client.{clientId}` — the subject of the client rollup.
    public static String clientSubjectFor(String clientId) {
        return EventConventions.buildSubject("platform", "client", clientId);
    }

    /// `platform:client:{clientId}` — the message group of the client rollup.
    public static String clientGroupFor(String clientId) {
        return EventConventions.buildMessageGroup("platform", "client", clientId);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, String applicationId) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(applicationId));
    }

    /// Emitted on create.
    public record ApplicationCreated(EventMetadata metadata, String applicationId, String code, String name)
            implements DomainEvent {

        public static ApplicationCreated of(ExecutionContext ec, Application a) {
            return new ApplicationCreated(metadataFor(ec, CREATED, a.id()), a.id(), a.code(), a.name());
        }

        @Override
        public String messageGroup() {
            return groupFor(applicationId);
        }

        @Override
        public Object data() {
            return new Data(applicationId, code, name);
        }

        private record Data(String applicationId, String code, String name) {
        }
    }

    /// Emitted on update; carries the (possibly unchanged) name.
    public record ApplicationUpdated(EventMetadata metadata, String applicationId, String name) implements DomainEvent {

        public static ApplicationUpdated of(ExecutionContext ec, Application a) {
            return new ApplicationUpdated(metadataFor(ec, UPDATED, a.id()), a.id(), a.name());
        }

        @Override
        public String messageGroup() {
            return groupFor(applicationId);
        }

        @Override
        public Object data() {
            return new Data(applicationId, name);
        }

        private record Data(String applicationId, String name) {
        }
    }

    /// Emitted on activate (also when already active — spec §2).
    public record ApplicationActivated(EventMetadata metadata, String applicationId) implements DomainEvent {

        public static ApplicationActivated of(ExecutionContext ec, Application a) {
            return new ApplicationActivated(metadataFor(ec, ACTIVATED, a.id()), a.id());
        }

        @Override
        public String messageGroup() {
            return groupFor(applicationId);
        }

        @Override
        public Object data() {
            return new Data(applicationId);
        }

        private record Data(String applicationId) {
        }
    }

    /// Emitted on deactivate (also when already inactive — spec §2).
    public record ApplicationDeactivated(EventMetadata metadata, String applicationId) implements DomainEvent {

        public static ApplicationDeactivated of(ExecutionContext ec, Application a) {
            return new ApplicationDeactivated(metadataFor(ec, DEACTIVATED, a.id()), a.id());
        }

        @Override
        public String messageGroup() {
            return groupFor(applicationId);
        }

        @Override
        public Object data() {
            return new Data(applicationId);
        }

        private record Data(String applicationId) {
        }
    }

    /// Emitted on delete.
    public record ApplicationDeleted(EventMetadata metadata, String applicationId, String code) implements DomainEvent {

        public static ApplicationDeleted of(ExecutionContext ec, Application a) {
            return new ApplicationDeleted(metadataFor(ec, DELETED, a.id()), a.id(), a.code());
        }

        @Override
        public String messageGroup() {
            return groupFor(applicationId);
        }

        @Override
        public Object data() {
            return new Data(applicationId, code);
        }

        private record Data(String applicationId, String code) {
        }
    }

    /// Emitted when a service account is attached to (or provisioned for) an
    /// application. `serviceAccountId` is the service-account id from the
    /// command, not the principal id stored on the row (spec §8, open
    /// question 8).
    public record ApplicationServiceAccountProvisioned(EventMetadata metadata, String applicationId,
                                                       String applicationCode, String serviceAccountId,
                                                       String serviceAccountCode) implements DomainEvent {

        public static ApplicationServiceAccountProvisioned of(ExecutionContext ec, Application a,
                                                              String serviceAccountId, String serviceAccountCode) {
            return new ApplicationServiceAccountProvisioned(metadataFor(ec, SERVICE_ACCOUNT_PROVISIONED, a.id()),
                    a.id(), a.code(), serviceAccountId, serviceAccountCode);
        }

        @Override
        public String messageGroup() {
            return groupFor(applicationId);
        }

        @Override
        public Object data() {
            return new Data(applicationId, applicationCode, serviceAccountId, serviceAccountCode);
        }

        private record Data(String applicationId, String applicationCode, String serviceAccountId, String serviceAccountCode) {
        }
    }

    /// Emitted when an application is enabled for a client; `configId` is the
    /// (created or re-enabled) config row.
    public record ApplicationEnabledForClient(EventMetadata metadata, String applicationId, String clientId, String configId)
            implements DomainEvent {

        public static ApplicationEnabledForClient of(ExecutionContext ec, ClientConfig c) {
            return new ApplicationEnabledForClient(metadataFor(ec, ENABLED_FOR_CLIENT, c.applicationId()),
                    c.applicationId(), c.clientId(), c.id());
        }

        @Override
        public String messageGroup() {
            return groupFor(applicationId);
        }

        @Override
        public Object data() {
            return new Data(applicationId, clientId, configId);
        }

        private record Data(String applicationId, String clientId, String configId) {
        }
    }

    /// Emitted when an application is disabled for a client (also when it
    /// already was — spec §2).
    public record ApplicationDisabledForClient(EventMetadata metadata, String applicationId, String clientId, String configId)
            implements DomainEvent {

        public static ApplicationDisabledForClient of(ExecutionContext ec, ClientConfig c) {
            return new ApplicationDisabledForClient(metadataFor(ec, DISABLED_FOR_CLIENT, c.applicationId()),
                    c.applicationId(), c.clientId(), c.id());
        }

        @Override
        public String messageGroup() {
            return groupFor(applicationId);
        }

        @Override
        public Object data() {
            return new Data(applicationId, clientId, configId);
        }

        private record Data(String applicationId, String clientId, String configId) {
        }
    }

    /// The rollup emitted by [UpdateClientApplications] (spec §7): subject
    /// `platform.client.{clientId}`, message group `platform:client:{clientId}`.
    /// `enabledApplicationIds` is the requested final set verbatim;
    /// `enabledAdded` / `disabledRemoved` are the diff actually applied.
    public record ClientApplicationsUpdated(EventMetadata metadata, String clientId, List<String> enabledApplicationIds,
                                            List<String> enabledAdded, List<String> disabledRemoved) implements DomainEvent {

        public ClientApplicationsUpdated {
            enabledApplicationIds = enabledApplicationIds == null ? List.of() : List.copyOf(enabledApplicationIds);
            enabledAdded = enabledAdded == null ? List.of() : List.copyOf(enabledAdded);
            disabledRemoved = disabledRemoved == null ? List.of() : List.copyOf(disabledRemoved);
        }

        public static ClientApplicationsUpdated of(ExecutionContext ec, String clientId, List<String> enabledApplicationIds,
                                                   List<String> enabledAdded, List<String> disabledRemoved) {
            return new ClientApplicationsUpdated(EventMetadata.of(ec, CLIENT_APPLICATIONS_UPDATED, SOURCE, clientSubjectFor(clientId)),
                    clientId, enabledApplicationIds, enabledAdded, disabledRemoved);
        }

        @Override
        public String messageGroup() {
            return clientGroupFor(clientId);
        }

        @Override
        public Object data() {
            return new Data(clientId, enabledApplicationIds, enabledAdded, disabledRemoved);
        }

        private record Data(String clientId, List<String> enabledApplicationIds, List<String> enabledAdded,
                            List<String> disabledRemoved) {
        }
    }
}
