package io.flowcatalyst.example.subscriptiontest;

import io.flowcatalyst.function.Event;
import io.flowcatalyst.function.Function;
import io.flowcatalyst.function.FunctionContext;
import io.flowcatalyst.function.Request;
import io.flowcatalyst.function.Result;
import io.flowcatalyst.function.Webhook;

import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/// The subscription test: every `platform:admin:eventtypes:synced` event the
/// platform delivers lands as one row in `received_events`, in a database the
/// function reaches through a platform-held secret (`manifest.json`:
/// `db[].secretRef = EVENTS_DSN`) — it never sees a password. The whole path
/// is exercised: manifest subscription → wiring at promote → the router's
/// signed delivery → this function → the host's pool for the declared DSN.
///
/// Idempotent on the envelope's id: the router redelivers on a retry, and a
/// redelivery must be a no-op, not a second row.
public final class SubscriptionTestFunction implements Function {

    static final String TABLE = "received_events";

    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS received_events (
                id           varchar(26) PRIMARY KEY,
                event_id     text NOT NULL UNIQUE,
                event_type   text NOT NULL,
                subject      text,
                received_at  timestamptz NOT NULL DEFAULT now(),
                event_data   jsonb NOT NULL
            )""";
    private static final String INSERT = """
            INSERT INTO received_events (id, event_id, event_type, subject, event_data)
            VALUES (?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (event_id) DO NOTHING""";

    @Override
    public void init(FunctionContext ctx) throws SQLException {
        DataSource db = ctx.dataSource("events");
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute(CREATE);
        }
        ctx.logger().log(Level.INFO, "subscription test ready (address={0}, version={1})", ctx.address(), ctx.version());
    }

    @Override
    public Result handle(Request in, FunctionContext ctx) throws Exception {
        if (in.path().equals("/healthz")) {
            return Result.json(200, "{\"status\":\"ok\"}");
        }
        if (!in.path().equals("/events/received")) {
            return Result.fail("no route for path " + in.path());
        }
        Event event = Webhook.event(in);
        String envelope = new String(in.body(), StandardCharsets.UTF_8);
        int inserted;
        try (Connection c = ctx.dataSource("events").getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setString(1, Tsid.next());
            ps.setString(2, event.id());
            ps.setString(3, event.type());
            ps.setString(4, event.subject());
            ps.setString(5, envelope);
            inserted = ps.executeUpdate();
        }
        ctx.logger().log(Level.INFO, "received {0} (id={1}, subject={2}) — {3}",
                event.type(), event.id(), event.subject(), inserted == 1 ? "stored" : "already stored");
        return Result.ack();
    }
}
