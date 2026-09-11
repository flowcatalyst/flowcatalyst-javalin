package io.flowcatalyst.platform.portalapp.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientCreated;
import io.flowcatalyst.platform.oauthclient.operations.Secrets;
import io.flowcatalyst.platform.portalapp.PortalApp;
import io.flowcatalyst.platform.portalapp.PortalAppRepository;
import io.flowcatalyst.platform.portalapp.operations.PortalAppEvents.PortalAppCreated;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// Registers a portal app AND provisions its portal OAuth client, in ONE
/// transaction (spec `portal-apps.md` §3.4, Part A J5): the HTTP API's
/// `POST /api/portal-apps` path. Mirrors `application.operations.ProvisionServiceAccount`'s
/// shape — the plaintext secret leaves only in the result, after commit.
/// `Authorize: Public` (spec §3) — the controller gates manage-only.
public final class CreatePortalAppWithOAuthClient {

    /// The client name suffix every provisioned portal OAuth client gets
    /// (spec §3.4 step 3).
    private static final String CLIENT_NAME_SUFFIX = " (portal)";

    private CreatePortalAppWithOAuthClient() {
    }

    /// @param appId              the portal app's id
    /// @param oauthClientRowId   the provisioned OAuth client's row id (`oac_…`)
    /// @param oauthClientId      the provisioned OAuth client's `client_id` string (`oac_…`)
    /// @param clientType         the resolved type (blank command input ⇒ `CONFIDENTIAL`)
    /// @param clientSecret       the plaintext secret, `CONFIDENTIAL` only, disclosed once
    public record Result(String appId, String oauthClientRowId, String oauthClientId, ClientType clientType, String clientSecret) {
        public Result {
            Objects.requireNonNull(appId, "appId");
            Objects.requireNonNull(oauthClientRowId, "oauthClientRowId");
            Objects.requireNonNull(oauthClientId, "oauthClientId");
            Objects.requireNonNull(clientType, "clientType");
        }
    }

    public static TxOperation<CreatePortalAppWithOAuthClientCommand, Result> of(
            PortalAppRepository apps, ClientRepository clients, OAuthClientRepository oauthClients, Optional<Encryption> encryption) {
        return TxOperation.<CreatePortalAppWithOAuthClientCommand, Result>named("CreatePortalAppWithOAuthClient")
                .validate(cmd -> {
                    PortalAppCreation.validateFields(cmd.clientId(), cmd.code(), cmd.name());
                    validateClientType(cmd.clientType());
                    for (String uri : trimmedNonBlank(cmd.redirectUris())) {
                        validateRedirectUri(uri);
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    PortalApp app = PortalAppCreation.create(apps, clients, cmd.clientId(), cmd.code(), cmd.name(), cmd.description());
                    scoped.commit(app, apps, PortalAppCreated.of(ec, app), cmd);

                    ClientType type = cmd.clientType() == null || cmd.clientType().isBlank()
                            ? ClientType.CONFIDENTIAL : ClientType.parse(cmd.clientType()); // already validated

                    String oauthClientId = EntityType.OAUTH_CLIENT.generate();
                    OAuthClient oc = OAuthClient.create(oauthClientId, app.name() + CLIENT_NAME_SUFFIX, type)
                            .withRedirectUris(trimmedNonBlank(cmd.redirectUris()))
                            .withGrantTypes(List.of("authorization_code"))
                            .withDefaultScopes(List.of("openid", "profile", "email"))
                            .withPkceRequired(true)
                            .withPortalAppId(app.id())
                            .withPortalAndApiAccess(app.clientId(), false);

                    String plaintext = null;
                    if (type == ClientType.CONFIDENTIAL) {
                        plaintext = Secrets.generatePlaintext();
                        oc = oc.withSecretRef(Secrets.hashedRef(encryption, plaintext));
                    }
                    scoped.commit(oc, oauthClients, OAuthClientCreated.of(ec, oc), cmd);

                    return new Result(app.id(), oc.id(), oc.clientId(), type, plaintext);
                });
    }

    /// Empty/`null` ⇒ `CONFIDENTIAL` is legal (defaulted at execute time); any
    /// other value not `PUBLIC`/`CONFIDENTIAL` is rejected up front.
    private static void validateClientType(String clientType) {
        if (clientType != null && !clientType.isBlank()
                && !"PUBLIC".equals(clientType) && !"CONFIDENTIAL".equals(clientType)) {
            throw UseCaseException.validation("INVALID_CLIENT_TYPE", "clientType must be PUBLIC or CONFIDENTIAL");
        }
    }

    /// Trimmed, non-empty entries only (spec §3.4 step 3) — a blank/`null`
    /// list yields none. Blank entries are silently dropped rather than
    /// rejected (a lazily-typed callback-URL line), never validated as URIs;
    /// every SURVIVING entry still goes through [#validateRedirectUri].
    private static List<String> trimmedNonBlank(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(s -> s == null ? "" : s.trim()).filter(s -> !s.isEmpty()).toList();
    }

    /// @throws UseCaseException validation `REDIRECT_URI_INVALID` unless `uri`
    ///                          parses as an absolute URL with scheme
    ///                          `http`/`https`, a non-empty host containing no
    ///                          `*`, and no fragment (spec §3.4)
    private static void validateRedirectUri(String uri) {
        boolean valid;
        try {
            URI parsed = new URI(uri);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            valid = parsed.isAbsolute()
                    && ("http".equals(scheme) || "https".equals(scheme))
                    && host != null && !host.isEmpty() && host.indexOf('*') < 0
                    && parsed.getFragment() == null;
        } catch (URISyntaxException e) {
            valid = false;
        }
        if (!valid) {
            throw UseCaseException.validation("REDIRECT_URI_INVALID",
                    "redirectUris must be absolute http(s) URLs without wildcards: " + uri);
        }
    }
}
