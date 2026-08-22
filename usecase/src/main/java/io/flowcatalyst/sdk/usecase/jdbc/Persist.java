package io.flowcatalyst.sdk.usecase.jdbc;

import io.flowcatalyst.sdk.usecase.HasId;

import java.sql.SQLException;

/// Implemented by a repository to upsert and delete aggregates of type `A`
/// inside the unit of work's transaction. Implement it on the *repository*,
/// not the aggregate — aggregates don't persist themselves.
///
/// ```java
/// public final class EventTypeRepository implements Persist<EventType> {
///     @Override public void persist(EventType et, DbTx tx) {
///         DSL.using(tx.connection()).insertInto(EVENT_TYPES)...onConflict(EVENT_TYPES.ID).doUpdate()...execute();
///     }
///     @Override public void delete(EventType et, DbTx tx) { ... }
/// }
/// ```
///
/// Any exception thrown here (checked `SQLException` or unchecked, e.g. a
/// jOOQ `DataAccessException`) rolls the whole transaction back and surfaces
/// as an internal `PERSIST` / `DELETE` use-case error.
public interface Persist<A extends HasId> {

    void persist(A aggregate, DbTx tx) throws SQLException;

    void delete(A aggregate, DbTx tx) throws SQLException;
}
