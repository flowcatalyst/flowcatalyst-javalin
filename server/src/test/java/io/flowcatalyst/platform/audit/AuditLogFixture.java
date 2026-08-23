package io.flowcatalyst.platform.audit;

import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.tsid.Tsid;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.EventMetadata;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;

/// Seeds `aud_logs` the only way the platform ever does: a domain event +
/// command through a real [UnitOfWork] with the [PlatformSink], so what the
/// repository reads back is genuine sink output (spec §9). Each fixture owns
/// a per-JVM namespace so tests never see one another's rows.
public final class AuditLogFixture {

    public static final DataSource DS = TestPg.dataSource();
    public static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    public static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    /// Per-JVM namespace; the aggregate segment of the subject carries it, so
    /// `entity_type` is unique to the run (`Audtest<run>`).
    public static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);

    private AuditLogFixture() {
    }

    /// The command whose simple name becomes `aud_logs.operation`.
    public record SeedCommand(String note, int n) {
    }

    /// A second command name, for operation filtering.
    public record OtherCommand(String note) {
    }

    /// A minimal domain event whose subject is `platform.<aggregate>.<id>`.
    public record Seeded(EventMetadata metadata, Object data) implements DomainEvent {
    }

    /// `aggregate` segment for this run: `<tag><RUN>`; the sink capitalises it.
    public static String aggregate(String tag) {
        return tag + RUN;
    }

    /// What the sink stores in `entity_type` for [#aggregate].
    public static String entityType(String tag) {
        String a = aggregate(tag);
        return Character.toUpperCase(a.charAt(0)) + a.substring(1);
    }

    /// Emits one event as `principalId` at `occurredAt` with `command`, and
    /// returns the audit row id the sink wrote.
    public static String seed(String aggregate, String entityId, String principalId, Instant occurredAt, Object command) {
        var metadata = new EventMetadata(Tsid.generate(), EventMetadata.SPEC_VERSION, "platform:admin",
                "platform:admin:" + aggregate + ":seeded", "platform." + aggregate + "." + entityId, occurredAt,
                UUID.randomUUID().toString(), null, principalId, UUID.randomUUID().toString(), null);
        var event = UOW.emitEvent(new Seeded(metadata, command), command);
        return DB.select(DSL.field("id", String.class)).from("aud_logs")
                .where(DSL.field("entity_id", String.class).eq(entityId))
                .and(DSL.field("performed_at").eq(occurredAt.atOffset(ZoneOffset.UTC)))
                .and(DSL.field("entity_type", String.class).eq(Character.toUpperCase(aggregate.charAt(0)) + aggregate.substring(1)))
                .orderBy(DSL.field("id").desc()) // TSIDs are time-ordered: the newest id is the row just written
                .limit(1)
                .fetchOne(DSL.field("id", String.class));
    }

    /// A fresh entity id (`evt_…` — any prefix works; the audit row does not care).
    public static String entityId() {
        return EntityType.EVENT_TYPE.generate();
    }

    /// Inserts an `iam_principals` row so the name join has something to find.
    public static String principal(String name) {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id)
                .set(IAM_PRINCIPALS.TYPE, "USER")
                .set(IAM_PRINCIPALS.NAME, name)
                .set(IAM_PRINCIPALS.EMAIL, id + "@audit.test")
                .execute();
        return id;
    }
}
