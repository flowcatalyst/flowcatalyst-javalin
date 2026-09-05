package io.flowcatalyst.stream;

import io.flowcatalyst.sdk.usecase.jdbc.DbTx;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// `event_projection` (stream spec §4): claims `msg_events` rows not yet
/// projected and copies their derived read shape into `msg_events_read`.
/// One [Projector.Step] call is one claim-project-mark transaction
/// ([StreamTx]).
public final class EventProjection implements Projector.Step {

    private static final String CLAIM_SQL = """
            SELECT id FROM msg_events
            WHERE projected_at IS NULL
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    /// `application`/`subdomain`/`aggregate` derived from `type` (spec §4);
    /// `data` copied as text; `created_at` is the source row's own (same
    /// partition); `projected_at = now()`.
    private static final String INSERT_SQL = """
            INSERT INTO msg_events_read (
                id, spec_version, type, source, subject, "time", data, correlation_id, causation_id,
                deduplication_id, message_group, client_id, application, subdomain, aggregate,
                created_at, projected_at)
            SELECT id, spec_version, type, source, subject, "time", data::text, correlation_id, causation_id,
                deduplication_id, message_group, client_id,
                split_part(type, ':', 1),
                NULLIF(split_part(type, ':', 2), ''),
                NULLIF(split_part(type, ':', 3), ''),
                created_at, now()
            FROM msg_events
            WHERE id = ANY(?)
            ON CONFLICT (id, created_at) DO NOTHING
            """;

    private static final String MARK_PROJECTED_SQL = "UPDATE msg_events SET projected_at = now() WHERE id = ANY(?)";

    private final DataSource dataSource;

    public EventProjection(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public int step(int batchSize) {
        return StreamTx.run(dataSource, tx -> projectInTx(tx, batchSize));
    }

    private static int projectInTx(DbTx tx, int batchSize) {
        Connection conn = tx.connection();
        List<String> ids = claim(conn, batchSize);
        if (ids.isEmpty()) return 0;
        try {
            Array idArray = conn.createArrayOf("varchar", ids.toArray());
            try (PreparedStatement insert = conn.prepareStatement(INSERT_SQL)) {
                insert.setArray(1, idArray);
                insert.executeUpdate();
            }
            idArray = conn.createArrayOf("varchar", ids.toArray());
            try (PreparedStatement mark = conn.prepareStatement(MARK_PROJECTED_SQL)) {
                mark.setArray(1, idArray);
                mark.executeUpdate();
            }
        } catch (SQLException e) {
            throw new StreamTx.StreamStepException(e);
        }
        return ids.size();
    }

    private static List<String> claim(Connection conn, int batchSize) {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(CLAIM_SQL)) {
            stmt.setInt(1, batchSize);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString("id"));
                }
            }
        } catch (SQLException e) {
            throw new StreamTx.StreamStepException(e);
        }
        return ids;
    }
}
