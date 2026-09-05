package io.flowcatalyst.platform.auth.token;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.ClientRepository;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/// The labels the `clients` / `applications` claims pair with their ids
/// ([ClaimShapes]): a client's identifier, an application's code. Go carries
/// these on the principal (`ClientIdentifierMap`, `ApplicationCodeMap`,
/// hydrated by `FindByID`); the Java principal reasons in ids, so the mint
/// asks for the labels it needs, by id set, in one query each.
public interface ClaimLabels {

    /// `clientId → identifier` for every id the store knows; unknown ids are absent.
    Map<String, String> clientIdentifiers(Collection<String> clientIds);

    /// `applicationId → code` for every id the store knows; unknown ids are absent.
    Map<String, String> applicationCodes(Collection<String> applicationIds);

    /// No labels at all: every pair degrades to the bare id. For tests and
    /// for callers that only need the wildcard forms.
    static ClaimLabels none() {
        return new ClaimLabels() {
            @Override
            public Map<String, String> clientIdentifiers(Collection<String> clientIds) {
                return Map.of();
            }

            @Override
            public Map<String, String> applicationCodes(Collection<String> applicationIds) {
                return Map.of();
            }
        };
    }

    /// The store-backed labels.
    static ClaimLabels of(ClientRepository clients, ApplicationRepository applications) {
        Objects.requireNonNull(clients, "clients");
        Objects.requireNonNull(applications, "applications");
        return new ClaimLabels() {
            @Override
            public Map<String, String> clientIdentifiers(Collection<String> clientIds) {
                return clientIds.isEmpty() ? Map.of() : clients.identifiersByIds(clientIds);
            }

            @Override
            public Map<String, String> applicationCodes(Collection<String> applicationIds) {
                return applicationIds.isEmpty() ? Map.of() : applications.codesByIds(applicationIds);
            }
        };
    }
}
