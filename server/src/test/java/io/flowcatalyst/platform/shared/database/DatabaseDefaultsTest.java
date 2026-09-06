package io.flowcatalyst.platform.shared.database;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

/// `docs/spec/admission.md` §5 / §6 row 9: the pool opens gated at 32 per pod.
class DatabaseDefaultsTest {

    @Test
    void thePoolOpensGatedAtThirtyTwoWithTwoReservedProbePermits() throws Exception {
        String url = TestPg.instance().getJdbcUrl("postgres", "postgres");
        try (GatedDataSource pool = Database.newPool(url)) {
            assertThat(pool.poolSize()).isEqualTo(32);
            assertThat(pool.reserved()).isEqualTo(2);
            assertThat(pool.hikari().getMaximumPoolSize()).isEqualTo(32);
            assertThat(Database.DEFAULT_POOL_SIZE).isEqualTo(32);
        }
    }
}
