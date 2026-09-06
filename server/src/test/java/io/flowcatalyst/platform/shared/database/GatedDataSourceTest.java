package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.http.Admission;
import io.flowcatalyst.testpg.TestPg;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/// `docs/spec/admission.md` §6 rows 1–6: the pool gate over the embedded
/// Postgres. Pool size 4 → 1 reserved, 3 ordinary.
class GatedDataSourceTest {

    private static GatedDataSource gate() {
        return GatedDataSource.over(TestPg.dataSource(), 4);
    }

    /// Holds `n` ordinary connections open until the latch drops.
    private static List<Connection> hold(GatedDataSource gate, int n) throws SQLException {
        var held = new ArrayList<Connection>();
        for (int i = 0; i < n; i++) held.add(gate.getConnection());
        return held;
    }

    private static void closeAll(List<Connection> cs) throws SQLException {
        for (Connection c : cs) c.close();
    }

    private static Thread.State stateAfter(Thread t, Duration d) throws InterruptedException {
        Thread.sleep(d.toMillis());
        return t.getState();
    }

    @Test
    void theOrdinaryPermitsAdmitPoolSizeMinusReservedHoldersAndParkTheNextUntimed() throws Exception {
        var gate = gate();
        assertThat(gate.poolSize()).isEqualTo(4);
        assertThat(gate.reserved()).isEqualTo(1);
        var held = hold(gate, 3);
        assertThat(gate.held()).isEqualTo(3);

        var got = new CompletableFuture<Connection>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                got.complete(gate.getConnection());
            } catch (SQLException e) {
                got.completeExceptionally(e);
            }
        });
        // Row 1 + row 2: parked, and parked WITHOUT a timeout (a timed park would be TIMED_WAITING).
        assertThat(stateAfter(waiter, Duration.ofMillis(300))).isEqualTo(Thread.State.WAITING);
        assertThat(got.isDone()).isFalse();
        assertThat(gate.waiting()).isEqualTo(1);

        long t0 = System.nanoTime();
        held.get(0).close();
        Connection c = got.get(2, TimeUnit.SECONDS);
        long wakeMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(c).isNotNull();
        assertThat(wakeMs).as("woken by the release, not by a timeout").isLessThan(500);
        assertThat(gate.waiting()).isZero();
        assertThat(gate.held()).isEqualTo(3);
        c.close();
        closeAll(held.subList(1, held.size()));
        assertThat(gate.held()).isZero();
    }

    @Test
    void probesTakeFromTheReservedLaneAndAnswerAtOnceWhenOrdinaryIsFull() throws Exception {
        var gate = gate();
        var held = hold(gate, 3);
        long t0 = System.nanoTime();
        try (Connection probe = gate.forProbes().getConnection()) {
            assertThat(probe.isValid(1)).isTrue();
        }
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(100);
        assertThat(gate.probesHeld()).isZero();
        closeAll(held);
    }

    @Test
    void anOrdinaryCallerNeverTakesAReservedPermitWhileAProbeStillCan() throws Exception {
        var gate = gate();
        var held = hold(gate, 3);
        var latch = new CountDownLatch(1);
        Thread ordinary = Thread.ofVirtual().start(() -> {
            try (Connection c = gate.getConnection()) {
                assertThat(c.isValid(1)).isTrue();
                latch.countDown();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(stateAfter(ordinary, Duration.ofMillis(300))).isEqualTo(Thread.State.WAITING);
        assertThat(latch.getCount()).as("the ordinary caller must not have taken the reserved permit").isEqualTo(1);
        try (Connection probe = gate.forProbes().getConnection()) {
            assertThat(probe).isNotNull();
        }
        closeAll(held);
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aNestedCheckoutInsideARequestScopeJoinsTheOuterTransactionAndReleasesNothing() throws Exception {
        var gate = gate();
        ScopedValue.where(Admission.CURRENT, new Admission("/api/x")).call(() -> {
            try (Connection outer = gate.getConnection()) {
                outer.setAutoCommit(false);
                try (Statement st = outer.createStatement()) {
                    st.execute("create temporary table reentrant_probe(v int) on commit drop");
                    st.execute("insert into reentrant_probe values (42)");
                }
                assertThat(gate.held()).isEqualTo(1);
                // the nested checkout: no wait, no second permit, same transaction
                try (Connection inner = gate.getConnection(); Statement st = inner.createStatement();
                     var rs = st.executeQuery("select v from reentrant_probe")) {
                    assertThat(rs.next()).as("the inner handle sees the outer transaction's uncommitted write").isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(42);
                    assertThat(gate.held()).as("no second permit").isEqualTo(1);
                    assertThat(gate.waiting()).isZero();
                    assertThatThrownBy(inner::commit).isInstanceOf(SQLException.class).hasMessageContaining("nested");
                    assertThatThrownBy(() -> inner.setAutoCommit(true)).isInstanceOf(SQLException.class);
                }
                assertThat(gate.held()).as("closing the inner handle releases nothing").isEqualTo(1);
                assertThat(outer.isClosed()).isFalse();
                outer.rollback();
                outer.setAutoCommit(true);
            }
            assertThat(Admission.CURRENT.get().held()).isZero();
            return null;
        });
        assertThat(gate.held()).isZero();
    }

    @Test
    void closeReleasesThePermitExactlyOnce() throws Exception {
        var gate = gate();
        Connection c = gate.getConnection();
        assertThat(gate.held()).isEqualTo(1);
        c.close();
        c.close();
        assertThat(gate.held()).as("a second close must not release again").isZero();
        // and the pool is intact: poolSize-reserved holders still fit, no more
        var held = hold(gate, 3);
        assertThat(gate.held()).isEqualTo(3);
        closeAll(held);
    }

    @Test
    void aPoolOfOneHasNoReservedLaneAndProbesShareTheOrdinaryOne() throws Exception {
        var gate = GatedDataSource.over(TestPg.dataSource(), 1);
        assertThat(gate.reserved()).isZero();
        try (Connection c = gate.forProbes().getConnection()) {
            assertThat(c.isValid(1)).isTrue();
            assertThat(gate.held()).isEqualTo(1);
        }
        assertThat(gate.held()).isZero();
    }

    @Test
    void reservedIsAtLeastOneAndOneSixteenthOfThePoolExceptForAPoolOfOne() {
        assertThat(GatedDataSource.reservedFor(1)).isZero();
        assertThat(GatedDataSource.reservedFor(2)).isEqualTo(1);
        assertThat(GatedDataSource.reservedFor(4)).isEqualTo(1);
        assertThat(GatedDataSource.reservedFor(32)).isEqualTo(2);
        assertThat(GatedDataSource.reservedFor(100)).isEqualTo(6);
    }
}
