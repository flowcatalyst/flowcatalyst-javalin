package io.flowcatalyst.sdk.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.flowcatalyst.sdk.sync.Definitions.DefinitionSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An application code is given directly or inherited from
 * {@code FLOWCATALYST_APP_CODE}. There is deliberately no per-definition
 * override: the set a definition is built into IS its application, and a
 * codebase owning several builds one set each for
 * {@code definitions().syncAll(…)}.
 *
 * <p>The rejection branch is asserted through {@code defineFrom(String)}
 * rather than {@code defineFromEnv()}. The JDK cannot mutate the process
 * environment, so a test driving {@code defineFromEnv()} could only assert
 * whichever branch the surrounding environment happened to give it — leaving
 * the rejection path unexercised on every machine with the variable set,
 * which is precisely the path that matters.
 */
class ApplicationCodeTest {

    @Test
    @DisplayName("define takes the application code directly")
    void defineTakesTheCodeDirectly() {
        assertEquals("orders", DefinitionSet.define("orders").applicationCode());
    }

    @Test
    @DisplayName("a resolved code is used as-is")
    void resolvedCodeIsUsed() {
        assertEquals("orders", DefinitionSet.defineFrom("orders").applicationCode());
    }

    @Test
    @DisplayName("an unset or blank code is rejected at the call site, not deferred to the request")
    void missingCodeIsRejectedImmediately() {
        // Without this a null code would surface much later as a request to
        // /api/applications/null/… — a 404 from the platform, attributed to
        // the platform, at sync time rather than at startup.
        for (String absent : new String[] {null, "", "   "}) {
            var thrown = assertThrows(IllegalStateException.class, () -> DefinitionSet.defineFrom(absent));
            assertTrue(thrown.getMessage().contains(DefinitionSet.APP_CODE_ENV),
                    "the message must name the variable to set, was: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("defineFromEnv reads FLOWCATALYST_APP_CODE and decides the same way")
    void defineFromEnvReadsTheVariable() {
        String fromEnv = System.getenv(DefinitionSet.APP_CODE_ENV);
        if (fromEnv == null || fromEnv.isBlank()) {
            assertThrows(IllegalStateException.class, DefinitionSet::defineFromEnv);
        } else {
            assertEquals(fromEnv, DefinitionSet.defineFromEnv().applicationCode());
        }
        assertNotNull(DefinitionSet.APP_CODE_ENV);
    }
}
