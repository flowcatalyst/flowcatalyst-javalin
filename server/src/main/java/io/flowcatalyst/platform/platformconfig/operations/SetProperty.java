package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.platform.platformconfig.ConfigValueType;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import io.flowcatalyst.platform.platformconfig.operations.PlatformConfigEvents.PropertySet;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import static io.flowcatalyst.platform.shared.auth.Permission.CONFIG_MANAGE;

/// Creates or replaces the value at a [ConfigCoordinate] and emits
/// [PropertySet]. Gated by `CONFIG_MANAGE` (`docs/spec/config-permissions.md`
/// §A.2) — permissions always come from roles, no per-application grant, no
/// anchor bypass. The check runs in the authorize phase (the established
/// envelope boundary for an operation, per the spec's "Error handling"
/// section) rather than at the handler, because [SetPropertyCommand] carries
/// no resource-level distinction the handler could gate on ahead of it.
public final class SetProperty {

    private SetProperty() {
    }

    public static Operation<SetPropertyCommand, PropertySet> of(PlatformConfigRepository configs) {
        return Operation.<SetPropertyCommand, PropertySet>named("SetProperty")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.applicationCode(), "FIELD_REQUIRED", "applicationCode is required");
                    UseCaseException.requireNonBlank(cmd.section(), "FIELD_REQUIRED", "section is required");
                    UseCaseException.requireNonBlank(cmd.property(), "FIELD_REQUIRED", "property is required");
                    if (cmd.value() == null) {
                        throw UseCaseException.validation("FIELD_REQUIRED", "value is required");
                    }
                    if (cmd.valueType() != null) {
                        ConfigValueType.parseWire(cmd.valueType()); // INVALID_VALUE_TYPE before any read (Go's order)
                    }
                })
                .authorize(cmd -> Checks.require(Auth.current(), CONFIG_MANAGE))
                .execute((cmd, ec) -> {
                    ConfigCoordinate coordinate = cmd.coordinate();
                    ConfigValueType valueType = cmd.valueType() == null ? null : ConfigValueType.parseWire(cmd.valueType());
                    PlatformConfig c = configs.findByCoordinate(coordinate)
                            .orElseGet(() -> PlatformConfig.create(coordinate, cmd.value()))
                            .set(cmd.value(), valueType, cmd.description());
                    return Plan.save(c, configs, PropertySet.of(ec, c));
                });
    }
}
