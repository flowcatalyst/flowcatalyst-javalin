package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Exercises the atomicity contract against a real (H2) transaction: the
/// aggregate row, the event row and the audit row either all land or none do.
class UnitOfWorkTest {

    record Thing(String id, String name) implements HasId {}

    record ThingCommand(String name) {}

    record ThingSaved(EventMetadata metadata, String thingId) implements DomainEvent {
        @Override public Object data() { return Map.of("thingId", thingId); }
    }

    /// A repository writing to `things`; optionally fails to simulate a DB error.
    static final class ThingRepository implements Persist<Thing> {
        boolean fail;
        @Override public void persist(Thing t, DbTx tx) throws SQLException {
            if (fail) throw new SQLException("disk on fire");
            try (PreparedStatement ps = tx.connection().prepareStatement("MERGE INTO things (id, name) KEY (id) VALUES (?, ?)")) {
                ps.setString(1, t.id()); ps.setString(2, t.name()); ps.executeUpdate();
            }
        }
        @Override public void delete(Thing t, DbTx tx) throws SQLException {
            try (PreparedStatement ps = tx.connection().prepareStatement("DELETE FROM things WHERE id = ?")) {
                ps.setString(1, t.id()); ps.executeUpdate();
            }
        }
    }

    /// A sink writing real rows on the same transaction so rollback is observable.
    static final class TableSink implements Sink {
        boolean failEvent;
        boolean failAudit;
        final List<String> order = new ArrayList<>();
        @Override public void writeEvent(DbTx tx, DomainEvent event) throws SQLException {
            if (failEvent) throw new SQLException("event table gone");
            order.add("event:" + event.subject());
            try (PreparedStatement ps = tx.connection().prepareStatement("INSERT INTO events (id, subject) VALUES (?, ?)")) {
                ps.setString(1, event.eventId()); ps.setString(2, event.subject()); ps.executeUpdate();
            }
        }
        @Override public void writeAudit(DbTx tx, DomainEvent event, Object command) throws SQLException {
            if (failAudit) throw new SQLException("audit table gone");
            order.add("audit:" + event.subject());
            try (PreparedStatement ps = tx.connection().prepareStatement("INSERT INTO audits (event_id, operation) VALUES (?, ?)")) {
                ps.setString(1, event.eventId()); ps.setString(2, SinkSupport.commandName(command)); ps.executeUpdate();
            }
        }
    }

    private DataSource ds;
    private ThingRepository repo;
    private TableSink sink;
    private UnitOfWork uow;
    private final ExecutionContext ec = ExecutionContext.of("prn_test");

