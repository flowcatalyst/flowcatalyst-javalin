package io.flowcatalyst.platform.openapispecs.operations;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.flowcatalyst.platform.openapispecs.OpenApiSpec;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;

/// The one domain event of the OpenAPI spec store (spec §4). Operations
/// never assemble metadata — the factory does.
public final class OpenApiSpecEvents {

    public static final String SOURCE = "platform:developer";
    public static final String SYNCED = "platform:developer:application-openapi:synced";

    private OpenApiSpecEvents() {
    }

    /// `platform.application-openapi.<specId>`
    public static String subjectFor(String specId) {
        return "platform.application-openapi." + specId;
    }

    /// `platform:application-openapi:<applicationId>` — one application's syncs in order.
    public static String messageGroupFor(String applicationId) {
        return "platform:application-openapi:" + applicationId;
    }

    /// Emitted on every sync — a new `CURRENT` row (`unchanged=false`) or a
    /// byte-identical re-sync that touched nothing (`unchanged=true`).
    public record ApplicationOpenApiSpecSynced(EventMetadata metadata, String applicationId, String applicationCode,
                                               String specId, String version, String specHash,
                                               String archivedPriorVersion, boolean hasBreaking, boolean unchanged)
            implements DomainEvent {

        /// The changed case: `spec` is the freshly inserted row.
        public static ApplicationOpenApiSpecSynced of(ExecutionContext ec, String applicationCode, OpenApiSpec spec,
                                                      String archivedPriorVersion, boolean hasBreaking) {
            return new ApplicationOpenApiSpecSynced(metadataFor(ec, spec), spec.applicationId(), applicationCode,
                    spec.id(), spec.version(), spec.specHash(), archivedPriorVersion, hasBreaking, false);
        }

        /// The unchanged case: `spec` is the existing `CURRENT` row.
        public static ApplicationOpenApiSpecSynced unchanged(ExecutionContext ec, String applicationCode, OpenApiSpec spec) {
            return new ApplicationOpenApiSpecSynced(metadataFor(ec, spec), spec.applicationId(), applicationCode,
                    spec.id(), spec.version(), spec.specHash(), null, false, true);
        }

        private static EventMetadata metadataFor(ExecutionContext ec, OpenApiSpec spec) {
            return EventMetadata.of(ec, SYNCED, SOURCE, subjectFor(spec.id())).withMessageGroup(messageGroupFor(spec.applicationId()));
        }

        @Override
        public Object data() {
            return new Data(applicationId, applicationCode, specId, version, specHash, archivedPriorVersion, hasBreaking, unchanged);
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        private record Data(String applicationId, String applicationCode, String specId, String version, String specHash,
                            String archivedPriorVersion, boolean hasBreaking, boolean unchanged) {
        }
    }
}
