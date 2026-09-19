package io.flowcatalyst.platform.function.artifact;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Where an [OciArtifactStore] gets HTTP Basic credentials for a registry
/// host (spec `function-artifacts.md` §2.2). ECR needs `ecr:GetAuthorizationToken`,
/// which needs the `ecr` SDK module — that lands with the host (package D)
/// behind this interface, so this package ships only the two trivial forms.
public interface RegistryCredentials {

    /// `host` is the registry authority as it appears in the artifact
    /// reference (`ghcr.io`, `localhost:5000`) — no scheme, no path.
    Optional<BasicAuth> forRegistry(String host);

    /// No registry has credentials — every challenge goes unanswered.
    static RegistryCredentials none() {
        return _ -> Optional.empty();
    }

    /// A fixed, immutable map of registry host to credentials.
    static RegistryCredentials fixed(Map<String, BasicAuth> byHost) {
        var copy = Map.copyOf(byHost);
        return host -> Optional.ofNullable(copy.get(host));
    }

    /// HTTP Basic credentials for one registry. `toString` masks `password`
    /// (`CONVENTIONS.md` §8: carriers of secrets mask `toString`).
    record BasicAuth(String username, String password) {
        public BasicAuth {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "BasicAuth[username=" + username + ", password=***]";
        }
    }
}
