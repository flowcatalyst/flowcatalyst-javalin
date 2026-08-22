package io.flowcatalyst.sdk.usecase.op;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.sdk.usecase.jdbc.Sink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationTest {

    record PingCommand(String text) {}

    record Pinged(EventMetadata metadata, String text) implements DomainEvent {
        @Override public Object data() { return new Data(text); }
        private record Data(String text) {}
    }

    /// Records every sink call; the H2 connection is real so `run` exercises
    /// the full transaction path.
    static final class RecordingSink implements Sink {
        final List<String> calls = new ArrayList<>();
        @Override public void writeEvent(DbTx tx, DomainEvent event) { calls.add("event:" + event.eventType()); }
        @Override public void writeAudit(DbTx tx, DomainEvent event, Object command) { calls.add("audit:" + command.getClass().getSimpleName()); }
    }

    private static UnitOfWork uow(RecordingSink sink) {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new UnitOfWork(ds, sink);
    }

    private static final ExecutionContext EC = ExecutionContext.of("prn_test");

    private static Pinged pinged(ExecutionContext ec, String text) {
        return new Pinged(EventMetadata.of(ec, "test:ping:pinged", "test", "test.ping." + text), text);
    }

    @Test
    void phasesRunInOrderAndThePlanIsAppliedAtomically() {
        var sink = new RecordingSink();
        var order = new ArrayList<String>();
        var op = Operation.<PingCommand, Pinged>named("Ping")
                .validate(_ -> order.add("validate"))
                .authorize(_ -> order.add("authorize"))
                .execute((cmd, ec) -> { order.add("execute"); return Plan.emit(pinged(ec, cmd.text())); });

        Pinged event = op.run(uow(sink), new PingCommand("hello"), EC);

        assertThat(order).containsExactly("validate", "authorize", "execute");
        assertThat(event.text()).isEqualTo("hello");
        assertThat(event.correlationId()).isEqualTo(EC.correlationId());
        assertThat(sink.calls).containsExactly("event:test:ping:pinged", "audit:PingCommand");
    }

    @Test
    void validationFailureStopsBeforeAuthorizeAndExecute() {
        var sink = new RecordingSink();
        var order = new ArrayList<String>();
        var op = Operation.<PingCommand, Pinged>named("Ping")
                .validate(cmd -> { if (cmd.text().isBlank()) throw UseCaseException.validation("TEXT_REQUIRED", "text required"); })
                .authorize(_ -> order.add("authorize"))
                .execute((cmd, ec) -> { order.add("execute"); return Plan.emit(pinged(ec, cmd.text())); });

        assertThatThrownBy(() -> op.run(uow(sink), new PingCommand(" "), EC))
                .isInstanceOfSatisfying(UseCaseException.class, e -> {
                    assertThat(e.error()).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(e.code()).isEqualTo("TEXT_REQUIRED");
                    assertThat(e.httpStatus()).isEqualTo(400);
                });
        assertThat(order).isEmpty();
        assertThat(sink.calls).isEmpty();
    }

    @Test
    void authorizationFailureStopsBeforeExecute() {
        var sink = new RecordingSink();
        var executed = new ArrayList<String>();
        var op = Operation.<PingCommand, Pinged>named("Ping")
                .authorize(_ -> { throw UseCaseException.authorization("ANCHOR_REQUIRED", "anchor scope required"); })
                .execute((cmd, ec) -> { executed.add("x"); return Plan.emit(pinged(ec, cmd.text())); });

        assertThatThrownBy(() -> op.run(uow(sink), new PingCommand("hi"), EC))
                .isInstanceOfSatisfying(UseCaseException.class, e -> assertThat(e.httpStatus()).isEqualTo(403));
        assertThat(executed).isEmpty();
        assertThat(sink.calls).isEmpty();
    }

    @Test
    void executeFailureCommitsNothing() {
        var sink = new RecordingSink();
        var op = Operation.<PingCommand, Pinged>named("Ping")
                .authorize(Operation.Authorize.publicAccess())
                .execute((_, _) -> { throw UseCaseException.conflict("EXISTS", "already there"); });

        assertThatThrownBy(() -> op.run(uow(sink), new PingCommand("hi"), EC))
                .isInstanceOfSatisfying(UseCaseException.class, e -> assertThat(e.error()).isInstanceOf(UseCaseError.Conflict.class));
        assertThat(sink.calls).isEmpty();
    }

    @Test
    void nullPlanIsAnInternalMisconfiguration() {
        var op = Operation.<PingCommand, Pinged>named("Ping")
                .authorize(Operation.Authorize.publicAccess())
                .execute((_, _) -> null);

        assertThatThrownBy(() -> op.run(uow(new RecordingSink()), new PingCommand("hi"), EC))
                .isInstanceOfSatisfying(UseCaseException.class, e -> assertThat(e.code()).isEqualTo("USECASE_NIL_PLAN"));
    }

    @Test
    void authorizeAndExecuteAreRequiredAtConstruction() {
        assertThatThrownBy(() -> new Operation<PingCommand, Pinged>("Ping", null, null, (_, _) -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("no Authorize phase");
        assertThatThrownBy(() -> new Operation<PingCommand, Pinged>("Ping", null, Operation.Authorize.publicAccess(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("no Execute phase");
        // validate is optional and defaults to a no-op
        var op = new Operation<PingCommand, Pinged>("Ping", null, Operation.Authorize.publicAccess(), (cmd, ec) -> Plan.emit(pinged(ec, cmd.text())));
        assertThat(op.validate()).isNotNull();
    }

    @Test
    void txOperationRunsItsPhasesThenOneTransaction() {
        var sink = new RecordingSink();
        var op = TxOperation.<PingCommand, String>named("PingTwice")
                .authorize(Operation.Authorize.publicAccess())
                .execute((scoped, cmd, ec) -> {
                    scoped.emitEvent(pinged(ec, cmd.text() + "-1"), cmd);
                    scoped.emitEvent(pinged(ec, cmd.text() + "-2"), cmd);
                    return "done:" + cmd.text();
                });

        assertThat(op.run(uow(sink), new PingCommand("x"), EC)).isEqualTo("done:x");
        assertThat(sink.calls).containsExactly(
                "event:test:ping:pinged", "audit:PingCommand", "event:test:ping:pinged", "audit:PingCommand");
    }
}
