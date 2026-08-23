package io.flowcatalyst.platform.publicapi.api;

import io.flowcatalyst.platform.publicapi.Branding;
import io.flowcatalyst.platform.publicapi.LoginTheme;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.Objects;

/// The `/api/public` surface (spec `docs/spec/publicapi.md` §2): the two
/// read-only routes the SPA fetches **before sign-in**. They are mounted
/// outside the authenticator (`Platform.isPublicPath`) and outside the
/// lockfile; the handlers consult no [io.flowcatalyst.platform.shared.auth.Auth]
/// context and never fail — a missing or malformed configuration degrades to
/// the defaults (spec §1).
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/public/platform` | 200 [PlatformResponse] |
/// | GET | `/api/public/login-theme` | 200 [LoginThemeResponse] (`{}` when unconfigured) |
public final class PublicApi {

    /// The only feature flag today — static until a flag source exists (spec §3, open question 5).
    static final boolean MESSAGING_ENABLED = true;

    private PublicApi() {
    }

    /// The handlers' dependency — the branding resolver; nothing here writes.
    public record State(Branding branding) {
        public State {
            Objects.requireNonNull(branding, "branding");
        }
    }

    /// Mounts the routes. Callers register them on the same router as the
    /// platform API; the authenticator skips them by path, not by handler.
    public static void register(JavalinDefaultRoutingApi routes, State s) {
        routes.get("/api/public/platform", ctx -> platform(ctx, s));
        // Same document on the legacy path the SPA's platformConfig store fetches pre-login (spec §9 Q1).
        routes.get("/api/config/platform", ctx -> platform(ctx, s));
        routes.get("/api/public/login-theme", ctx -> loginTheme(ctx, s));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void platform(Context ctx, State s) {
        ctx.json(PlatformResponse.of(s.branding().platformName()));
    }

    /// `clientId` is accepted and ignored — the theme is global (spec §5, open question 2).
    private static void loginTheme(Context ctx, State s) {
        ctx.json(LoginThemeResponse.from(s.branding().loginTheme()));
    }

    // ── Wire DTOs (spec §3) ────────────────────────────────────────────────

    /// `{features: {messagingEnabled}, platformName}`.
    public record PlatformResponse(FeaturesResponse features, String platformName) {
        static PlatformResponse of(String platformName) {
            return new PlatformResponse(new FeaturesResponse(MESSAGING_ENABLED), platformName);
        }
    }

    /// `{messagingEnabled}`.
    public record FeaturesResponse(boolean messagingEnabled) {
    }

    /// The stored theme echoed field-for-field; absent fields are omitted,
    /// stored `""` values are kept (spec §3).
    public record LoginThemeResponse(
            String brandName,
            String brandSubtitle,
            String logoUrl,
            String logoSvg,
            Integer logoHeight,
            String primaryColor,
            String accentColor,
            String backgroundColor,
            String backgroundGradient,
            String footerText,
            String customCss) {

        static LoginThemeResponse from(LoginTheme t) {
            return new LoginThemeResponse(t.brandName(), t.brandSubtitle(), t.logoUrl(), t.logoSvg(), t.logoHeight(),
                    t.primaryColor(), t.accentColor(), t.backgroundColor(), t.backgroundGradient(), t.footerText(), t.customCss());
        }
    }
}
