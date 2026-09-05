package io.flowcatalyst.platform.authadmin.operations;

import io.flowcatalyst.platform.authadmin.AnchorDomain;
import io.flowcatalyst.platform.authadmin.ClientAuthConfig;
import io.flowcatalyst.platform.authadmin.IdpRoleMapping;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The three aggregates' domain events (spec §5): the type strings, the
/// source, the subject / message-group builders and one record per event.
/// Every event has a static `of(…)` factory that takes the execution
/// context plus the aggregate, so operations never assemble metadata or
/// payload fields by hand; the `data()` records are the wire payloads,
/// field names verbatim. Every event carries a per-aggregate-instance
/// message group so one row's events are delivered in order.
public final class AuthAdminEvents {

    public static final String SOURCE = "platform:admin";

    public static final String ANCHOR_DOMAIN_CREATED = "platform:admin:anchor-domain:created";
    public static final String ANCHOR_DOMAIN_UPDATED = "platform:admin:anchor-domain:updated";
    public static final String ANCHOR_DOMAIN_DELETED = "platform:admin:anchor-domain:deleted";
    public static final String AUTH_CONFIG_CREATED = "platform:admin:auth-config:created";
    public static final String AUTH_CONFIG_UPDATED = "platform:admin:auth-config:updated";
    public static final String AUTH_CONFIG_DELETED = "platform:admin:auth-config:deleted";
    public static final String IDP_ROLE_MAPPING_CREATED = "platform:admin:idp-role-mapping:created";
    public static final String IDP_ROLE_MAPPING_DELETED = "platform:admin:idp-role-mapping:deleted";

    private AuthAdminEvents() {
    }

    // ── Subjects / message groups ───────────────────────────────────────────

    public static String anchorDomainSubject(String id) {
        return EventConventions.buildSubject("platform", "anchordomain", id);
    }

    public static String anchorDomainMessageGroup(String id) {
        return EventConventions.buildMessageGroup("platform", "anchordomain", id);
    }

    public static String authConfigSubject(String id) {
        return EventConventions.buildSubject("platform", "authconfig", id);
    }

    public static String authConfigMessageGroup(String id) {
        return EventConventions.buildMessageGroup("platform", "authconfig", id);
    }

    public static String idpRoleMappingSubject(String id) {
        return EventConventions.buildSubject("platform", "idprolemapping", id);
    }

    public static String idpRoleMappingMessageGroup(String id) {
        return EventConventions.buildMessageGroup("platform", "idprolemapping", id);
    }

    // ── Anchor domain events ─────────────────────────────────────────────────

    /// Emitted on create.
    public record AnchorDomainCreated(EventMetadata metadata, String anchorDomainId, String domain) implements DomainEvent {

        public static AnchorDomainCreated of(ExecutionContext ec, AnchorDomain a) {
            return new AnchorDomainCreated(EventMetadata.of(ec, ANCHOR_DOMAIN_CREATED, SOURCE, anchorDomainSubject(a.id())), a.id(), a.domain());
        }

        @Override
        public String messageGroup() {
            return anchorDomainMessageGroup(anchorDomainId);
        }

        @Override
        public Object data() {
            return new Data(anchorDomainId, domain);
        }

        private record Data(String anchorDomainId, String domain) {
        }
    }

    /// Emitted on update.
    public record AnchorDomainUpdated(EventMetadata metadata, String anchorDomainId, String domain) implements DomainEvent {

        public static AnchorDomainUpdated of(ExecutionContext ec, AnchorDomain a) {
            return new AnchorDomainUpdated(EventMetadata.of(ec, ANCHOR_DOMAIN_UPDATED, SOURCE, anchorDomainSubject(a.id())), a.id(), a.domain());
        }

        @Override
        public String messageGroup() {
            return anchorDomainMessageGroup(anchorDomainId);
        }

        @Override
        public Object data() {
            return new Data(anchorDomainId, domain);
        }

        private record Data(String anchorDomainId, String domain) {
        }
    }

    /// Emitted on delete.
    public record AnchorDomainDeleted(EventMetadata metadata, String anchorDomainId, String domain) implements DomainEvent {

        public static AnchorDomainDeleted of(ExecutionContext ec, AnchorDomain a) {
            return new AnchorDomainDeleted(EventMetadata.of(ec, ANCHOR_DOMAIN_DELETED, SOURCE, anchorDomainSubject(a.id())), a.id(), a.domain());
        }

