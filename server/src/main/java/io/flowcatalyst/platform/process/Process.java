package io.flowcatalyst.platform.process;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The process aggregate root (spec: `docs/spec/process.md`). A process is
/// free-form workflow documentation — typically a Mermaid diagram —
/// identified by its three-segment code; the segments are denormalised onto
/// the row. Processes are global: there is no client dimension (spec §5).
///
/// Immutable record: each transition returns a copy and throws
/// [UseCaseException] when an invariant is violated, so an operation is just
/// load → transition → event. The repository persists whatever copy it is
/// handed and stamps `updatedAt` itself.
///
/// `createdBy` is set by the admin create but `msg_processes` has no such
/// column, so it is never persisted and reads back `null` (spec §1, open
/// question 1).
///
/// @param id          `prc_…` TSID
/// @param code        `application:subdomain:process-name`, unique, immutable
/// @param name        human-readable name
/// @param description optional description (`null` when absent)
/// @param status      `CURRENT` | `ARCHIVED`
/// @param source      `CODE` | `API` | `UI`
/// @param application first code segment
/// @param subdomain   second code segment
/// @param processName third code segment
/// @param body        diagram / documentation source; `""` is a real value ("no body"), the column is `NOT NULL`
/// @param diagramType diagram syntax, default [#DEFAULT_DIAGRAM_TYPE]
/// @param tags        free-form tags, never `null` (the column is `NOT NULL`)
/// @param createdBy   creating principal, never persisted (see above)
/// @param createdAt   creation time
/// @param updatedAt   last change
public record Process(
        String id,
        String code,
        String name,
        String description,
        ProcessStatus status,
        ProcessSource source,
        String application,
        String subdomain,
        String processName,
        String body,
        String diagramType,
        List<String> tags,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) implements HasId {

    /// The diagram syntax a process gets when none is given (spec §1).
    public static final String DEFAULT_DIAGRAM_TYPE = "mermaid";

    public Process {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(subdomain, "subdomain");
        Objects.requireNonNull(processName, "processName");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(diagramType, "diagramType");
        tags = tags == null ? List.of() : List.copyOf(tags);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh `CURRENT`, `UI`-sourced process with an empty body, the
    /// default diagram type and no tags.
    ///
    /// @throws UseCaseException validation `INVALID_CODE_FORMAT` (see [ProcessCode#parse])
    public static Process create(String code, String name) {
        ProcessCode parsed = ProcessCode.parse(code);
        Instant now = Instant.now();
        return new Process(EntityType.PROCESS.generate(), code, name, null, ProcessStatus.CURRENT, ProcessSource.UI,
                parsed.application(), parsed.subdomain(), parsed.processName(), "", DEFAULT_DIAGRAM_TYPE, List.of(),
                null, now, now);
    }

    public boolean isArchived() {
        return status == ProcessStatus.ARCHIVED;
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// `CURRENT` → `ARCHIVED`. Unconditional: an already archived process is
    /// archived again (spec §2, open question 2).
    public Process archive() {
        return new Process(id, code, name, description, ProcessStatus.ARCHIVED, source, application, subdomain,
                processName, body, diagramType, tags, createdBy, createdAt, Instant.now());
    }

    /// The fields an admin update may change; `null` = untouched (spec §3).
    /// `tags` replaces the whole list (an empty list clears it).
    public record Changes(String name, String description, String body, String diagramType, List<String> tags) {
        public Changes {
            tags = tags == null ? null : List.copyOf(tags);
        }
    }

    /// Applies the non-null [Changes]; `code`, `status` and `source` never change.
    public Process update(Changes c) {
        return new Process(id, code,
                c.name() != null ? c.name() : name,
                c.description() != null ? c.description() : description,
                status, source, application, subdomain, processName,
                c.body() != null ? c.body() : body,
                c.diagramType() != null ? c.diagramType() : diagramType,
                c.tags() != null ? c.tags() : tags,
                createdBy, createdAt, Instant.now());
    }

    /// The declarative sync overwrite (spec §7): `name`, `description`,
    /// `body` and `tags` are replaced outright (absent tags ⇒ none); the
    /// diagram type is replaced only when the batch names one. `source` and
    /// `status` stay as they are.
    public Process syncedFrom(String newName, String newDescription, String newBody, String newDiagramType,
                              List<String> newTags) {
        return new Process(id, code, newName, newDescription, status, source, application, subdomain, processName,
                newBody == null ? "" : newBody, orDefault(newDiagramType, diagramType), newTags,
                createdBy, createdAt, Instant.now());
    }

    // ── Copies ─────────────────────────────────────────────────────────────

    public Process withDescription(String newDescription) {
        return new Process(id, code, name, newDescription, status, source, application, subdomain, processName,
                body, diagramType, tags, createdBy, createdAt, updatedAt);
    }

    /// `null` ⇒ `""` — the body is never absent.
    public Process withBody(String newBody) {
        return new Process(id, code, name, description, status, source, application, subdomain, processName,
                newBody == null ? "" : newBody, diagramType, tags, createdBy, createdAt, updatedAt);
    }

    /// Blank ⇒ the default stays (spec §3: defaults are domain rules).
    public Process withDiagramType(String newDiagramType) {
        return new Process(id, code, name, description, status, source, application, subdomain, processName,
                body, orDefault(newDiagramType, diagramType), tags, createdBy, createdAt, updatedAt);
    }

    /// `null` ⇒ no tags.
    public Process withTags(List<String> newTags) {
        return new Process(id, code, name, description, status, source, application, subdomain, processName,
                body, diagramType, newTags, createdBy, createdAt, updatedAt);
    }

    public Process withSource(ProcessSource newSource) {
        return new Process(id, code, name, description, status, newSource, application, subdomain, processName,
                body, diagramType, tags, createdBy, createdAt, updatedAt);
    }

    public Process withCreatedBy(String principalId) {
        return new Process(id, code, name, description, status, source, application, subdomain, processName,
                body, diagramType, tags, principalId, createdAt, updatedAt);
    }

    private static String orDefault(String candidate, String current) {
        return candidate == null || candidate.isBlank() ? current : candidate;
    }
}
