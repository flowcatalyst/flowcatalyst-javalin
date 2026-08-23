package io.flowcatalyst.platform.publicapi;

import io.flowcatalyst.platform.platformconfig.ConfigCoordinate;
import io.flowcatalyst.platform.platformconfig.PlatformConfig;
import io.flowcatalyst.platform.platformconfig.PlatformConfigRepository;
import io.flowcatalyst.sdk.usecase.jdbc.DbTx;
import io.flowcatalyst.testpg.TestPg;

import java.sql.SQLException;

/// Seeds and clears the two platform-wide branding rows (spec §4, §5) for
/// the DB-backed tests. Bootstrap-style writes: the platform-config use
/// cases are another aggregate's, and these tests only need a row to exist.
/// The coordinates are singletons, so every test class that touches them
/// clears them before and after.
public final class BrandingFixture {

    public static final PlatformConfigRepository REPO = new PlatformConfigRepository(TestPg.dataSource());

    private BrandingFixture() {
    }

    public static void set(ConfigCoordinate c, String value) {
        try (var conn = TestPg.dataSource().getConnection()) {
            var tx = DbTx.wrapForBootstrap(conn);
            var row = REPO.findByCoordinate(c).map(existing -> existing.set(value, null, null)).orElseGet(() -> PlatformConfig.create(c, value));
            REPO.persist(row, tx);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void clear(ConfigCoordinate c) {
        try (var conn = TestPg.dataSource().getConnection()) {
            var tx = DbTx.wrapForBootstrap(conn);
            REPO.findByCoordinate(c).ifPresent(row -> REPO.delete(row, tx));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Both branding rows gone.
    public static void clearAll() {
        clear(Branding.PLATFORM_NAME);
        clear(Branding.LOGIN_THEME);
    }
}
