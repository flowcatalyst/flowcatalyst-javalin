package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.FunctionAddress;

/// `UpdateFunction`'s command (spec `function-api.md` §4.2): both fields
/// optional, absent (`null`) = untouched. `status`, when given, selects
/// `enable()` / `disable()` — `"ACTIVE"` / `"DISABLED"` — never modelled as
/// a free-form transition (`CONVENTIONS.md` §2).
public record UpdateCommand(FunctionAddress address, String description, String status) {
}
