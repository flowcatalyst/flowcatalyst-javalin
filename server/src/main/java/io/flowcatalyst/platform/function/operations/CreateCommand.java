package io.flowcatalyst.platform.function.operations;

/// `CreateFunction`'s command (spec `function-api.md` §4.1). `clientId`
/// absent (`null`/blank) ⇒ a platform-owned function.
public record CreateCommand(String applicationCode, String serviceName, String name, String runtime,
                            String description, String clientId) {
}
