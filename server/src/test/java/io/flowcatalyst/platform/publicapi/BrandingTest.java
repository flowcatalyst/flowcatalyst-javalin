package io.flowcatalyst.platform.publicapi;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/// The branding resolver against a real `app_platform_configs` (spec §4–6):
/// the two GLOBAL coordinates, the defaults on miss / blank, trimming, and
/// the stored document flowing into the login and email themes. The rows
/// are platform-wide singletons, so the class owns and clears them.
class BrandingTest {

    private static final Branding BRANDING = new Branding(BrandingFixture.REPO);

    @BeforeAll
    @AfterAll
    static void clearBrandingRows() {
        BrandingFixture.clearAll();
    }

    @AfterEach
    void clearAfterEach() {
        BrandingFixture.clearAll();
    }

    // ── Platform name (spec §4) ────────────────────────────────────────────

    @Test
    void platformNameDefaultsWhenNoRowExists() {
        assertThat(BRANDING.platformName()).isEqualTo(Branding.DEFAULT_PLATFORM_NAME);
    }

    @Test
    void platformNameDefaultsWhenTheStoredValueIsBlank() {
        BrandingFixture.set(Branding.PLATFORM_NAME, "   ");
        assertThat(BRANDING.platformName()).isEqualTo("Flowcatalyst");
    }

    @Test
    void platformNameIsTheStoredValueTrimmed() {
        BrandingFixture.set(Branding.PLATFORM_NAME, "  Acme Platform  ");
        assertThat(BRANDING.platformName()).isEqualTo("Acme Platform");
    }

    // ── Login theme (spec §5) ──────────────────────────────────────────────

    @Test
    void loginThemeIsEmptyWhenNoRowExists() {
        assertThat(BRANDING.loginTheme()).isEqualTo(LoginTheme.EMPTY);
    }

    @Test
    void loginThemeIsEmptyWhenTheStoredValueIsBlankOrMalformed() {
        BrandingFixture.set(Branding.LOGIN_THEME, "");
        assertThat(BRANDING.loginTheme()).as("blank").isEqualTo(LoginTheme.EMPTY);

        BrandingFixture.set(Branding.LOGIN_THEME, "{not json");
        assertThat(BRANDING.loginTheme()).as("malformed").isEqualTo(LoginTheme.EMPTY);
    }

    @Test
    void loginThemeIsTheStoredDocument() {
        BrandingFixture.set(Branding.LOGIN_THEME, "{\"brandName\":\"Acme\",\"logoHeight\":48,\"primaryColor\":\"red\"}");

        var theme = BRANDING.loginTheme();
        assertThat(theme.brandName()).isEqualTo("Acme");
        assertThat(theme.logoHeight()).isEqualTo(48);
        assertThat(theme.primaryColor()).as("echoed, not validated").isEqualTo("red");
        assertThat(theme.footerText()).isNull();
    }

    // ── Email theme (spec §6) ──────────────────────────────────────────────

    @Test
    void emailThemeLayersTheStoredThemeOverThePlatformName() {
        BrandingFixture.set(Branding.PLATFORM_NAME, "Acme Platform");
        BrandingFixture.set(Branding.LOGIN_THEME, "{\"primaryColor\":\"#111111\",\"accentColor\":\"red\",\"footerText\":\" Acme Inc \"}");

        var t = BRANDING.emailTheme();
        assertThat(t.brandName()).as("no brandName in the theme → platform name").isEqualTo("Acme Platform");
        assertThat(t.primaryColor()).isEqualTo("#111111");
        assertThat(t.accentColor()).as("unsafe colour → default").isEqualTo(EmailTheme.DEFAULT_ACCENT_COLOR);
        assertThat(t.footerText()).isEqualTo("Acme Inc");
    }

    @Test
    void emailThemeIsAllDefaultsWithoutAnyRows() {
        assertThat(BRANDING.emailTheme()).isEqualTo(EmailTheme.defaults("Flowcatalyst"));
    }

    // ── Unavailable store (spec §1, §4 last row, open question 3) ──────────

    @Test
    void aFailedLookupReadsAsNothingConfiguredAndWarns() {
        var log = (Logger) LoggerFactory.getLogger(Branding.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            var down = new Branding(new PlatformConfigRepository(new UnavailableDataSource()));

            assertThat(down.platformName()).isEqualTo(Branding.DEFAULT_PLATFORM_NAME);
            assertThat(down.loginTheme()).isEqualTo(LoginTheme.EMPTY);
            assertThat(down.emailTheme()).isEqualTo(EmailTheme.defaults(Branding.DEFAULT_PLATFORM_NAME));
            assertThat(captured.list)
                    .as("each failed lookup is a WARN, never an exception")
                    .isNotEmpty()
                    .allSatisfy(e -> {
                        assertThat(e.getLevel()).isEqualTo(Level.WARN);
                        assertThat(e.getFormattedMessage()).contains("using defaults");
                    });
        } finally {
            log.detachAppender(captured);
        }
    }

    /// A `DataSource` whose pool is gone: every `getConnection` fails the way
    /// a dropped database does.
    private static final class UnavailableDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("database unavailable", "08001");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
