package io.flowcatalyst.fnhost.route;

import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.platform.function.FunctionAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// spec `function-public-routes.md` §3/§6 (F6): longest whole-segment
/// prefix wins; `/billing` does not match `/billingx`; prefix stripping
/// yields `/` for an exact match; unknown host/path is absent (no match).
class PublicRouteTableTest {

    private static final FunctionAddress BILLING = FunctionAddress.parse("acme.default.billing");
    private static final FunctionAddress ROOT = FunctionAddress.parse("acme.default.root");

    private static DesiredDocument.PublicRouteRef ref(String hostname, String prefix, FunctionAddress address) {
        return new DesiredDocument.PublicRouteRef(hostname, prefix, address, List.of());
    }

    private static DesiredDocument.PublicRouteRef ref(String hostname, String prefix, FunctionAddress address,
            List<String> aliasPrefixes) {
        return new DesiredDocument.PublicRouteRef(hostname, prefix, address, aliasPrefixes);
    }

    @Test
    void exactMatchStripsToRootPath() {
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("api.acme.com", "/billing", BILLING)));
        var match = table.match("api.acme.com", "/billing");
        assertThat(match).isPresent();
        assertThat(match.get().address()).isEqualTo(BILLING);
        assertThat(match.get().functionPath()).isEqualTo("/");
        assertThat(match.get().alias()).as("an exact hostname match resolves to the live alias").isEqualTo("live");
    }

    @Test
    void longerPathUnderPrefixStripsRemainder() {
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("api.acme.com", "/billing", BILLING)));
        var match = table.match("api.acme.com", "/billing/x");
        assertThat(match).isPresent();
        assertThat(match.get().functionPath()).isEqualTo("/x");
    }

    /// Mutant: `startsWith` — `/billing` must NOT match `/billingx` (the
    /// segment boundary is required, not merely a shared string prefix).
    @Test
    void wholeSegmentBoundaryRequired_billingDoesNotMatchBillingx() {
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("api.acme.com", "/billing", BILLING)));
        assertThat(table.match("api.acme.com", "/billingx")).isEmpty();
    }

    /// Mutant: "first-registered wins" instead of "longest wins" — register
    /// the SHORTER prefix first so a first-registered-wins bug would pick it.
    @Test
    void longestPrefixWinsOverShorterEvenWhenRegisteredFirst() {
        PublicRouteTable table = PublicRouteTable.of(List.of(
                ref("api.acme.com", "/", ROOT),
                ref("api.acme.com", "/billing", BILLING)));
        var match = table.match("api.acme.com", "/billing/invoices/7");
        assertThat(match).isPresent();
        assertThat(match.get().address()).as("mutant: first-registered wins").isEqualTo(BILLING);
        assertThat(match.get().functionPath()).isEqualTo("/invoices/7");
    }

    @Test
    void rootPrefixMatchesAnythingNotOwnedByAMoreSpecificPrefix() {
        PublicRouteTable table = PublicRouteTable.of(List.of(
                ref("api.acme.com", "/", ROOT),
                ref("api.acme.com", "/billing", BILLING)));
        var match = table.match("api.acme.com", "/other/path");
        assertThat(match).isPresent();
        assertThat(match.get().address()).isEqualTo(ROOT);
        assertThat(match.get().functionPath()).isEqualTo("/other/path");
    }

    @Test
    void rootPrefixExactMatchIsRootPath() {
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("api.acme.com", "/", ROOT)));
        var match = table.match("api.acme.com", "/");
        assertThat(match).isPresent();
        assertThat(match.get().functionPath()).isEqualTo("/");
    }

    @Test
    void unknownHostnameNeverMatches() {
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("api.acme.com", "/", ROOT)));
        assertThat(table.match("other.example.com", "/")).isEmpty();
    }

    @Test
    void hostnameLookupIsExactAlreadyLowerCased() {
        // The caller (FnHttpServer.publicHostname) already lower-cases; the table's own
        // storage keys are lower-cased from the ref, so a query with the ref's own casing
        // (here already lower) finds it, and a different-case query used AS the key (no
        // caller-side lower-casing) does not — pinning that this class does not itself
        // silently re-lowercase an already-uppercase QUERY argument.
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("Api.Acme.Com", "/", ROOT)));
        assertThat(table.match("api.acme.com", "/")).as("stored hostname is lower-cased").isPresent();
    }

    @Test
    void emptyTableNeverMatches() {
        assertThat(PublicRouteTable.EMPTY.match("api.acme.com", "/")).isEmpty();
    }

    @Test
    void twoDifferentFunctionsOnDifferentHostnames() {
        FunctionAddress other = FunctionAddress.parse("acme.default.other");
        PublicRouteTable table = PublicRouteTable.of(List.of(
                ref("api.acme.com", "/", ROOT),
                ref("shop.acme.com", "/", other)));
        assertThat(table.match("api.acme.com", "/x").get().address()).isEqualTo(ROOT);
        assertThat(table.match("shop.acme.com", "/x").get().address()).isEqualTo(other);
    }

    // ── package J3 (function-zones-and-aliases.md §4): alias-prefixed hostnames ──

    /// `qa-myapp.acme.com` with `qa` opted in on `myapp.acme.com` ⇒ resolves
    /// to `myapp.acme.com`'s route with `alias = "qa"` (mutant: drop the
    /// opt-in check — match on ANY prefix regardless of `aliasPrefixes`).
    @Test
    void aliasPrefixOptedInResolvesToTheBaseHostnamesRoute() {
        FunctionAddress myapp = FunctionAddress.parse("acme.default.myapp");
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("myapp.acme.com", "/", myapp, List.of("qa"))));
        var match = table.match("qa-myapp.acme.com", "/x");
        assertThat(match).isPresent();
        assertThat(match.get().address()).isEqualTo(myapp);
        assertThat(match.get().functionPath()).isEqualTo("/x");
        assertThat(match.get().alias()).isEqualTo("qa");
    }

    /// Without the opt-in, the SAME derived hostname is 404 (mutant: derive
    /// regardless of whether the route names the prefix in `aliasPrefixes`).
    @Test
    void aliasPrefixNotOptedInIsNoMatch() {
        FunctionAddress myapp = FunctionAddress.parse("acme.default.myapp");
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("myapp.acme.com", "/", myapp, List.of())));
        assertThat(table.match("qa-myapp.acme.com", "/x")).isEmpty();
    }

    /// `qa-my-app.acme.com` splits at the FIRST `-` (`qa`, `my-app...`), not
    /// the last (mutant: split at the last `-`, which would try `qa-my` as
    /// the prefix and `app.acme.com` as the base — neither of which exists).
    @Test
    void aliasPrefixSplitsAtTheFirstDash() {
        FunctionAddress myApp = FunctionAddress.parse("acme.default.myapp");
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("my-app.acme.com", "/", myApp, List.of("qa"))));
        var match = table.match("qa-my-app.acme.com", "/");
        assertThat(match).as("mutant: split at the last dash instead of the first").isPresent();
        assertThat(match.get().address()).isEqualTo(myApp);
        assertThat(match.get().alias()).isEqualTo("qa");
    }

    /// An exact route ON the derived hostname beats the derivation (spec §4
    /// step 1 runs before step 2, unconditionally) — mutant: try the
    /// derivation before the exact lookup, which here would find NO base
    /// route for `myapp.acme.com` (there isn't one) and 404 instead of
    /// resolving `qa-myapp.acme.com`'s own exact route.
    @Test
    void anExactRouteOnTheDerivedHostnameBeatsTheDerivation() {
        FunctionAddress exact = FunctionAddress.parse("acme.default.exact");
        FunctionAddress myapp = FunctionAddress.parse("acme.default.myapp");
        PublicRouteTable table = PublicRouteTable.of(List.of(
                ref("qa-myapp.acme.com", "/", exact),
                ref("myapp.acme.com", "/", myapp, List.of("qa"))));
        var match = table.match("qa-myapp.acme.com", "/");
        assertThat(match).isPresent();
        assertThat(match.get().address()).as("mutant: derive before checking for an exact route").isEqualTo(exact);
        assertThat(match.get().alias()).isEqualTo("live");
    }

    /// One level only: `qa-staging-myapp.acme.com` is `p = qa`, base
    /// `staging-myapp.acme.com` — an EXACT lookup on that base (itself
    /// containing a `-`), never a second round of derivation.
    @Test
    void derivationAppliesOnlyOneLevel() {
        FunctionAddress myapp = FunctionAddress.parse("acme.default.myapp");
        PublicRouteTable table = PublicRouteTable.of(List.of(
                ref("staging-myapp.acme.com", "/", myapp, List.of("qa"))));
        var match = table.match("qa-staging-myapp.acme.com", "/");
        assertThat(match).isPresent();
        assertThat(match.get().address()).isEqualTo(myapp);
        assertThat(match.get().alias()).isEqualTo("qa");
    }

    /// A hostname with no `-` in its first label never derives (mutant:
    /// attempt derivation even with no dash present).
    @Test
    void noDashInFirstLabelNeverDerives() {
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("myapp.acme.com", "/", ROOT, List.of("qa"))));
        assertThat(table.match("other.acme.com", "/")).isEmpty();
    }
}
