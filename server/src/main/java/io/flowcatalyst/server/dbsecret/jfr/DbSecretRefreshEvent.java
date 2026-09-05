package io.flowcatalyst.server.dbsecret.jfr;

import jdk.jfr.Description;
import jdk.jfr.Label;
import jdk.jfr.Name;

/// One DB-secret refresh attempt (`docs/spec/jfr-events.md` §5), committed
/// in `DbSecretRefresher.refreshNow` after the apply succeeded or the fetch
/// failure was caught. Never carries credential material — only whether the
/// swap happened.
@Name("io.flowcatalyst.server.dbsecret.DbSecretRefresh")
@Label("DB Secret Refresh")
@Description("A DB-secret refresh attempt succeeded or failed; never carries credential material")
public final class DbSecretRefreshEvent extends DbSecretEvent {

    /// Credentials swapped.
    @Label("Succeeded")
    public boolean succeeded;

    /// On failure.
    @Label("Error")
    public String error;
}
