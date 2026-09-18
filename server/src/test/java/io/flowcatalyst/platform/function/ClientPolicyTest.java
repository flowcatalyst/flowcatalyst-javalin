package io.flowcatalyst.platform.function;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// The aggregate's pure rules (spec `function-registry.md` §6.4): `permits`
/// is exact — no case folding, no trimming, no pattern (§8 M15) — and
/// `ceilings` resolves each column against the platform default.
class ClientPolicyTest {

    private static final Instant NOW = Instant.now();
    private static final SignerIdentity GITHUB =
            new SignerIdentity("https://token.actions.githubusercontent.com", "repo:acme/billing:ref:refs/heads/main");

    private static ClientPolicy policyWith(ClientPolicy.SignerRule... rules) {
        return new ClientPolicy("clt_1", List.of(rules), null, null, null, null, NOW, NOW);
    }

    // ── permits — §8 M15 ──────────────────────────────────────────────────

    @Test
    void permitsAnExactMatch() {
        ClientPolicy p = policyWith(new ClientPolicy.SignerRule(GITHUB.issuer(), GITHUB.subject(), Set.of(Runtime.JVM)));
        assertThat(p.permits(GITHUB, Runtime.JVM)).isTrue();
    }

    @Test
    void aSubjectDifferingByCaseDoesNotPermit() {
        ClientPolicy p = policyWith(new ClientPolicy.SignerRule(GITHUB.issuer(), GITHUB.subject().toUpperCase(), Set.of(Runtime.JVM)));
        assertThat(p.permits(GITHUB, Runtime.JVM)).as("no case folding").isFalse();
    }

    @Test
    void aSubjectDifferingByATrailingSlashDoesNotPermit() {
        ClientPolicy p = policyWith(new ClientPolicy.SignerRule(GITHUB.issuer(), GITHUB.subject() + "/", Set.of(Runtime.JVM)));
        assertThat(p.permits(GITHUB, Runtime.JVM)).as("no trimming/normalisation").isFalse();
    }

    @Test
    void aRuleForTheOtherRuntimeDoesNotPermit() {
        ClientPolicy p = policyWith(new ClientPolicy.SignerRule(GITHUB.issuer(), GITHUB.subject(), Set.of(Runtime.WASM)));
        assertThat(p.permits(GITHUB, Runtime.JVM)).isFalse();
    }

    @Test
    void nullIdentityNeverPermits() {
        ClientPolicy p = policyWith(new ClientPolicy.SignerRule(GITHUB.issuer(), GITHUB.subject(), Set.of(Runtime.JVM)));
        assertThat(p.permits(null, Runtime.JVM)).isFalse();
    }

    @Test
    void emptySignerListPermitsNothing() {
        ClientPolicy p = policyWith();
        assertThat(p.permits(GITHUB, Runtime.JVM)).isFalse();
    }

    // ── ceilings (spec §4.6) ──────────────────────────────────────────────

    @Test
    void aClientWithNoOverridesGetsThePlatformDefaultForEveryCeiling() {
        FunctionLimits defaults = FunctionLimits.defaults();
        ClientPolicy p = new ClientPolicy("clt_1", List.of(), null, null, null, null, NOW, NOW);
        assertThat(p.ceilings(defaults)).isEqualTo(ClientCeilings.of(defaults));
    }

    @Test
    void anOverrideReplacesJustItsOwnColumn() {
        FunctionLimits defaults = FunctionLimits.defaults();
        ClientPolicy p = new ClientPolicy("clt_1", List.of(), 1000, null, null, null, NOW, NOW);
        ClientCeilings c = p.ceilings(defaults);
        assertThat(c.maxDurationMs()).isEqualTo(1000);
        assertThat(c.maxConcurrency()).isEqualTo(defaults.maxConcurrency());
        assertThat(c.wasmMemoryMb()).isEqualTo(defaults.wasmMemoryMb());
        assertThat(c.dbPoolSize()).isEqualTo(defaults.dbPoolSize());
    }

    @Test
    void everyCeilingColumnOverridesIndependently() {
        FunctionLimits defaults = FunctionLimits.defaults();
        ClientPolicy p = new ClientPolicy("clt_1", List.of(), 111, 222, 333, 444, NOW, NOW);
        assertThat(p.ceilings(defaults)).isEqualTo(new ClientCeilings(111, 222, 333, 444));
    }
}
