package io.flowcatalyst.platform.platformconfig.operations;

import io.flowcatalyst.platform.platformconfig.ConfigAccessRepository;
import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.platform.platformconfig.ConfigValueType;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import io.flowcatalyst.platform.platformconfig.operations.PlatformConfigEvents.PropertySet;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Creates or replaces the value at a [ConfigCoordinate] and emits
/// [PropertySet]. Not anchor-only: a non-anchor with a write grant on the
/// application may set (spec §6) — that rule runs in the authorize phase
/// because the target application is a command field and the DB-backed
/// check must precede any persistence.
public final class SetProperty {

    private SetProperty() {
    }

    public static Operation<SetPropertyCommand, PropertySet> of(PlatformConfigRepository configs, ConfigAccessRepository grants) {
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
                .authorize(cmd -> Access.requireWrite(grants, Auth.current(), cmd.applicationCode()))
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
