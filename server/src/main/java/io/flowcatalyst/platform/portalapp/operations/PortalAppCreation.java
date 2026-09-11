package io.flowcatalyst.platform.portalapp.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppCode;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// The `CreateApp` invariant checks (spec `portal-apps.md` §3.3), shared by
/// [CreatePortalApp] (single-aggregate) and [CreatePortalAppWithOAuthClient]
/// (§3.4's orchestration, which cannot delegate to the former — it must run
/// inside its own already-open transaction). One place so the two never
/// drift on validation order or the exact `CODE_EXISTS` message.
final class PortalAppCreation {

    private PortalAppCreation() {
    }

    /// Pure, no I/O — the `Validate` phase's half (§3.3: `clientId`, `code`'s
    /// format, `name`).
    static void validateFields(String clientId, String code, String name) {
        UseCaseException.requireNonBlank(clientId, "CLIENT_ID_REQUIRED", "clientId is required");
        PortalAppCode.parse(code); // throws CODE_INVALID
        UseCaseException.requireNonBlank(name, "NAME_REQUIRED", "name is required");
    }

    /// The `Execute` phase's half: client exists (`Client_NOT_FOUND`), code
    /// unused for the client (409 `CODE_EXISTS`), then the fresh aggregate.
    static PortalApp create(PortalAppRepository apps, ClientRepository clients,
                            String clientId, String rawCode, String name, String description) {
        clients.findById(clientId).orElseThrow(() -> UseCaseException.resourceNotFound("Client", clientId));
        PortalAppCode code = PortalAppCode.parse(rawCode);
        if (apps.findByClientAndCode(clientId, code.value()).isPresent()) {
            throw UseCaseException.conflict("CODE_EXISTS",
                    "portal app code '" + code.value() + "' already exists for this client");
        }
        return PortalApp.create(clientId, code, name, description);
    }
}
