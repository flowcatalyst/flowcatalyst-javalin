package io.flowcatalyst.router.api.dashboard;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/// Serves the router's monitoring dashboard (`docs/spec/router.md` §9.6, Go
/// `router/api/dashboard.go` + `dashboard.html`) — a single static page
/// copied verbatim from the Go repository into
/// `server/src/main/resources/router/dashboard.html`, with the mount prefix
/// substituted for the `__FC_API_BASE__` token so `window.__API_BASE__`
/// (consumed by the page's own `fetchWithAuth`) works under nested mounting.
///
/// The page carries no behaviour this class reproduces beyond that
/// substitution — its JavaScript is not ported, per spec §9.6.
public final class DashboardHandler implements Handler {

    private static final String RESOURCE_PATH = "/router/dashboard.html";
    private static final String TOKEN = "__FC_API_BASE__";

    private final String rendered;

    /// @param prefix the mount prefix (e.g. `/router`); `null`/blank means
    ///                mounted at root, substituting `""` for the token —
    ///                matching Go's `strings.TrimSuffix` yielding `""` at root.
    public DashboardHandler(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "" : prefix;
        this.rendered = loadTemplate().replace(TOKEN, p);
    }

    @Override
    public void handle(Context ctx) {
        ctx.html(rendered);
    }

    /// Mounts at both `<prefix>/monitoring/dashboard` and `<prefix>/dashboard.html`
    /// — the same two paths Go's `dashboard.go` doc comment names.
    public static void register(JavalinDefaultRoutingApi routes, String prefix) {
        var handler = new DashboardHandler(prefix);
        String p = prefix == null || prefix.isBlank() ? "" : prefix;
        routes.get(p + "/monitoring/dashboard", handler);
        routes.get(p + "/dashboard.html", handler);
    }

    private static String loadTemplate() {
        try (InputStream in = DashboardHandler.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + RESOURCE_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
