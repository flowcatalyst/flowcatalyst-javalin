package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

/// `POST /api/functions/{address}/versions` (spec `function-api.md` §5.1).
///
/// @param address        the target function; from the path
/// @param artifactRef    where the artifact lives
/// @param digest         the artifact's content digest, unparsed
/// @param signatureBundle nullable — absent when signatures are off or the
///                        caller sent none
/// @param manifest       the raw manifest body, read by `Manifest.parseStrict`
public record PublishCommand(FunctionAddress address, String artifactRef, String digest, String signatureBundle,
                             JsonNode manifest) {
    public PublishCommand {
        Objects.requireNonNull(address, "address");
    }
}
