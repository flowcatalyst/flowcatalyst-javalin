package io.flowcatalyst.fnhost;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.testpg.TestPg;
import org.junit.jupiter.api.Test;

/// This module gets `TestPg` — and the extension that makes it per-class —
/// through the server's test-jar. If the autodetection property or the
/// `META-INF/services` entry stops reaching this module's test classpath,
/// every test here still passes, on ONE shared database. This fails instead.
class PerClassDatabaseTest {

    @Test
    void thisModulesTestClassesGetTheirOwnDatabaseToo() {
        assertThat(TestPg.databaseName()).startsWith("fc_test_").isNotEqualTo("fc_test_unowned");
    }
}
