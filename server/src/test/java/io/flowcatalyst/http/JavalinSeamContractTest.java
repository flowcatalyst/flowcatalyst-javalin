package io.flowcatalyst.http;

import io.flowcatalyst.platform.shared.TestHttp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/// `docs/spec/http-seam.md` §4 rows 1–9 against the Javalin adapter.
class JavalinSeamContractTest extends SeamContract {
    @BeforeAll
    static void up() {
        start(TestHttp.Adapter.JAVALIN);
    }

    @AfterAll
    static void down() {
        stop();
    }
}
