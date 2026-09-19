package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/// `TriggerObjectRepository` against the embedded Postgres (spec
/// `function-invocation.md` §4, §4.2). This slice (I1) wires no caller for
/// [TriggerObjectRepository#link]/[#unlink] yet — that is `TriggerSync`'s
/// job (slice I2) — so this test is this repository's only coverage of its
/// own read/write contract.
class TriggerObjectRepositoryTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final FunctionRepository FUNCTION_REPO = new FunctionRepository(DS);
    private static final TriggerObjectRepository REPO = new TriggerObjectRepository(DS);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private static String fresh() {
        return "t" + Long.toString(SEQ.incrementAndGet(), 36);
    }

    private static Function createFunction() {
        Function f = Function.create(fresh(),
                FunctionAddress.of(new DnsLabel("app-" + RUN + "-" + fresh()), new DnsLabel("svc"), new DnsLabel("fn")),
                FunctionOwner.ofClientId(fresh()), Runtime.JVM, null);
        UOW.inTransaction(tx -> {
            FUNCTION_REPO.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static void link(TriggerObject obj) {
        UOW.inTransaction(tx -> {
            REPO.link(obj, tx.dbTx());
            return null;
        });
    }

    private static void unlink(String functionId, TriggerObjectKind kind, String triggerKey) {
        UOW.inTransaction(tx -> {
            REPO.unlink(functionId, kind, triggerKey, tx.dbTx());
            return null;
        });
    }

    @Test
    void linkListByFunctionAndObjectIdsRoundTrip() {
        Function f = createFunction();
        String poolTriggerKey = "fn-" + f.id();
        String poolObjectId = "dsp_" + fresh();
        Instant now = Instant.now();

        link(TriggerObject.of(f.id(), TriggerObjectKind.POOL, poolObjectId, poolTriggerKey, now));

        assertThat(REPO.listByFunction(f.id())).hasSize(1);
        TriggerObject stored = REPO.listByFunction(f.id()).get(0);
        assertThat(stored.functionId()).isEqualTo(f.id());
        assertThat(stored.kind()).isEqualTo(TriggerObjectKind.POOL);
        assertThat(stored.objectId()).isEqualTo(poolObjectId);
        assertThat(stored.triggerKey()).isEqualTo(poolTriggerKey);

        assertThat(REPO.objectIds(TriggerObjectKind.POOL)).contains(poolObjectId);
        assertThat(REPO.objectIds(TriggerObjectKind.SUBSCRIPTION)).as("kinds are not mixed").doesNotContain(poolObjectId);
    }

    @Test
    void objectIdsIsScopedByKindAcrossManyFunctions() {
        Function f1 = createFunction();
        Function f2 = createFunction();
        String pool1 = "dsp_" + fresh();
        String sub2 = "sub_" + fresh();

        link(TriggerObject.of(f1.id(), TriggerObjectKind.POOL, pool1, "fn-" + f1.id(), Instant.now()));
        link(TriggerObject.of(f2.id(), TriggerObjectKind.SUBSCRIPTION, sub2, "fn-" + f2.id() + "-a", Instant.now()));

        Set<String> pools = REPO.objectIds(TriggerObjectKind.POOL);
        Set<String> subs = REPO.objectIds(TriggerObjectKind.SUBSCRIPTION);
        assertThat(pools).contains(pool1).doesNotContain(sub2);
        assertThat(subs).contains(sub2).doesNotContain(pool1);
    }

    @Test
    void linkUpsertsTheSameTriggerKeyToANewObjectId() {
        Function f = createFunction();
        String triggerKey = "fn-" + f.id() + "-" + fresh();
        String firstObjectId = "sub_" + fresh();
        String secondObjectId = "sub_" + fresh();

        link(TriggerObject.of(f.id(), TriggerObjectKind.SUBSCRIPTION, firstObjectId, triggerKey, Instant.now()));
        link(TriggerObject.of(f.id(), TriggerObjectKind.SUBSCRIPTION, secondObjectId, triggerKey, Instant.now()));

        List<TriggerObject> rows = REPO.listByFunction(f.id());
        assertThat(rows).as("re-linking the same trigger key replaces, not duplicates, the row").hasSize(1);
        assertThat(rows.get(0).objectId()).isEqualTo(secondObjectId);
        assertThat(REPO.objectIds(TriggerObjectKind.SUBSCRIPTION))
                .as("the old object id is gone once its trigger key points elsewhere").doesNotContain(firstObjectId);
    }

    @Test
    void unlinkRemovesOnlyTheNamedRow() {
        Function f = createFunction();
        String keyA = "fn-" + f.id() + "-a";
        String keyB = "fn-" + f.id() + "-b";
        link(TriggerObject.of(f.id(), TriggerObjectKind.SCHEDULED_JOB, "sched_" + fresh(), keyA, Instant.now()));
        link(TriggerObject.of(f.id(), TriggerObjectKind.SCHEDULED_JOB, "sched_" + fresh(), keyB, Instant.now()));
        assertThat(REPO.listByFunction(f.id())).hasSize(2);

        unlink(f.id(), TriggerObjectKind.SCHEDULED_JOB, keyA);

        List<TriggerObject> remaining = REPO.listByFunction(f.id());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).triggerKey()).isEqualTo(keyB);
    }
}
