package io.flowcatalyst.platform.shared.database;

import io.flowcatalyst.platform.shared.auth.Visibility;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/// The one SQL predicate for a [Visibility]: repositories AND it into their
/// list reads so the caller's own client filters can only narrow within it.
public final class VisibilitySql {

    private VisibilitySql() {
    }

    /// Nothing for [Visibility.Everything]; `clientIdColumn IS NULL OR
    /// clientIdColumn IN (…)` for [Visibility.Tenants] (an empty list keeps
    /// platform-scoped rows only).
    public static Condition toCondition(Visibility visibility, Field<String> clientIdColumn) {
        return switch (visibility) {
            case Visibility.Everything _ -> DSL.noCondition();
            case Visibility.Tenants t -> clientIdColumn.isNull().or(clientIdColumn.in(t.clientIds()));
        };
    }
}
