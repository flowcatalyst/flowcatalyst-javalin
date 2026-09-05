package io.flowcatalyst.parity.model;

import java.util.List;
import java.util.Map;

/// One request/response pair within a [Scenario] (spec §3).
///
/// @param id            unique within the scenario; identifies the step in the report and in
///                      `expected-diffs.json`
/// @param request       what to send
/// @param expect        a sanity check, not the oracle (spec §3): only steps a later step
///                      depends on should set this
/// @param capture       name → JSON Pointer (RFC 6901) into the response body, or
///                      `header:<Name>` / `cookie:<name>`; captured per side
/// @param unordered     pointers to arrays compared as multisets of their normalised elements
///                      (spec §5 rule 6)
/// @param ignore        pointers dropped from both sides before comparison (spec §5 rule 7);
///                      each entry must carry a reason
/// @param authenticator `"register"` or `"assert"` — run the software authenticator over the
///                      previous step's response and send its output as this step's body; `null`
///                      for an ordinary step
public record Step(String id, Request request, Expect expect, Map<String, String> capture,
                    List<String> unordered, List<Ignore> ignore, String authenticator) {
    public Step {
        capture = capture == null ? Map.of() : Map.copyOf(capture);
        unordered = unordered == null ? List.of() : List.copyOf(unordered);
        ignore = ignore == null ? List.of() : List.copyOf(ignore);
    }

    /// A sanity check on the step's status; `null` when the step carries none.
    public record Expect(Integer status) {
    }

    /// A JSON Pointer dropped from both sides' bodies before comparison, with the reason a
    /// reviewer would want when reading `ignore` the way a `@Disabled` is read.
    public record Ignore(String pointer, String reason) {
    }
}