        @Override
        public String messageGroup() {
            return anchorDomainMessageGroup(anchorDomainId);
        }

        @Override
        public Object data() {
            return new Data(anchorDomainId, domain);
        }

        private record Data(String anchorDomainId, String domain) {
        }
    }

    // ── Auth config events ───────────────────────────────────────────────────

    /// Emitted on create.
    public record AuthConfigCreated(EventMetadata metadata, String authConfigId, String emailDomain) implements DomainEvent {

        public static AuthConfigCreated of(ExecutionContext ec, ClientAuthConfig c) {
            return new AuthConfigCreated(EventMetadata.of(ec, AUTH_CONFIG_CREATED, SOURCE, authConfigSubject(c.id())), c.id(), c.emailDomain());
        }

        @Override
        public String messageGroup() {
            return authConfigMessageGroup(authConfigId);
        }

        @Override
        public Object data() {
            return new Data(authConfigId, emailDomain);
        }

        private record Data(String authConfigId, String emailDomain) {
        }
    }

    /// Emitted on update.
    public record AuthConfigUpdated(EventMetadata metadata, String authConfigId, String emailDomain) implements DomainEvent {

        public static AuthConfigUpdated of(ExecutionContext ec, ClientAuthConfig c) {
            return new AuthConfigUpdated(EventMetadata.of(ec, AUTH_CONFIG_UPDATED, SOURCE, authConfigSubject(c.id())), c.id(), c.emailDomain());
        }

        @Override
        public String messageGroup() {
            return authConfigMessageGroup(authConfigId);
        }

        @Override
        public Object data() {
            return new Data(authConfigId, emailDomain);
        }

        private record Data(String authConfigId, String emailDomain) {
        }
    }

    /// Emitted on delete.
    public record AuthConfigDeleted(EventMetadata metadata, String authConfigId, String emailDomain) implements DomainEvent {

        public static AuthConfigDeleted of(ExecutionContext ec, ClientAuthConfig c) {
            return new AuthConfigDeleted(EventMetadata.of(ec, AUTH_CONFIG_DELETED, SOURCE, authConfigSubject(c.id())), c.id(), c.emailDomain());
        }

        @Override
        public String messageGroup() {
            return authConfigMessageGroup(authConfigId);
        }

        @Override
        public Object data() {
            return new Data(authConfigId, emailDomain);
        }

        private record Data(String authConfigId, String emailDomain) {
        }
    }

    // ── IdP role mapping events ──────────────────────────────────────────────

    /// Emitted on create.
    public record IdpRoleMappingCreated(EventMetadata metadata, String mappingId, String idpType, String idpRoleName,
                                        String platformRoleName) implements DomainEvent {

        public static IdpRoleMappingCreated of(ExecutionContext ec, IdpRoleMapping m) {
            return new IdpRoleMappingCreated(EventMetadata.of(ec, IDP_ROLE_MAPPING_CREATED, SOURCE, idpRoleMappingSubject(m.id())),
                    m.id(), m.idpType(), m.idpRoleName(), m.platformRoleName());
        }

        @Override
        public String messageGroup() {
            return idpRoleMappingMessageGroup(mappingId);
        }

        @Override
        public Object data() {
            return new Data(mappingId, idpType, idpRoleName, platformRoleName);
        }

        private record Data(String mappingId, String idpType, String idpRoleName, String platformRoleName) {
        }
    }

    /// Emitted on delete; carries the same fields as [IdpRoleMappingCreated] (spec §5).
    public record IdpRoleMappingDeleted(EventMetadata metadata, String mappingId, String idpType, String idpRoleName,
                                        String platformRoleName) implements DomainEvent {

        public static IdpRoleMappingDeleted of(ExecutionContext ec, IdpRoleMapping m) {
            return new IdpRoleMappingDeleted(EventMetadata.of(ec, IDP_ROLE_MAPPING_DELETED, SOURCE, idpRoleMappingSubject(m.id())),
                    m.id(), m.idpType(), m.idpRoleName(), m.platformRoleName());
        }

        @Override
        public String messageGroup() {
            return idpRoleMappingMessageGroup(mappingId);
        }

        @Override
        public Object data() {
            return new Data(mappingId, idpType, idpRoleName, platformRoleName);
        }

        private record Data(String mappingId, String idpType, String idpRoleName, String platformRoleName) {
        }
    }
}
