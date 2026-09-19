package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The function aggregate root (spec `function-registry.md` §6.1): one row
/// per `app.service.name` address, plus its alias pointers — `live` today,
/// with room for named aliases later (design §10.6).
///
/// There is no transition that changes [#applicationId], [#address],
/// [#owner] or [#runtime] (spec §6.1, design §10.17): the repository's
/// upsert `SET` list omits those columns, so even a hand-built copy cannot
/// move a function.
///
/// @param id            `fnc_…` TSID
/// @param applicationId the owning application; immutable
/// @param owner         the owning client, or the platform (ruling R2); immutable
/// @param runtime       `JVM` or `WASM`; immutable
/// @param description   optional; the only field [#describe] edits
/// @param status        `ACTIVE` or `DISABLED`
/// @param aliases       named pointers to versions, replaced wholesale on write
/// @param createdAt     creation time
/// @param updatedAt     last change
public record Function(
        String id,
        String applicationId,
        FunctionAddress address,
        FunctionOwner owner,
        Runtime runtime,
        String description,
        FunctionStatus status,
        List<FunctionAlias> aliases,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    /// The well-known alias [#promote] targets today — the only one the
    /// spec supports (design §10.6, `ALIAS_UNSUPPORTED` for any other name).
    public static final String LIVE = "live";

    public Function {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(runtime, "runtime");
        description = description == null || description.isBlank() ? null : description;
        Objects.requireNonNull(status, "status");
        aliases = List.copyOf(aliases);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A named pointer from a function to one of its versions (spec §6.1).
    ///
    /// @param alias     the alias name, e.g. [Function#LIVE]
    /// @param versionId the version it currently points at
    /// @param updatedBy the principal that last changed it
    /// @param updatedAt when it was last changed
    public record FunctionAlias(String alias, String versionId, String updatedBy, Instant updatedAt) {
        public FunctionAlias {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(versionId, "versionId");
            Objects.requireNonNull(updatedBy, "updatedBy");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /// The result of a successful [#promote]: the updated function and the
    /// version the alias pointed at before, `null` on a first promotion.
    public record Promoted(Function function, String previousVersionId) {
        public Promoted {
            Objects.requireNonNull(function, "function");
        }
    }

    /// A fresh, `ACTIVE` function with no aliases (spec §6.1). The caller
    /// hands in the already-parsed [FunctionAddress] — the type carries the
    /// proof (`CONVENTIONS.md` §8).
    public static Function create(String applicationId, FunctionAddress address, FunctionOwner owner, Runtime runtime,
            String description) {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(runtime, "runtime");
        Instant now = Instant.now();
        return new Function(EntityType.FUNCTION.generate(), applicationId, address, owner, runtime, description,
                FunctionStatus.ACTIVE, List.of(), now, now);
    }

    /// The only editable field besides status and aliases (spec §6.1).
    public Function describe(String newDescription, Instant now) {
        return new Function(id, applicationId, address, owner, runtime, newDescription, status, aliases, createdAt, now);
    }

    /// @throws UseCaseException conflict `FUNCTION_ALREADY_DISABLED`
    public Function disable(Instant now) {
        if (status == FunctionStatus.DISABLED) {
            throw UseCaseException.conflict("FUNCTION_ALREADY_DISABLED", "function is already disabled");
        }
        return new Function(id, applicationId, address, owner, runtime, description, FunctionStatus.DISABLED,
                aliases, createdAt, now);
    }

    /// @throws UseCaseException conflict `FUNCTION_ALREADY_ACTIVE`
    public Function enable(Instant now) {
        if (status == FunctionStatus.ACTIVE) {
            throw UseCaseException.conflict("FUNCTION_ALREADY_ACTIVE", "function is already active");
        }
        return new Function(id, applicationId, address, owner, runtime, description, FunctionStatus.ACTIVE,
                aliases, createdAt, now);
    }

    /// Points `alias` at `version` (spec §6.1). Deliberately does **not**
    /// require `READY` — see spec open question 3: a first version can never
    /// become ready before it is promotable, since only a promoted alias is
    /// loaded by a host today.
    ///
    /// @throws UseCaseException validation `ALIAS_UNSUPPORTED` for any alias
    ///                          but [#LIVE]; validation `VERSION_NOT_OF_FUNCTION`
    ///                          when `version` belongs to another function;
    ///                          conflict `VERSION_RETIRED`; conflict
    ///                          `FUNCTION_DISABLED`; conflict `ALIAS_UNCHANGED`
    ///                          when the alias already points at `version`
    public Promoted promote(String alias, FunctionVersion version, String principalId, Instant now) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(now, "now");
        requireSupportedAlias(alias);
        if (!id.equals(version.functionId())) {
            throw UseCaseException.validation("VERSION_NOT_OF_FUNCTION", "version does not belong to this function");
        }
        if (version.state() instanceof FunctionVersion.VersionState.Retired) {
            throw UseCaseException.conflict("VERSION_RETIRED", "version is retired");
        }
        if (status == FunctionStatus.DISABLED) {
            throw UseCaseException.conflict("FUNCTION_DISABLED", "function is disabled");
        }
        Optional<String> current = liveVersionId();
        if (current.isPresent() && current.get().equals(version.id())) {
            throw UseCaseException.conflict("ALIAS_UNCHANGED", "alias already points at this version");
        }

        List<FunctionAlias> updated = new ArrayList<>();
        boolean replaced = false;
        for (FunctionAlias a : aliases) {
            if (a.alias().equals(alias)) {
                updated.add(new FunctionAlias(alias, version.id(), principalId, now));
                replaced = true;
            } else {
                updated.add(a);
            }
        }
        if (!replaced) {
            updated.add(new FunctionAlias(alias, version.id(), principalId, now));
        }
        Function updatedFunction =
                new Function(id, applicationId, address, owner, runtime, description, status, updated, createdAt, now);
        return new Promoted(updatedFunction, current.orElse(null));
    }

    /// The one home of the `ALIAS_UNSUPPORTED` rule (review fix, slice B3):
    /// [#promote] calls it, and `PromoteVersion`'s `validate` phase calls it
    /// too — so alias validity is rejected BEFORE the version-state check
    /// (`VERSION_NOT_READY`) ever runs, and the message is written once.
    ///
    /// @throws UseCaseException validation `ALIAS_UNSUPPORTED` for any alias but [#LIVE]
    public static void requireSupportedAlias(String alias) {
        if (!LIVE.equals(alias)) {
            throw UseCaseException.validation("ALIAS_UNSUPPORTED", "alias must be '" + LIVE + "'");
        }
    }

    public Optional<String> liveVersionId() {
        return aliases.stream().filter(a -> a.alias().equals(LIVE)).map(FunctionAlias::versionId).findFirst();
    }

    public boolean isLive(String versionId) {
        return liveVersionId().map(v -> v.equals(versionId)).orElse(false);
    }
}
