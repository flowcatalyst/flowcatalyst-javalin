package io.flowcatalyst.platform.application.operations;

/// The input DTO for [CreateApplication]. The record's simple name is the
/// audit log's `operation` column, so it must stay `CreateCommand`.
///
/// @param code           raw code; normalised by the operation (spec §1.1)
/// @param name           required
/// @param type           `APPLICATION` | `INTEGRATION`; anything else (incl. `null`) → `APPLICATION`
/// @param description    optional
/// @param iconUrl        optional
/// @param website        optional
/// @param logo           optional inline logo content
/// @param logoMimeType   optional
/// @param defaultBaseUrl optional
public record CreateCommand(String code, String name, String type, String description, String iconUrl,
                            String website, String logo, String logoMimeType, String defaultBaseUrl) {
}
