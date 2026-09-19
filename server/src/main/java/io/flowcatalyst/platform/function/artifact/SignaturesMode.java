package io.flowcatalyst.platform.function.artifact;

/// The raw `FC_FN_SIGNATURES` setting, resolved once at boot (spec
/// `function-api.md` §5.1). `REQUIRED` is the default — anything unset,
/// blank, `"required"`, or an unrecognised typo parses to `REQUIRED`, never
/// silently to `OFF`: the one way to get [#OFF] is to spell it exactly. This
/// mirrors `EnvReader`'s "unparseable falls back to the default" rule
/// (`CONVENTIONS.md` §8) while making it impossible for a typo to turn
/// signatures off by accident.
///
/// [#OFF] alone does not mean signatures are off — [Signatures#resolve] also
/// requires `FLOWCATALYST_DEV_MODE=true` (spec §5.1 step 5, §8 P9).
public enum SignaturesMode {
    REQUIRED, OFF;

    /// `"off"` (case-insensitive, trimmed) ⇒ [#OFF]; everything else,
    /// including `null`, ⇒ [#REQUIRED].
    public static SignaturesMode parse(String raw) {
        return raw != null && "off".equalsIgnoreCase(raw.trim()) ? OFF : REQUIRED;
    }
}
