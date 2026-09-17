package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;
import java.util.Map;

/// The principal aggregate's domain events (spec §8): the type strings,
/// the source, the subject/group builders and one record per event. Every
/// event has a static `of(…)` factory taking the execution context plus the
/// aggregate, so operations never assemble metadata or payloads by hand;
/// the `data()` records are the wire payloads, field names verbatim.
///
/// The subject of every event names the principal the event is *about*
/// (`principalId` / `userId` in the payload); the actor is always the
/// metadata's principal id, via [DomainEvent#principalId()]. Every record
/// component here is named `userId`, never `principalId`, so it cannot
/// shadow that inherited accessor — a guard test enforces this for every
/// `DomainEvent` in the codebase. No payload ever carries a password, a
/// hash or a secret.
public final class PrincipalEvents {

    public static final String SOURCE = "platform:iam";

    public static final String USER_CREATED = "platform:iam:user:created";
    public static final String USER_UPDATED = "platform:iam:user:updated";
    public static final String USER_ACTIVATED = "platform:iam:user:activated";
    public static final String USER_DEACTIVATED = "platform:iam:user:deactivated";
    public static final String USER_DELETED = "platform:iam:user:deleted";
    public static final String PASSWORD_RESET = "platform:iam:user:password-reset-completed";
    public static final String ROLES_ASSIGNED = "platform:iam:user:roles-assigned";
    public static final String APPLICATION_ACCESS_ASSIGNED = "platform:iam:user:application-access-assigned";
    public static final String CLIENT_ACCESS_GRANTED = "platform:iam:user:client-access-granted";
    public static final String CLIENT_ACCESS_REVOKED = "platform:iam:user:client-access-revoked";
    public static final String DEVELOPER_CREDENTIAL_SET = "platform:iam:user:developer-credential-set";
    public static final String DEVELOPER_CREDENTIAL_REVOKED = "platform:iam:user:developer-credential-revoked";
    public static final String USER_LOGGED_IN = "platform:iam:user:logged-in";
    public static final String PRINCIPALS_SYNCED = "platform:iam:principals:synced";

    private PrincipalEvents() {
    }

    /// `platform.principal.{id}` — the subject of every per-aggregate event.
    public static String subjectFor(String principalId) {
        return EventConventions.buildSubject("platform", "principal", principalId);
    }

    /// `platform:principal:{id}` — one principal's events are delivered in order.
    public static String groupFor(String principalId) {
        return EventConventions.buildMessageGroup("platform", "principal", principalId);
    }

    /// `platform.principals` or `platform.principals.{applicationCode}` — the sync rollup's subject.
    public static String syncSubjectFor(String applicationCode) {
        return applicationCode == null || applicationCode.isEmpty()
                ? "platform.principals" : EventConventions.buildSubject("platform", "principals", applicationCode);
    }

    private static EventMetadata metadataFor(ExecutionContext ec, String type, String principalId) {
        return EventMetadata.of(ec, type, SOURCE, subjectFor(principalId)).withMessageGroup(groupFor(principalId));
    }

    /// Emitted on create (admin, portal, and per new row by sync).
    public record UserCreated(EventMetadata metadata, String userId, String email) implements DomainEvent {

        public static UserCreated of(ExecutionContext ec, Principal p) {
            return new UserCreated(metadataFor(ec, USER_CREATED, p.id()), p.id(), p.email());
        }

        @Override
        public Object data() {
            return new Data(userId, email);
        }

        private record Data(String principalId, String email) {
        }
    }

    /// Emitted on the admin update, on a scope/client change, and per existing row by sync.
    public record UserUpdated(EventMetadata metadata, String userId, String name) implements DomainEvent {

        public static UserUpdated of(ExecutionContext ec, Principal p) {
            return new UserUpdated(metadataFor(ec, USER_UPDATED, p.id()), p.id(), p.name());
        }

        @Override
        public Object data() {
            return new Data(userId, name);
        }

        private record Data(String principalId, String name) {
        }
    }

    public record UserActivated(EventMetadata metadata, String userId) implements DomainEvent {

        public static UserActivated of(ExecutionContext ec, Principal p) {
            return new UserActivated(metadataFor(ec, USER_ACTIVATED, p.id()), p.id());
        }

        @Override
        public Object data() {
            return new PrincipalOnly(userId);
        }
    }

    public record UserDeactivated(EventMetadata metadata, String userId) implements DomainEvent {

        public static UserDeactivated of(ExecutionContext ec, Principal p) {
            return new UserDeactivated(metadataFor(ec, USER_DEACTIVATED, p.id()), p.id());
        }

        @Override
        public Object data() {
            return new PrincipalOnly(userId);
        }
    }

    /// Emitted on delete; `email` is `""` for a service principal (the payload keeps the key).
    public record UserDeleted(EventMetadata metadata, String userId, String email) implements DomainEvent {

