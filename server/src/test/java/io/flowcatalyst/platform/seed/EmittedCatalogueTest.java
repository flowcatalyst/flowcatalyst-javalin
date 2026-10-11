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
/// subscription can pick it — except the known gaps below. The client events
/// were catalogued as `platform:iam:client:*` while the client operations emit
/// `platform:admin:client:*` (Go `25c3ed6`, Java F3 2026-10-11); this is the
/// check that would have caught it. Go's `TestEveryEmittedEventTypeIsCatalogued`
/// scans its `events.go` files the same way.
class EmittedCatalogueTest {

    /// `platform:{subdomain}:{aggregate}:{event}`.
    private static final Pattern EVENT_CODE = Pattern.compile("^platform:[a-z-]+:[a-z-]+:[a-z-]+$");

    /// A `static final String NAME = "…";` constant.
    private static final Pattern CONSTANT = Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"\\s*;");

    /// A type declaring that it is a `DomainEvent`.
    private static final Pattern DOMAIN_EVENT = Pattern.compile("implements\\s+(?:[\\w.]+\\s*,\\s*)*DomainEvent\\b");

    /// Event types the platform emits that the seeded catalogue does not list
    /// yet (found 2026-10-11 while aligning the client events; Go's list is
    /// `knownUncataloguedEventTypes`, which differs by the Java-only function,
    /// reset-approval, token-minted, requeue and audit-log redaction events; the
    /// last was found 2026-10-11 when the scan grew past `*Events.java`). A subscription cannot
    /// pick them from the catalogue. The list may only shrink: an entry that
    /// becomes catalogued, or stops being emitted, fails the test until removed.
    private static final Set<String> KNOWN_UNCATALOGUED = Set.of(
            "platform:admin:audit-log:redacted",
            "platform:admin:anchor-domain:created",
            "platform:admin:anchor-domain:deleted",
            "platform:admin:anchor-domain:updated",
            "platform:admin:auth-config:created",
            "platform:admin:auth-config:deleted",
            "platform:admin:auth-config:updated",
            "platform:admin:dispatch-pool:activated",
            "platform:admin:dispatch-pool:suspended",
            "platform:admin:dispatchjob:requeued",
            "platform:admin:dispatchjobs:requeued",
            "platform:admin:email-domain-mapping:created",
            "platform:admin:email-domain-mapping:deleted",
            "platform:admin:email-domain-mapping:provider-changed",
            "platform:admin:email-domain-mapping:updated",
            "platform:admin:identity-provider:created",
            "platform:admin:identity-provider:deleted",
            "platform:admin:identity-provider:updated",
            "platform:admin:idp-role-mapping:created",
            "platform:admin:idp-role-mapping:deleted",
            "platform:admin:oauth-client:activated",
            "platform:admin:oauth-client:created",
            "platform:admin:oauth-client:deactivated",
            "platform:admin:oauth-client:deleted",
            "platform:admin:oauth-client:previous-secret-revoked",
            "platform:admin:oauth-client:secret-rotated",
            "platform:admin:oauth-client:updated",
            "platform:admin:platform-config:property-set",
            "platform:admin:process:archived",
            "platform:admin:process:created",
            "platform:admin:process:deleted",
            "platform:admin:process:updated",
            "platform:admin:processes:synced",
            "platform:admin:role:created",
            "platform:admin:role:deleted",
            "platform:admin:role:permission-granted",
            "platform:admin:role:permission-revoked",
            "platform:admin:role:updated",
            "platform:admin:roles:synced",
            "platform:admin:scheduled-job:archived",
            "platform:admin:scheduled-job:created",
            "platform:admin:scheduled-job:deleted",
            "platform:admin:scheduled-job:fired-manually",
            "platform:admin:scheduled-job:paused",
            "platform:admin:scheduled-job:resumed",
            "platform:admin:scheduled-job:updated",
            "platform:admin:scheduledjobs:synced",
            "platform:developer:application-openapi:synced",
            "platform:function:alias:changed",
            "platform:function:alias:removed",
            "platform:function:config:updated",
            "platform:function:domain:claimed",
            "platform:function:domain:released",
            "platform:function:function:created",
            "platform:function:function:deleted",
            "platform:function:function:updated",
            "platform:function:policy:updated",
            "platform:function:secret:deleted",
            "platform:function:secret:set",
            "platform:function:version:published",
            "platform:function:version:ready",
            "platform:function:version:retired",
            "platform:iam:passkey:authenticated",
            "platform:iam:passkey:registered",
            "platform:iam:passkey:revoked",
            "platform:iam:reset-approval:decided",
            "platform:iam:reset-approval:queued",
            "platform:iam:serviceaccount:deactivated",
            "platform:iam:serviceaccount:token-minted",
            "platform:iam:user:developer-credential-revoked",
            "platform:iam:user:developer-credential-set",
            "platform:messaging:dispatch-job:cancelled",
            "platform:messaging:dispatch-job:completed",
            "platform:portal:app:created",
            "platform:portal:app:deleted",
            "platform:portal:app:updated",
            "platform:portal:identity:app-granted",
            "platform:portal:identity:app-revoked",
            "platform:portal:identity:deleted",
            "platform:portal:identity:ensured",
            "platform:portal:identity:status-set");

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
