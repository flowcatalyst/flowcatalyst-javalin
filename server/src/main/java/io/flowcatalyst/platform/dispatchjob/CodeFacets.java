package io.flowcatalyst.platform.dispatchjob;

/// The `application / subdomain / aggregate` facets the list wire shape
/// derives from a job's code (spec §1.3): segments 1–3 of the `:`-split
/// code, each `null` when missing or empty. Not a parser — a dispatch code
/// is not validated, any string is accepted and what is there is reported.
///
/// @param application first segment, or `null`
/// @param subdomain   second segment, or `null`
/// @param aggregate   third segment, or `null`
public record CodeFacets(String application, String subdomain, String aggregate) {

    /// The facets of `code`; `null` yields all-absent facets.
    public static CodeFacets of(String code) {
        if (code == null) return new CodeFacets(null, null, null);
        String[] parts = code.split(":", -1);
        return new CodeFacets(segment(parts, 0), segment(parts, 1), segment(parts, 2));
    }

    private static String segment(String[] parts, int i) {
        return i < parts.length && !parts[i].isEmpty() ? parts[i] : null;
    }
}
