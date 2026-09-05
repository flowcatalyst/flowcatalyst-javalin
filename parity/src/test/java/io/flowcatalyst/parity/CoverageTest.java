package io.flowcatalyst.parity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the coverage/`covers`-claim machinery (parity-harness spec §7):
/// template matching treats `{id}` as one operation regardless of the
/// concrete id, but a different segment count is a different operation
/// entirely; a scenario's `covers` claim not backed by an actual request
/// fails.
class CoverageTest {

    @Test
    void aPathParameterSegmentMatchesAnyConcreteSegment() {
        assertThat(Coverage.matchesTemplate("/api/event-types/{id}", "/api/event-types/abc")).isTrue();
        assertThat(Coverage.matchesTemplate("/api/event-types/{id}", "/api/event-types/evt_123")).isTrue();
    }

    /// A different segment count is a DIFFERENT operation — `/api/event-types`
    /// (list/create) must never be counted as covering `/api/event-types/{id}`
    /// (get/update/delete) or vice versa.
    @Test
    void aDifferentSegmentCountIsADifferentOperation() {
        assertThat(Coverage.matchesTemplate("/api/event-types/{id}", "/api/event-types")).isFalse();
        assertThat(Coverage.matchesTemplate("/api/event-types", "/api/event-types/abc")).isFalse();
    }

    @Test
    void aLiteralSegmentMustMatchExactly() {
        assertThat(Coverage.matchesTemplate("/api/event-types/by-code/{code}", "/api/event-types/by-code/x")).isTrue();
        assertThat(Coverage.matchesTemplate("/api/event-types/by-code/{code}", "/api/event-types/other/x")).isFalse();
    }

    /// The behaviour the brief pins by name: "a `covers` claim not backed by
    /// a request fails the scenario".
    @Test
    void aCoversClaimNeverActuallyRequestedIsUnmet() {
        var routes = List.of(new Coverage.Route("GET", "/api/event-types/{id}", "getEventType"));
        var requested = List.<Runner.RequestedRoute>of(); // the scenario claimed it but never sent it

        List<String> unmet = Coverage.unmetClaims(List.of("getEventType"), routes, requested);

        assertThat(unmet).containsExactly("getEventType");
    }

    @Test
    void aCoversClaimBackedByAMatchingRequestIsMet() {
        var routes = List.of(new Coverage.Route("GET", "/api/event-types/{id}", "getEventType"));
        var requested = List.of(new Runner.RequestedRoute("GET", "/api/event-types/evt_1"));

        List<String> unmet = Coverage.unmetClaims(List.of("getEventType"), routes, requested);

        assertThat(unmet).isEmpty();
    }

    @Test
    void aClaimNamingAnUnknownOperationIdIsUnmet() {
        List<String> unmet = Coverage.unmetClaims(List.of("noSuchOperation"), List.of(), List.of());
        assertThat(unmet).containsExactly("noSuchOperation (not a lockfile operationId)");
    }

    @Test
    void computeReportsHitAndMissingRoutesSeparately() {
        var lockfile = List.of(
                new Coverage.Route("GET", "/api/event-types/{id}", "getEventType"),
                new Coverage.Route("GET", "/api/event-types", "listEventTypes"));
        var requested = List.of(new Runner.RequestedRoute("GET", "/api/event-types/evt_1"));

        Coverage.Result result = Coverage.compute(lockfile, List.of(), requested);

        assertThat(result.hitLockfile()).containsExactly(lockfile.get(0));
        assertThat(result.missingLockfile()).containsExactly(lockfile.get(1));
        assertThat(result.lockfileCoverage()).isEqualTo(0.5);
    }
}
