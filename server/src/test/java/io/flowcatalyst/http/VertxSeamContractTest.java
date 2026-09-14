package io.flowcatalyst.http;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/// `docs/spec/http-seam.md` §4 rows 1–9 against the Vert.x adapter.
class VertxSeamContractTest extends SeamContract {
    @BeforeAll
    static void up() {
        start();
    }

    @AfterAll
    static void down() {
        stop();
    }
}