        public static UserDeleted of(ExecutionContext ec, Principal p) {
            return new UserDeleted(metadataFor(ec, USER_DELETED, p.id()), p.id(), p.email());
        }

        @Override
        public Object data() {
            return new Data(userId, email == null ? "" : email);
        }

        private record Data(String principalId, String email) {
        }
    }

    /// Emitted when a password is set by an administrator or the reset flow — never the password.
    public record UserPasswordReset(EventMetadata metadata, String userId) implements DomainEvent {

        public static UserPasswordReset of(ExecutionContext ec, Principal p) {
            return new UserPasswordReset(metadataFor(ec, PASSWORD_RESET, p.id()), p.id());
        }

        @Override
        public Object data() {
            return new PrincipalOnly(userId);
        }
    }

    /// Emitted when the role set changes (admin assignment or IdP sync):
    /// the resulting names plus what entered and left.
    public record RolesAssigned(EventMetadata metadata, String userId, List<String> roles, List<String> added,
                                List<String> removed) implements DomainEvent {

        public RolesAssigned {
            roles = roles == null ? List.of() : List.copyOf(roles);
            added = added == null ? List.of() : List.copyOf(added);
            removed = removed == null ? List.of() : List.copyOf(removed);
        }

        public static RolesAssigned of(ExecutionContext ec, Principal.RolesChanged change) {
            return new RolesAssigned(metadataFor(ec, ROLES_ASSIGNED, change.principal().id()), change.principal().id(),
                    change.roles(), change.added(), change.removed());
        }

        @Override
        public Object data() {
            return new Data(userId, roles, added, removed);
        }

        private record Data(String principalId, List<String> roles, List<String> added, List<String> removed) {
        }
    }

    /// Emitted when the explicit application set changes; on the wire the
    /// subject is `userId` (the frontend's vocabulary).
    public record ApplicationAccessAssigned(EventMetadata metadata, String userId, List<String> applicationIds,
                                            List<String> added, List<String> removed) implements DomainEvent {

        public ApplicationAccessAssigned {
            applicationIds = applicationIds == null ? List.of() : List.copyOf(applicationIds);
            added = added == null ? List.of() : List.copyOf(added);
            removed = removed == null ? List.of() : List.copyOf(removed);
        }

        public static ApplicationAccessAssigned of(ExecutionContext ec, Principal.AccessChanged change) {
            Principal p = change.principal();
            return new ApplicationAccessAssigned(metadataFor(ec, APPLICATION_ACCESS_ASSIGNED, p.id()), p.id(),
                    p.accessibleApplicationIds(), change.added(), change.removed());
        }

        @Override
        public Object data() {
            return new Data(userId, applicationIds, added, removed);
        }

        private record Data(String userId, List<String> applicationIds, List<String> added, List<String> removed) {
        }
    }

    public record ClientAccessGranted(EventMetadata metadata, String userId, String clientId) implements DomainEvent {

        public static ClientAccessGranted of(ExecutionContext ec, Principal p, String clientId) {
            return new ClientAccessGranted(metadataFor(ec, CLIENT_ACCESS_GRANTED, p.id()), p.id(), clientId);
        }

        @Override
        public Object data() {
            return new ClientData(userId, clientId);
        }
    }

    public record ClientAccessRevoked(EventMetadata metadata, String userId, String clientId) implements DomainEvent {

        public static ClientAccessRevoked of(ExecutionContext ec, Principal p, String clientId) {
            return new ClientAccessRevoked(metadataFor(ec, CLIENT_ACCESS_REVOKED, p.id()), p.id(), clientId);
        }

        @Override
        public Object data() {
            return new ClientData(userId, clientId);
        }
    }

    /// Emitted when a developer client-secret is created or rotated — never the secret.
    public record DeveloperCredentialSet(EventMetadata metadata, String userId) implements DomainEvent {

        public static DeveloperCredentialSet of(ExecutionContext ec, Principal p) {
            return new DeveloperCredentialSet(metadataFor(ec, DEVELOPER_CREDENTIAL_SET, p.id()), p.id());
        }

        @Override
        public Object data() {
            return new UserOnly(userId);
        }
    }

    public record DeveloperCredentialRevoked(EventMetadata metadata, String userId) implements DomainEvent {

        public static DeveloperCredentialRevoked of(ExecutionContext ec, Principal p) {
            return new DeveloperCredentialRevoked(metadataFor(ec, DEVELOPER_CREDENTIAL_REVOKED, p.id()), p.id());
        }

        @Override
        public Object data() {
            return new UserOnly(userId);
        }
    }

