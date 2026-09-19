package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [HostEnv] (`docs/spec/function-host-reconciler.md` §1.4).
class HostEnvTest {

    private static final Map<String, String> REQUIRED = Map.of(
            "FC_FN_PLATFORM_URL", "http://platform.example",
            "FC_FN_CLIENT_ID", "client-1",
            "FC_FN_CLIENT_SECRET", "secret-1");

    private static HostEnv load(Map<String, String> env) {
        return HostEnv.load(new EnvReader(env));
    }

    @Test
    void defaultsPoolMaxLoadedAndCacheDirWhenEverythingRequiredIsSet() {
        HostEnv env = load(REQUIRED);
        assertThat(env.pool()).isEqualTo(new DnsLabel("default"));
        assertThat(env.maxLoaded()).isEqualTo(200);
        assertThat(env.cacheDir().toString()).contains("fc-fn-cache");
        assertThat(env.signatures()).isInstanceOf(Signatures.Required.class);
    }

    @Test
    void missingRequiredValuesFailWithOneMessageNamingAllOfThem() {
        assertThatThrownBy(() -> load(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_PLATFORM_URL")
                .hasMessageContaining("FC_FN_CLIENT_ID")
                .hasMessageContaining("FC_FN_CLIENT_SECRET");
    }

    @Test
    void missingOnlyOneRequiredValueNamesOnlyThatOne() {
        var partial = new java.util.HashMap<>(REQUIRED);
        partial.remove("FC_FN_CLIENT_SECRET");
        assertThatThrownBy(() -> load(partial))
                .hasMessageContaining("FC_FN_CLIENT_SECRET")
                .as("mutant: report every field as missing even when only one is")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("FC_FN_PLATFORM_URL")
                        .doesNotContain("FC_FN_CLIENT_ID,"));
    }

    @Test
    void anExplicitlySetInvalidHostIdFailsFast() {
        var withBadHostId = new java.util.HashMap<>(REQUIRED);
        withBadHostId.put("FC_FN_HOST_ID", "has a space");
        assertThatThrownBy(() -> load(withBadHostId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_HOST_ID");
    }

    @Test
    void anExplicitHostIdIsUsedVerbatim() {
        var withHostId = new java.util.HashMap<>(REQUIRED);
        withHostId.put("FC_FN_HOST_ID", "my-host-1");
        assertThat(load(withHostId).hostId()).isEqualTo("my-host-1");
    }

    @Test
    void anUnsetHostIdGetsAGeneratedDefaultMatchingTheHeartbeatsOwnRule() {
        String hostId = load(REQUIRED).hostId();
        assertThat(hostId).matches("^[A-Za-z0-9._:-]{1,100}$");
    }

    @Test
    void offRequiresDevModeSameRuleAsThePlatform() {
        var offNoDevMode = new java.util.HashMap<>(REQUIRED);
        offNoDevMode.put("FC_FN_SIGNATURES", "off");
        assertThatThrownBy(() -> load(offNoDevMode))
                .as("mutant: accept FC_FN_SIGNATURES=off without dev mode")
                .isInstanceOf(IllegalStateException.class);

        offNoDevMode.put("FLOWCATALYST_DEV_MODE", "true");
        assertThat(load(offNoDevMode).signatures()).isInstanceOf(Signatures.Off.class);
    }

    @Test
    void toStringMasksTheClientSecret() {
        assertThat(load(REQUIRED).toString()).doesNotContain("secret-1");
    }
}
