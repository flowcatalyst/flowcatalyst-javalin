package io.flowcatalyst.parity;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/// One side's [StepRecord] after every [Normaliser] rule has been applied
/// (parity-harness spec §5) — what [Diff] actually compares. `body` is
/// always a [JsonNode]: a non-JSON [StepRecord] (raw text, or a SHA-256 for a
/// known binary body) is carried through as a text leaf so the same
/// structural diff handles every step uniformly.
public record Normalised(int status, Map<String, String> headers, JsonNode body) {
    public Normalised {
        headers = Map.copyOf(headers);
    }
}
