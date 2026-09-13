package io.flowcatalyst.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/// `docs/spec/admission.md` §2 and §6 row 7: the tier-2 group bulkhead
/// primitive. The Vert.x listener enforces the equivalent group budget as a
/// worker pool instead ([RequestWorkersTest]) — [Budgets] itself is tested
/// here in isolation.
class BudgetsTest {

    @Test
    void derivedBudgetsGiveLoginAndOidcTheProcessorCountAndNothingElseABudget() {
        var b = Budgets.derived();
        int n = Runtime.getRuntime().availableProcessors();
        assertThat(b.budget(Group.LOGIN)).contains(n);
        assertThat(b.budget(Group.OIDC)).contains(n);
        assertThat(b.budget(Group.DISPATCH)).isEmpty();
        assertThat(b.budget(Group.INGEST)).isEmpty();
    }

    @Test
    void aFullBudgetParksTheNextCallerUntimedAndAPermitReleasesOnce() throws Exception {
        var b = Budgets.of(Map.of(Group.LOGIN, 1));
        Budgets.Permit first = b.acquire(Group.LOGIN);
        assertThat(b.held(Group.LOGIN)).isEqualTo(1);
        var got = new CompletableFuture<Budgets.Permit>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                got.complete(b.acquire(Group.LOGIN));
            } catch (InterruptedException e) {
                got.completeExceptionally(e);
            }
        });
        Thread.sleep(200);
        assertThat(t.getState()).isEqualTo(Thread.State.WAITING);
        assertThat(b.waiting(Group.LOGIN)).isEqualTo(1);
        first.close();
        first.close();
        Budgets.Permit second = got.get(2, TimeUnit.SECONDS);
        assertThat(b.held(Group.LOGIN)).as("double close released once: the second holder is the only one").isEqualTo(1);
        second.close();
        assertThat(b.held(Group.LOGIN)).isZero();
        assertThat(b.acquire(Group.DISPATCH)).as("an unbudgeted group hands out a no-op permit").isNotNull();
    }
}
