package io.flowcatalyst.platform.seed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Every event type an operation emits is in the seeded catalogue, so a
/// subscription can pick it — except the known gaps below (none). The client
/// events were once catalogued as `platform:iam:client:*` while the client
/// operations emit `platform:admin:client:*` (Go `25c3ed6`, Java F3
/// 2026-10-11), and 81 other codes were missing (Go F9); this is the check
/// that would have caught both. Go's `TestEveryEmittedEventTypeIsCatalogued`
/// scans its `events.go` files the same way.
class EmittedCatalogueTest {

    /// `platform:{subdomain}:{aggregate}:{event}`.
    private static final Pattern EVENT_CODE = Pattern.compile("^platform:[a-z-]+:[a-z-]+:[a-z-]+$");

    /// A `static final String NAME = "…";` constant.
    private static final Pattern CONSTANT = Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"\\s*;");

    /// A type declaring that it is a `DomainEvent`.
    private static final Pattern DOMAIN_EVENT = Pattern.compile("implements\\s+(?:[\\w.]+\\s*,\\s*)*DomainEvent\\b");

    /// Event types the platform emits that the seeded catalogue does not list
    /// yet. A subscription cannot pick them from the catalogue. Empty since the
    /// 81 found 2026-10-11 were catalogued (Go 8ddaed4, F9; ledger
    /// docs/go-mirror/2026-10-11-event-catalogue.md); keep it empty: catalogue
    /// a new emitted code rather than listing it here. The list may only
    /// shrink: an entry that becomes catalogued, or stops being emitted, fails
    /// the test until removed.
    private static final Set<String> KNOWN_UNCATALOGUED = Set.of();

    /// Code → `File: CONSTANT` for every event-type constant in an `*Events.java`
    /// under the platform sources (one events file per aggregate, CONVENTIONS §2),
    /// and in any other source file declaring a `DomainEvent` (`AuditLogsRedacted`
    /// stands alone, outside an events file).
    private static Map<String, String> emittedEventTypes() {
        Path root = Path.of("src/main/java/io/flowcatalyst/platform");
        assertThat(root).as("platform sources (surefire runs in the server module)").isDirectory();
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String source = Files.readString(f);
                if (!f.getFileName().toString().endsWith("Events.java") && !DOMAIN_EVENT.matcher(source).find()) {
                    continue;
                }
                var m = CONSTANT.matcher(source);
                while (m.find()) {
                    if (EVENT_CODE.matcher(m.group(2)).matches()) {
                        out.put(m.group(2), f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(out.size()).as("emitted event types found; fewer means the scan is broken").isGreaterThanOrEqualTo(100);
        return out;
    }

    @Test
    void everyEmittedEventTypeIsCatalogued() {
        Set<String> catalogued = PlatformEventTypes.all().stream()
                .map(PlatformEventTypes.Definition::code).collect(Collectors.toSet());
        Map<String, String> emitted = emittedEventTypes();

        Set<String> missing = new TreeSet<>();
        emitted.forEach((code, where) -> {
            if (!catalogued.contains(code) && !KNOWN_UNCATALOGUED.contains(code)) missing.add(code + " (" + where + ")");
        });
        assertThat(missing).as("emitted but not in the seeded catalogue").isEmpty();

        assertThat(KNOWN_UNCATALOGUED.stream().filter(catalogued::contains).toList())
                .as("catalogued now; remove from KNOWN_UNCATALOGUED").isEmpty();
        assertThat(KNOWN_UNCATALOGUED.stream().filter(c -> !emitted.containsKey(c)).toList())
                .as("no longer emitted; remove from KNOWN_UNCATALOGUED").isEmpty();
    }

    @Test
    void theClientEventsAreCataloguedUnderTheCodesTheOperationsEmit() {
        Set<String> catalogued = PlatformEventTypes.all().stream()
                .map(PlatformEventTypes.Definition::code).collect(Collectors.toSet());
        assertThat(catalogued).contains(
                "platform:admin:client:created", "platform:admin:client:updated", "platform:admin:client:activated",
                "platform:admin:client:suspended", "platform:admin:client:deactivated", "platform:admin:client:deleted",
                "platform:admin:client:note-added", "platform:iam:client:applications-updated");
        assertThat(catalogued).noneMatch(c -> c.startsWith("platform:iam:client:") && !c.endsWith(":applications-updated"));
    }
}
