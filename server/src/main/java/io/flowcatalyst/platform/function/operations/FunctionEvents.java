package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.ClientPolicy;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionDomain;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventConventions;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

import java.util.List;

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
    public static final String VERSION_PUBLISHED = "platform:function:version:published";
    public static final String VERSION_READY = "platform:function:version:ready";
    public static final String VERSION_RETIRED = "platform:function:version:retired";
    public static final String ALIAS_CHANGED = "platform:function:alias:changed";
    public static final String ALIAS_REMOVED = "platform:function:alias:removed";
    public static final String POLICY_UPDATED = "platform:function:policy:updated";
    public static final String CONFIG_UPDATED = "platform:function:config:updated";
    public static final String SECRET_SET = "platform:function:secret:set";
    public static final String SECRET_DELETED = "platform:function:secret:deleted";

    /// spec `function-public-routes.md` §1 — the domain aggregate's own
    /// event types. Subject `platform.function-domain.{domainId}`, group
    /// `platform:function-domain:{domainId}` ([#domainMetadataFor]) — a
    /// domain is not a function, so it gets its own message-group namespace,
    /// the same treatment [#POLICY_UPDATED] gets via [#policyMetadataFor].
    public static final String DOMAIN_CLAIMED = "platform:function:domain:claimed";
    public static final String DOMAIN_VERIFIED = "platform:function:domain:verified";
    public static final String DOMAIN_RELEASED = "platform:function:domain:released";

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

    /// [DomainClaimed] / [DomainVerified] / [DomainReleased]'s metadata:
    /// subject `platform.function-domain.{domainId}`, group
    /// `platform:function-domain:{domainId}` — never [#metadataFor], which
    /// would group a domain event into a (nonexistent) function's stream.
    private static EventMetadata domainMetadataFor(ExecutionContext ec, String type, String domainId) {
        String subject = EventConventions.buildSubject("platform", "function-domain", domainId);
        String messageGroup = EventConventions.buildMessageGroup("platform", "function-domain", domainId);
        return EventMetadata.of(ec, type, SOURCE, subject).withMessageGroup(messageGroup);
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

    /// `{functionId, address, versionId, version, digest, pool, signerIssuer?,
    /// signerSubject?}` (spec §3) — `PublishVersion` (spec §5.1). The signer
    /// pair is absent together: a version published with [Signatures.Off]
    /// carries neither.
    public record VersionPublished(EventMetadata metadata, String functionId, String address, String versionId,
                                   int version, String digest, String pool, String signerIssuer, String signerSubject)
            implements DomainEvent {

        public static VersionPublished of(ExecutionContext ec, Function f, FunctionVersion v) {
            String issuer = v.signer() == null ? null : v.signer().issuer();
            String subject = v.signer() == null ? null : v.signer().subject();
            return new VersionPublished(metadataFor(ec, VERSION_PUBLISHED, f.id()), f.id(), f.address().render(),
                    v.id(), v.version(), v.digest().value(), v.manifest().pool().value(), issuer, subject);
        }

        @Override
        public Object data() {
            return new Data(functionId, address, versionId, version, digest, pool, signerIssuer, signerSubject);
        }

        private record Data(String functionId, String address, String versionId, int version, String digest,
                            String pool, String signerIssuer, String signerSubject) {
        }
    }

    /// `{functionId, address, versionId, version}` (spec §3) — `RetireVersion`
    /// (spec §5.2).
    public record VersionRetired(EventMetadata metadata, String functionId, String address, String versionId,
                                 int version) implements DomainEvent {

        public static VersionRetired of(ExecutionContext ec, Function f, FunctionVersion v) {
            return new VersionRetired(metadataFor(ec, VERSION_RETIRED, f.id()), f.id(), f.address().render(), v.id(),
                    v.version());
        }

        @Override
        public Object data() {
            return new Data(functionId, address, versionId, version);
        }

        private record Data(String functionId, String address, String versionId, int version) {
        }
    }

    /// `{functionId, address, alias, versionId, version, previousVersionId?}`
    /// (spec §3) — `PromoteVersion` (spec §5.2); `previousVersionId` is
    /// `null` on a first promotion ([Function.Promoted#previousVersionId]).
    public record AliasChanged(EventMetadata metadata, String functionId, String address, String alias,
                               String versionId, int version, String previousVersionId) implements DomainEvent {

        public static AliasChanged of(ExecutionContext ec, Function f, String alias, FunctionVersion v,
                String previousVersionId) {
            return new AliasChanged(metadataFor(ec, ALIAS_CHANGED, f.id()), f.id(), f.address().render(), alias,
                    v.id(), v.version(), previousVersionId);
        }

        @Override
        public Object data() {
            return new Data(functionId, address, alias, versionId, version, previousVersionId);
        }

        private record Data(String functionId, String address, String alias, String versionId, int version,
                            String previousVersionId) {
        }
    }

    /// `{functionId, address, keys}` (spec `function-context.md` §1) —
    /// `SetFunctionConfig`. **Keys only, never values** — a config value is
    /// not secret, but the event still never carries it (spec §1's PUT is a
    /// full replacement; the event names what changed, not what it holds).
    public record ConfigUpdated(EventMetadata metadata, String functionId, String address,
                                List<String> keys) implements DomainEvent {

        public static ConfigUpdated of(ExecutionContext ec, Function f, java.util.Collection<String> keys) {
            return new ConfigUpdated(metadataFor(ec, CONFIG_UPDATED, f.id()), f.id(), f.address().render(),
                    List.copyOf(keys));
        }

        @Override
        public Object data() {
            return new Data(functionId, address, keys);
        }

        private record Data(String functionId, String address, List<String> keys) {
        }
    }

    /// `{functionId, address, key}` (spec §1) — `SetFunctionSecret`. **The
    /// key only** — spec §1 X1: a secret value is in no event, ever.
    public record SecretSet(EventMetadata metadata, String functionId, String address, String key)
            implements DomainEvent {

        public static SecretSet of(ExecutionContext ec, Function f, String key) {
            return new SecretSet(metadataFor(ec, SECRET_SET, f.id()), f.id(), f.address().render(), key);
        }

        @Override
        public Object data() {
            return new Data(functionId, address, key);
        }

        private record Data(String functionId, String address, String key) {
        }
    }

    /// `{functionId, address, key}` (spec §1) — `DeleteFunctionSecret`.
    public record SecretDeleted(EventMetadata metadata, String functionId, String address, String key)
            implements DomainEvent {

        public static SecretDeleted of(ExecutionContext ec, Function f, String key) {
            return new SecretDeleted(metadataFor(ec, SECRET_DELETED, f.id()), f.id(), f.address().render(), key);
        }

        @Override
        public Object data() {
            return new Data(functionId, address, key);
        }

        private record Data(String functionId, String address, String key) {
        }
    }

    /// `{functionId, address, alias, versionId, version}` (spec
    /// `function-zones-and-aliases.md` §2) — `RemoveAlias`. `live` never
    /// reaches this event ([Function#removeAlias] refuses it with
    /// `ALIAS_PROTECTED` before any write).
    public record AliasRemoved(EventMetadata metadata, String functionId, String address, String alias,
                               String versionId, int version) implements DomainEvent {

        public static AliasRemoved of(ExecutionContext ec, Function f, String alias, FunctionVersion v) {
            return new AliasRemoved(metadataFor(ec, ALIAS_REMOVED, f.id()), f.id(), f.address().render(), alias,
                    v.id(), v.version());
        }

        @Override
        public Object data() {
            return new Data(functionId, address, alias, versionId, version);
        }

        private record Data(String functionId, String address, String alias, String versionId, int version) {
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

    // ── Domains (spec `function-public-routes.md` §1) ───────────────────────

    /// `{domainId, hostname, owner}` — never the token (spec §1: "no token").
    public record DomainClaimed(EventMetadata metadata, String domainId, String hostname, String owner)
            implements DomainEvent {

        public static DomainClaimed of(ExecutionContext ec, FunctionDomain d) {
            return new DomainClaimed(domainMetadataFor(ec, DOMAIN_CLAIMED, d.id()), d.id(), d.hostname().value(),
                    d.owner().toWire());
        }

        @Override
        public Object data() {
            return new Data(domainId, hostname, owner);
        }

        private record Data(String domainId, String hostname, String owner) {
        }
    }

    /// `{domainId, hostname, owner}`.
    public record DomainVerified(EventMetadata metadata, String domainId, String hostname, String owner)
            implements DomainEvent {

        public static DomainVerified of(ExecutionContext ec, FunctionDomain d) {
            return new DomainVerified(domainMetadataFor(ec, DOMAIN_VERIFIED, d.id()), d.id(), d.hostname().value(),
                    d.owner().toWire());
        }

        @Override
        public Object data() {
            return new Data(domainId, hostname, owner);
        }

        private record Data(String domainId, String hostname, String owner) {
        }
    }

    /// `{domainId, hostname, owner}`.
    public record DomainReleased(EventMetadata metadata, String domainId, String hostname, String owner)
            implements DomainEvent {

        public static DomainReleased of(ExecutionContext ec, FunctionDomain d) {
            return new DomainReleased(domainMetadataFor(ec, DOMAIN_RELEASED, d.id()), d.id(), d.hostname().value(),
                    d.owner().toWire());
        }

        @Override
        public Object data() {
            return new Data(domainId, hostname, owner);
        }

        private record Data(String domainId, String hostname, String owner) {
        }
    }
}
