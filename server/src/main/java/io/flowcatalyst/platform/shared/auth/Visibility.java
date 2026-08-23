package io.flowcatalyst.platform.shared.auth;

import java.util.List;

/// Whose view a tenant-scoped read is: which rows the caller may see, stated
/// as a value so a repository can enforce it in SQL (never a nullable client
/// list). Built from the principal by [AuthContext#visibility()]; a
/// repository's read filter carries it as a required component (no default
/// view — see CONVENTIONS §8). The one SQL predicate for it lives in
/// `shared/database` (`VisibilitySql.toCondition`), keeping this package free
/// of jOOQ.
public sealed interface Visibility permits Visibility.Everything, Visibility.Tenants {

    /// Every row — the anchor's view. A singleton: [#INSTANCE].
    final class Everything implements Visibility {
        public static final Everything INSTANCE = new Everything();

        private Everything() {
        }

        @Override
        public String toString() {
            return "Visibility.Everything";
        }
    }

    /// Platform-scoped rows (`client_id IS NULL`) plus rows of these clients;
    /// an empty list is platform-scoped rows only.
    record Tenants(List<String> clientIds) implements Visibility {
        public Tenants {
            clientIds = clientIds == null ? List.of() : List.copyOf(clientIds);
        }
    }
}