    /// `platform.user.{id}` — [UserLoggedIn]'s subject, deliberately the
    /// "user" vocabulary rather than [#subjectFor(String)]'s "principal":
    /// it matches the event type's own name (`platform:iam:user:logged-in`),
    /// spec `docs/spec/oidc-logged-in-event.md`.
    private static EventMetadata loggedInMetadata(ExecutionContext ec, String userId) {
        String subject = EventConventions.buildSubject("platform", "user", userId);
        return EventMetadata.of(ec, USER_LOGGED_IN, SOURCE, subject)
                .withMessageGroup(EventConventions.buildMessageGroup("platform", "user", userId));
    }

    /// Emitted once, after a successful **OIDC** login's session token is
    /// minted (spec `docs/spec/oidc-logged-in-event.md`) — password, 2FA and
    /// passkey logins never emit this. Best-effort: a failure to emit is
    /// logged and swallowed by the caller so the login itself never fails
    /// because of it.
    public record UserLoggedIn(EventMetadata metadata, String userId, String email, String loginMethod,
                               String identityProviderCode, FlowcatalystClaims flowcatalystClaims,
                               FederatedClaims federatedClaims) implements DomainEvent {

        public static final String LOGIN_METHOD_OIDC = "OIDC";

        public static UserLoggedIn of(ExecutionContext ec, String userId, String email, String identityProviderCode,
                                      FlowcatalystClaims flowcatalystClaims, FederatedClaims federatedClaims) {
            return new UserLoggedIn(loggedInMetadata(ec, userId), userId, email, LOGIN_METHOD_OIDC, identityProviderCode,
                    flowcatalystClaims, federatedClaims);
        }

        @Override
        public Object data() {
            return new Data(userId, email, loginMethod, identityProviderCode, flowcatalystClaims, federatedClaims);
        }

        private record Data(String userId, String email, String loginMethod, String identityProviderCode,
                            FlowcatalystClaims flowcatalystClaims, FederatedClaims federatedClaims) {
        }
    }

    /// [UserLoggedIn]'s FlowCatalyst claims: `roles` is the principal's role
    /// set **after** this login's IdP role sync (the caller re-reads the
    /// principal — never the pre-sync copy); `clients` is `["*"]` for an
    /// anchor-scope principal, else its assigned (granted) client ids;
    /// `applications` is the distinct, sorted set of role-name prefixes
    /// before each role's first `:` (a role with no `:` contributes none).
    public record FlowcatalystClaims(String email, String type, List<String> roles, List<String> clients,
                                     List<String> applications) {
        public FlowcatalystClaims {
            roles = roles == null ? List.of() : List.copyOf(roles);
            clients = clients == null ? List.of() : List.copyOf(clients);
            applications = applications == null ? List.of() : List.copyOf(applications);
        }
    }

    /// [UserLoggedIn]'s federated claims: the verified id_token's full claim
    /// set (`idToken`, minus `nonce`/`at_hash`/`c_hash` — the OIDC bridge's
    /// `IdTokenClaims` already strips them) and the access token's payload
    /// decoded **without** verification (`accessToken`, `{}` for an opaque
    /// token, see `OidcProvider.decodeUnverifiedPayload`). The raw token
    /// strings never appear here.
    public record FederatedClaims(Map<String, Object> idToken, Map<String, Object> accessToken) {
        public FederatedClaims {
            idToken = idToken == null ? Map.of() : Map.copyOf(idToken);
            accessToken = accessToken == null ? Map.of() : Map.copyOf(accessToken);
        }
    }

    /// The rollup emitted by [SyncPrincipals]: counts plus the synced emails;
    /// `deactivated` counts principals stripped of their `SDK_SYNC` roles.
    /// Message group `platform:principals` so syncs are delivered in order.
    public record PrincipalsSynced(EventMetadata metadata, String applicationCode, int created, int updated,
                                   int deactivated, List<String> syncedEmails) implements DomainEvent {

        public PrincipalsSynced {
            syncedEmails = syncedEmails == null ? List.of() : List.copyOf(syncedEmails);
        }

        public static PrincipalsSynced of(ExecutionContext ec, String applicationCode, int created, int updated,
                                          int deactivated, List<String> syncedEmails) {
            return new PrincipalsSynced(EventMetadata.of(ec, PRINCIPALS_SYNCED, SOURCE, syncSubjectFor(applicationCode))
                    .withMessageGroup("platform:principals"), applicationCode, created, updated, deactivated, syncedEmails);
        }

        @Override
        public Object data() {
            return new Data(applicationCode == null ? "" : applicationCode, created, updated, deactivated, syncedEmails);
        }

        private record Data(String applicationCode, int created, int updated, int deactivated, List<String> syncedEmails) {
        }
    }

    // ── Shared payload shapes ──────────────────────────────────────────────

    private record PrincipalOnly(String principalId) {
    }

    private record UserOnly(String userId) {
    }

    private record ClientData(String principalId, String clientId) {
    }
}
