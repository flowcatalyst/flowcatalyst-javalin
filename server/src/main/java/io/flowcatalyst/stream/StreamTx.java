package io.flowcatalyst.stream;

import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.ToIntFunction;

/// One connection, one transaction, one claim-and-act step — the shape every
/// stream projector's step shares (stream spec §3-§5): open a connection,
/// `setAutoCommit(false)`, wrap it as [DbTx#wrapForBootstrap] (this is
/// router/scheduler-adjacent background infrastructure, not a human-initiated
/// use case), run `work`, commit and return its result; any exception rolls
/// the transaction back and is rethrown so the caller's row-level effects
/// (a stamped `fanned_out_at`, a marked `projected_at`) never happen. Mirrors
/// [io.flowcatalyst.platform.scheduler.PendingJobPoller#claimAndMark],
/// generalised across the three claim-based projectors so the
/// connection/commit/rollback scaffolding is written once.
final class StreamTx {

    private static final Logger LOG = LoggerFactory.getLogger(StreamTx.class);

    private StreamTx() {
    }

    static int run(DataSource dataSource, ToIntFunction<DbTx> work) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int result = work.applyAsInt(DbTx.wrapForBootstrap(conn));
                conn.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(conn);
                throw e;
            }
        } catch (SQLException e) {
            throw new StreamStepException(e);
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            LOG.warn("stream step transaction rollback failed", rollbackFailure);
        }
    }

    /// Wraps a claim-transaction JDBC failure — connection acquisition,
    /// commit, or the claim/insert/update statements themselves. Unchecked:
    /// [Projector] catches it like any other step exception and retries on
    /// the next tick.
    static final class StreamStepException extends RuntimeException {
        StreamStepException(SQLException cause) {
            super("stream step failed", cause);
        }
    }
}
