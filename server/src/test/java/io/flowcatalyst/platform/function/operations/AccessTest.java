package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `Access.canReach` / `Access.requireReach` (spec `function-api.md` §2, §8
/// P2): the three reach clauses, each pinned independently so a mutant that
/// drops any ONE of them dies without killing the others. Also pins that a
/// failure is 404, never 403 (spec §2: "never 403, which would confirm the
/// address").
class AccessTest {

    private static final String APPLICATION_ID = "app_1";
    private static final String OTHER_APPLICATION_ID = "app_2";

    private static Function functionOwnedBy(FunctionOwner owner, String applicationId) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("billing"), new DnsLabel("invoices"), new DnsLabel("create"));
        return Function.create(applicationId, address, owner, Runtime.JVM, null);
    }

    private static AuthContext anchor() {
        return new AuthContext("usr_anchor", Scope.ANCHOR, null, List.of("*"), List.of(), List.of(), true, List.of());
    }

    private static AuthContext clientScoped(String clientId, boolean allApplications, List<String> applications) {
        return new AuthContext("usr_client", Scope.CLIENT, null, List.of(clientId), List.of(), applications, allApplications, List.of());
    }

    // ── Clause 1: a client-scoped principal against another client's function ──

    @Test
    void clientScopedPrincipalCannotReachAnotherClientsFunction() {
        Function f = functionOwnedBy(FunctionOwner.ofClientId("clt_owner"), APPLICATION_ID);
        AuthContext ac = clientScoped("clt_other", true, List.of());
        assertThat(Access.canReach(ac, f)).as("wrong client").isFalse();
    }

    @Test
    void clientScopedPrincipalReachesItsOwnClientsFunction() {
        Function f = functionOwnedBy(FunctionOwner.ofClientId("clt_owner"), APPLICATION_ID);
        AuthContext ac = clientScoped("clt_owner", true, List.of());
        assertThat(Access.canReach(ac, f)).as("own client").isTrue();
    }

    // ── Clause 2: a non-anchor against a platform function ──────────────────

    @Test
    void nonAnchorCannotReachAPlatformFunctionEvenWithEveryClient() {
        Function f = functionOwnedBy(new FunctionOwner.Platform(), APPLICATION_ID);
        // Anchor-only wildcard client list does NOT substitute for anchor scope.
        AuthContext ac = new AuthContext("usr_wild", Scope.CLIENT, null, List.of("*"), List.of(), List.of(), true, List.of());
        assertThat(Access.canReach(ac, f)).as("non-anchor, platform-owned").isFalse();
    }

    @Test
    void anchorReachesAPlatformFunction() {
        Function f = functionOwnedBy(new FunctionOwner.Platform(), APPLICATION_ID);
        assertThat(Access.canReach(anchor(), f)).isTrue();
    }

    // ── Clause 3: an application-scoped service account against another application's ──

    @Test
    void applicationScopedPrincipalCannotReachAnotherApplicationsFunctionEvenWithOwnerReach() {
        Function f = functionOwnedBy(FunctionOwner.ofClientId("clt_owner"), APPLICATION_ID);
        // Owner reach passes (same client) but the application list excludes APPLICATION_ID.
        AuthContext ac = clientScoped("clt_owner", false, List.of(OTHER_APPLICATION_ID));
        assertThat(Access.canReach(ac, f)).as("wrong application, owner reach otherwise fine").isFalse();
    }

    @Test
    void applicationScopedPrincipalReachesItsOwnApplicationsFunction() {
        Function f = functionOwnedBy(FunctionOwner.ofClientId("clt_owner"), APPLICATION_ID);
        AuthContext ac = clientScoped("clt_owner", false, List.of(APPLICATION_ID));
        assertThat(Access.canReach(ac, f)).isTrue();
    }

    @Test
    void anchorWithAllApplicationsReachesEveryApplication() {
        Function f = functionOwnedBy(new FunctionOwner.Platform(), APPLICATION_ID);
        assertThat(Access.canReach(anchor(), f)).isTrue();
    }

    // ── Unauthenticated / requireReach shape ────────────────────────────────

    @Test
    void unauthenticatedNeverReachesAnything() {
        Function f = functionOwnedBy(new FunctionOwner.Platform(), APPLICATION_ID);
        assertThat(Access.canReach(null, f)).isFalse();
    }

    /// Spec §2: out of reach is 404 `Function_NOT_FOUND`, never a 403
    /// authorization error — this is what distinguishes function reach from
    /// every other aggregate's `Checks.checkScopeAccess` (403).
    @Test
    void requireReachThrowsNotFoundNeverAuthorization() {
        Function f = functionOwnedBy(FunctionOwner.ofClientId("clt_owner"), APPLICATION_ID);
        AuthContext ac = clientScoped("clt_other", true, List.of());
        assertThatThrownBy(() -> Access.requireReach(ac, f))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.NotFound.class);
                    assertThat(err.code()).isEqualTo("Function_NOT_FOUND");
                    assertThat(err.httpStatus()).isEqualTo(404);
                });
    }
}
