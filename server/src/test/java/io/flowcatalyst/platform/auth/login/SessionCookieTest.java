package io.flowcatalyst.platform.auth.login;

import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/cookie-hardening.md` §3: `SessionCookie` owns the name
/// (`__Host-fc_session` secure, `fc_session` otherwise) and `clear()`'s
/// secure-mode dual expiry.
class SessionCookieTest {

    private TestHttp http;

    @AfterEach
    void stop() {
        if (http != null) http.close();
    }

    @Test
    @DisplayName("name() is __Host-fc_session when secure, fc_session otherwise")
    void nameFollowsSecure() {
        assertThat(new SessionCookie(true, 60).name()).isEqualTo("__Host-fc_session");
        assertThat(new SessionCookie(false, 60).name()).isEqualTo("fc_session");
        // nameFor(boolean) is what Platform hands Authenticator.Config — must agree with
        // the instance method, or the mint side and the enforcement side can drift.
        assertThat(SessionCookie.nameFor(true)).isEqualTo(new SessionCookie(true, 60).name());
        assertThat(SessionCookie.nameFor(false)).isEqualTo(new SessionCookie(false, 60).name());
    }

    @Test
    @DisplayName("secure clear() expires __Host-fc_session AND a leftover fc_session")
    void secureClearExpiresBothNames() {
        var cookie = new SessionCookie(true, 60);
        http = TestHttp.routes(routes -> routes.get("/clear", ctx -> {
            cookie.clear(ctx);
            ctx.result("ok");
        }));
        var r = http.get("/clear");
        List<String> setCookies = r.headers().allValues("set-cookie");

        // Mutant: drop the legacy expiry (only write the __Host- one) — this fails, since
        // only one Set-Cookie header would be present instead of two.
        assertThat(setCookies).as("both the new and the pre-hardening cookie must be expired")
                .hasSize(2);
        assertThat(setCookies).anySatisfy(c -> assertThat(c)
                .startsWith("__Host-fc_session=;").contains("Max-Age=0").contains("Secure"));
        assertThat(setCookies).anySatisfy(c -> assertThat(c)
                .startsWith("fc_session=;").contains("Max-Age=0").doesNotContain("Secure"));
    }

    @Test
    @DisplayName("insecure clear() expires only fc_session — there is no __Host- name to leak from")
    void insecureClearExpiresOnlyThePlainName() {
        var cookie = new SessionCookie(false, 60);
        http = TestHttp.routes(routes -> routes.get("/clear", ctx -> {
            cookie.clear(ctx);
            ctx.result("ok");
        }));
        var r = http.get("/clear");
        List<String> setCookies = r.headers().allValues("set-cookie");

        assertThat(setCookies).hasSize(1);
        assertThat(setCookies.get(0)).startsWith("fc_session=;").contains("Max-Age=0").doesNotContain("Secure");
    }
}
