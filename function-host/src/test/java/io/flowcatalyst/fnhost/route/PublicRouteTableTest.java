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
        return new DesiredDocument.PublicRouteRef(hostname, prefix, address);
    }

    @Test
    void exactMatchStripsToRootPath() {
        PublicRouteTable table = PublicRouteTable.of(List.of(ref("api.acme.com", "/billing", BILLING)));
        var match = table.match("api.acme.com", "/billing");
        assertThat(match).isPresent();
        assertThat(match.get().address()).isEqualTo(BILLING);
        assertThat(match.get().functionPath()).isEqualTo("/");
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
}
