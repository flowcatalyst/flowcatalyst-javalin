package io.flowcatalyst.parity;

/// One `parity/expected-diffs.json` allow-list entry (parity-harness spec
/// §6, amended: `scenario`/`step` may be `"*"`, and a `pointer` starting
/// `"**/"` matches its trailing segment at any depth — `"**/$schema"`
/// matches `/$schema` and `/items/3/$schema`). `ruling` is mandatory: no
/// ruling, no entry — the reviewer rejects it.
public record ExpectedDiff(String scenario, String step, String pointer, String reason, String ruling) {
}
