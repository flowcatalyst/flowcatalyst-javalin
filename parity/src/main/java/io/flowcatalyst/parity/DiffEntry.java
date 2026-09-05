package io.flowcatalyst.parity;

/// One structural disagreement between the two sides' [Normalised] records
/// (parity-harness spec §6). `pointer` is `/status`, `/headers/<Name>`, or a
/// JSON Pointer into the body. [Diff#ABSENT] marks a side where the pointer
/// resolved to nothing at all — distinct from a JSON `null`, which renders
/// as the text `"null"`.
public record DiffEntry(String pointer, String go, String java) {
}
