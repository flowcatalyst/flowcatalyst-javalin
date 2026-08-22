package io.flowcatalyst.platform.role;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.Objects;

/// One entry of the permission catalogue (`iam_permissions`, spec §1) — a
/// permission definition that exists independently of any role. The code
/// is the four-segment `application:context:aggregate:action`; the
/// segments are denormalised onto the row (the first one in a column named
/// `subdomain`). [#define] is the one place the format rule lives.
///
/// @param id          `prm_…` TSID
/// @param code        the permission string, unique
/// @param subdomain   first segment (the application)
/// @param context     second segment
/// @param aggregate   third segment
/// @param action      fourth segment
/// @param description optional
/// @param createdAt   creation time
/// @param updatedAt   last change
public record Permission(
        String id,
        String code,
        String subdomain,
        String context,
        String aggregate,
        String action,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public static final String FORMAT_MESSAGE =
            "Permission code must follow format: application:context:aggregate:action";

    public Permission {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(subdomain, "subdomain");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(aggregate, "aggregate");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A new catalogue entry for `code`, segments split out.
    ///
    /// @throws UseCaseException validation `INVALID_PERMISSION_CODE` unless the
    ///                          code has exactly four `:`-separated segments
    public static Permission define(String code, String description) {
        String[] parts = (code == null ? "" : code).split(":", -1);
        if (parts.length != 4) {
            throw UseCaseException.validation("INVALID_PERMISSION_CODE", FORMAT_MESSAGE);
        }
        Instant now = Instant.now();
        return new Permission(EntityType.PERMISSION.generate(), code, parts[0], parts[1], parts[2], parts[3],
                description, now, now);
    }

    /// `subdomain:context:aggregate` — how the UI groups the catalogue (spec §1).
    public String category() {
        return subdomain + ":" + context + ":" + aggregate;
    }
}
