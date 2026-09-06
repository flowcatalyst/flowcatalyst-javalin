package io.flowcatalyst.router.api.dashboard;

import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Handler;
import io.flowcatalyst.http.Routes;

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

    /// The last bytes of a complete document. Checked because a partial read
    /// has no other symptom — see [#loadTemplate].
    private static final String CLOSING_TAG = "</html>";

    private final String rendered;

    /// @param prefix the mount prefix (e.g. `/router`); `null`/blank means
    ///                mounted at root, substituting `""` for the token —
    ///                matching Go's `strings.TrimSuffix` yielding `""` at root.
    public DashboardHandler(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "" : prefix;
        this.rendered = loadTemplate().replace(TOKEN, p);
    }

    @Override
    public void handle(Exchange ctx) {
        ctx.html(rendered);
    }

    /// Mounts at both `<prefix>/monitoring/dashboard` and `<prefix>/dashboard.html`
    /// — the same two paths Go's `dashboard.go` doc comment names.
    public static void register(Routes routes, String prefix) {
        var handler = new DashboardHandler(prefix);
        String p = prefix == null || prefix.isBlank() ? "" : prefix;
        routes.get(p + "/monitoring/dashboard", handler);
        routes.get(p + "/dashboard.html", handler);
    }

    /// Reads the page and **refuses an incomplete one**.
    ///
    /// A missing resource already threw. A *partial* one did not, and it is
    /// the more likely accident: the resource is copied into `target/classes`
    /// by the build, so a second Maven run over the same `target/` can be
    /// observed mid-copy — the hazard `Claude.md` documents under "Build
    /// hygiene under multiple agents". `readAllBytes` returns what is there
    /// so far, quite happily, and an empty read produces a handler that
    /// answers **200 with an empty body**: the right status, the right
    /// content type, and no page. Nothing downstream can tell that apart from
    /// a dashboard that legitimately renders nothing.
    private static String loadTemplate() {
        String template;
        try (InputStream in = DashboardHandler.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("classpath resource not found: " + RESOURCE_PATH);
            }
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return validated(template);
    }

    /// The completeness rules, separated from the reading that supplies the
    /// bytes so a test can state a partial document instead of contriving one
    /// on a classpath.
    ///
    /// Both rules are needed. The token sits at byte 845 of ~91 KB, so its
    /// presence says nothing about the other 99%; the closing tag is the only
    /// cheap evidence the whole document arrived.
    static String validated(String template) {
        if (!template.contains(TOKEN)) {
            throw new IllegalStateException(RESOURCE_PATH + " has no " + TOKEN
                    + " token to substitute (" + template.length() + " chars read)");
        }
        if (!template.stripTrailing().endsWith(CLOSING_TAG)) {
            throw new IllegalStateException(RESOURCE_PATH + " is truncated: it does not end in "
                    + CLOSING_TAG + " (" + template.length() + " chars read)");
        }
        return template;
    }
}