    @BeforeEach
    void setUp() throws SQLException {
        var h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds = h2;
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE things (id VARCHAR PRIMARY KEY, name VARCHAR)");
            s.execute("CREATE TABLE events (id VARCHAR PRIMARY KEY, subject VARCHAR)");
            s.execute("CREATE TABLE audits (event_id VARCHAR, operation VARCHAR)");
        }
        repo = new ThingRepository();
        sink = new TableSink();
        uow = new UnitOfWork(ds, sink);
    }

    private ThingSaved saved(String id) {
        return new ThingSaved(EventMetadata.of(ec, "test:things:thing:saved", "test:things", "things.thing." + id), id);
    }

    @Test
    void commitWritesAggregateEventAndAuditTogether() throws SQLException {
        var event = uow.commit(new Thing("t1", "one"), repo, saved("t1"), new ThingCommand("one"));

        assertThat(event.thingId()).isEqualTo("t1");
        assertThat(count("things")).isEqualTo(1);
        assertThat(count("events")).isEqualTo(1);
        assertThat(count("audits")).isEqualTo(1);
        assertThat(scalar("SELECT operation FROM audits")).isEqualTo("ThingCommand");
        assertThat(sink.order).containsExactly("event:things.thing.t1", "audit:things.thing.t1");
    }

    @Test
    void eventWriteFailureRollsBackThePersistedAggregate() throws SQLException {
        sink.failEvent = true;

        assertThatThrownBy(() -> uow.commit(new Thing("t1", "one"), repo, saved("t1"), new ThingCommand("one")))
                .isInstanceOfSatisfying(UseCaseException.class, e -> {
                    assertThat(e.code()).isEqualTo("EVENT_WRITE");
                    assertThat(e.httpStatus()).isEqualTo(500);
                    assertThat(e.getCause()).isInstanceOf(SQLException.class);
                });
        assertThat(count("things")).isZero();
        assertThat(count("events")).isZero();
    }

    @Test
    void auditWriteFailureRollsBackAggregateAndEvent() throws SQLException {
        sink.failAudit = true;

        assertThatThrownBy(() -> uow.commit(new Thing("t1", "one"), repo, saved("t1"), new ThingCommand("one")))
                .isInstanceOfSatisfying(UseCaseException.class, e -> assertThat(e.code()).isEqualTo("AUDIT_WRITE"));
        assertThat(count("things")).isZero();
        assertThat(count("events")).isZero();
        assertThat(count("audits")).isZero();
    }

    @Test
    void persistFailureSurfacesAsInternalPersistError() throws SQLException {
        repo.fail = true;
        assertThatThrownBy(() -> uow.commit(new Thing("t1", "one"), repo, saved("t1"), new ThingCommand("one")))
                .isInstanceOfSatisfying(UseCaseException.class, e -> assertThat(e.code()).isEqualTo("PERSIST"));
        assertThat(count("events")).isZero();
    }

    /// A concurrent writer got the unique key between validate and persist: the
    /// database's unique violation is a 409, not a 500. Anything else stays
    /// internal and names the aggregate. Mutant: every failure is internal.
    @Test
    void aUniqueViolationAtPersistIsAConflictAndOtherFailuresNameTheAggregate() throws SQLException {
        Persist<Thing> insertOnly = new Persist<>() {
            @Override public void persist(Thing t, DbTx tx) throws SQLException {
                try (PreparedStatement ps = tx.connection().prepareStatement("INSERT INTO things (id, name) VALUES (?, ?)")) {
                    ps.setString(1, t.id()); ps.setString(2, t.name()); ps.executeUpdate();
                }
            }
            @Override public void delete(Thing t, DbTx tx) {
            }
        };
        uow.commit(new Thing("t1", "one"), insertOnly, saved("t1"), new ThingCommand("one"));

        assertThatThrownBy(() -> uow.commit(new Thing("t1", "again"), insertOnly, saved("t1"), new ThingCommand("again")))
                .isInstanceOfSatisfying(UseCaseException.class, e -> {
                    assertThat(e.code()).isEqualTo("DUPLICATE_KEY");
                    assertThat(e.error()).isInstanceOf(io.flowcatalyst.sdk.usecase.UseCaseError.Conflict.class);
                    assertThat(e.getMessage()).contains("Thing t1");
                });

        repo.fail = true;
        assertThatThrownBy(() -> uow.commit(new Thing("t2", "two"), repo, saved("t2"), new ThingCommand("two")))
                .isInstanceOfSatisfying(UseCaseException.class, e -> {
                    assertThat(e.code()).isEqualTo("PERSIST");
                    assertThat(e.getMessage()).contains("Thing t2");
                });
    }

    @Test
    void commitDeleteRemovesTheRowAndRecordsTheEvent() throws SQLException {
        uow.commit(new Thing("t1", "one"), repo, saved("t1"), new ThingCommand("one"));
        uow.commitDelete(new Thing("t1", "one"), repo, saved("t1"), new ThingCommand("one"));
        assertThat(count("things")).isZero();
        assertThat(count("events")).isEqualTo(2);
    }

    @Test
    void commitSyncWritesSavesThenDeletesThenRollupInOrder() throws SQLException {
        uow.commit(new Thing("old", "old"), repo, saved("old"), new ThingCommand("old"));
        sink.order.clear();

        var rollup = uow.commitSync(repo,
                List.of(new SyncSave<>(new Thing("a", "a"), saved("a")), new SyncSave<>(new Thing("b", "b"), saved("b"))),
                List.of(new SyncDelete<>(new Thing("old", "old"), saved("old"))),
                saved("rollup"),
                new ThingCommand("sync"));

        assertThat(rollup.thingId()).isEqualTo("rollup");
        assertThat(sink.order).containsExactly(
                "event:things.thing.a", "audit:things.thing.a",
                "event:things.thing.b", "audit:things.thing.b",
                "event:things.thing.old", "audit:things.thing.old",
                "event:things.thing.rollup", "audit:things.thing.rollup");
        assertThat(count("things")).isEqualTo(2);
        assertThat(count("audits")).isEqualTo(5); // 1 from setup + 4
    }

    @Test
    void commitAllPersistsEveryAggregateWithOneSummaryEvent() throws SQLException {
        uow.commitAll(List.of(new Thing("a", "a"), new Thing("b", "b"), new Thing("c", "c")), repo, saved("batch"), new ThingCommand("all"));
        assertThat(count("things")).isEqualTo(3);
        assertThat(count("events")).isEqualTo(1);
    }

    @Test
    void inTransactionRollsBackEverythingWhenTheBodyThrows() throws SQLException {
        assertThatThrownBy(() -> uow.inTransaction(scoped -> {
            scoped.commit(new Thing("t1", "one"), repo, saved("t1"), new ThingCommand("one"));
            scoped.commit(new Thing("t2", "two"), repo, saved("t2"), new ThingCommand("two"));
            throw UseCaseException.businessRule("NOPE", "second thoughts");
        })).isInstanceOfSatisfying(UseCaseException.class, e -> assertThat(e.code()).isEqualTo("NOPE"));

        assertThat(count("things")).isZero();
        assertThat(count("events")).isZero();
        assertThat(count("audits")).isZero();
    }

    @Test
    void inTransactionCommitsScopedWritesAndRawSqlTogether() throws SQLException {
        String result = uow.inTransaction(scoped -> {
            scoped.commit(new Thing("t1", "one"), repo, saved("t1"), new ThingCommand("one"));
            try (PreparedStatement ps = scoped.connection().prepareStatement("INSERT INTO things (id, name) VALUES ('raw', 'raw')")) {
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(count("things")).isEqualTo(2);
        assertThat(count("events")).isEqualTo(1);
    }

    private int count(String table) throws SQLException {
        return Integer.parseInt(scalar("SELECT COUNT(*) FROM " + table));
    }

    private String scalar(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
