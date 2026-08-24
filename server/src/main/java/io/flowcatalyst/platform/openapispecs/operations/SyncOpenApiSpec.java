package io.flowcatalyst.platform.openapispecs.operations;

import io.flowcatalyst.platform.openapispecs.ChangeNotes;
import io.flowcatalyst.platform.openapispecs.OpenApiDocument;
import io.flowcatalyst.platform.openapispecs.OpenApiSpec;
import io.flowcatalyst.platform.openapispecs.OpenApiSpecRepository;
import io.flowcatalyst.platform.openapispecs.operations.OpenApiSpecEvents.ApplicationOpenApiSpecSynced;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/// Replaces an application's `CURRENT` OpenAPI document (spec §3): a
/// byte-identical re-sync touches nothing and reports `unchanged`; anything
/// else archives the prior `CURRENT` (stamping the structural diff) and
/// inserts the new one, atomically with the event and audit row.
///
/// Authorization is resource-level against the application the sync is
/// scoped to ([Checks#checkApplicationAccess]); the coarse sync permission
/// and the `appCode → id` resolution belong to the sdksync handler.
public final class SyncOpenApiSpec {

    /// The fallback / disambiguating version stamp, UTC (spec §3).
    static final DateTimeFormatter VERSION_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private SyncOpenApiSpec() {
    }

    public static Operation<SyncOpenApiSpecCommand, ApplicationOpenApiSpecSynced> of(OpenApiSpecRepository repo) {
        return Operation.<SyncOpenApiSpecCommand, ApplicationOpenApiSpecSynced>named("SyncOpenApiSpec")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationId(), "APPLICATION_ID_REQUIRED", "applicationId is required");
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED", "applicationCode is required");
                    OpenApiDocument.parse(cmd.spec());
                })
                .authorize(cmd -> Checks.checkApplicationAccess(Auth.current(), cmd.applicationId(), cmd.applicationCode()))
                .execute((cmd, ec) -> {
                    OpenApiDocument document = OpenApiDocument.parse(cmd.spec());
                    Instant now = Instant.now();
                    Optional<OpenApiSpec> prior = repo.findCurrentByApplication(cmd.applicationId());

                    if (prior.isPresent() && prior.get().specHash().equals(document.hash())) {
                        return Plan.emit(ApplicationOpenApiSpecSynced.unchanged(ec, cmd.applicationCode(), prior.get()));
                    }

                    String candidate = document.infoVersion().orElseGet(() -> VERSION_STAMP.format(now));
                    String version = repo.existsByApplicationAndVersion(cmd.applicationId(), candidate)
                            ? candidate + "+" + VERSION_STAMP.format(now)
                            : candidate;
                    OpenApiSpec fresh = OpenApiSpec.create(cmd.applicationId(), version, document, now, ec.principalId());

                    if (prior.isEmpty()) {
                        return Plan.saveAll(List.of(fresh), repo, ApplicationOpenApiSpecSynced.of(ec, cmd.applicationCode(), fresh, null, false));
                    }
                    ChangeNotes notes = ChangeNotes.diff(prior.get().spec(), document.root());
                    OpenApiSpec archived = prior.get().archive(notes, notes.summary(), now);
                    return Plan.saveAll(List.of(archived, fresh), repo,
                            ApplicationOpenApiSpecSynced.of(ec, cmd.applicationCode(), fresh, archived.version(), notes.hasBreaking()));
                });
    }
}
