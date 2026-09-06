package io.flowcatalyst.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.platform.shared.TestHttp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/// `docs/spec/admission.md` §2 and §6 row 7: the tier-2 group bulkhead.
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

    @Test
    void theLoginGroupAdmitsItsBudgetAndQueuesTheNextRequestUntilAHandlerFinishes() throws Exception {
        var budgets = Budgets.of(Map.of(Group.LOGIN, 1));
        var inHandler = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (TestHttp http = TestHttp.routes(budgets, routes -> routes.in(Group.LOGIN).get("/login", ctx -> {
            inHandler.countDown();
            release.await();
            ctx.status(200).result("ok");
        }))) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + "/login")).timeout(Duration.ofSeconds(10)).build();
            CompletableFuture<HttpResponse<String>> r1 = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            assertThat(inHandler.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<HttpResponse<String>> r2 = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            Thread.sleep(300);
            assertThat(budgets.held(Group.LOGIN)).isEqualTo(1);
            assertThat(budgets.waiting(Group.LOGIN)).as("the second request is parked on the bulkhead, not in the handler").isEqualTo(1);
            assertThat(r2.isDone()).isFalse();
            release.countDown();
            assertThat(r1.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(r2.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(budgets.held(Group.LOGIN)).isZero();
            assertThat(budgets.waiting(Group.LOGIN)).isZero();
        }
    }
}
