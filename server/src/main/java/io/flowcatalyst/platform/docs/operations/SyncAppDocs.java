package io.flowcatalyst.platform.docs.operations;

import io.flowcatalyst.platform.docs.AppDoc;
import io.flowcatalyst.platform.docs.AppDocRepository;
import io.flowcatalyst.platform.docs.AppDocRepository.ReplaceResult;
import io.flowcatalyst.platform.docs.AppDocSlug;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.TxOperation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Declaratively replaces an application's documentation pages (spec §5):
/// the payload IS the set — kept slugs update in place, unlisted slugs are
/// deleted. Validation is all-or-nothing and stops at the first failing
/// page, in payload order. Authorization is resource-level: the caller must
/// be able to act for the application (the handler's coarse "may sync docs"
/// gate and the `{appCode}` resolution are separate).
///
/// A `TxOperation`, not an `Operation`: the sync writes no domain event and
/// no audit row (spec §5, open question 5), so there is no `Plan` to apply —
/// the repository's set-level replace runs inside the operation's one
/// transaction.
public final class SyncAppDocs {

    /// An application may sync at most this many pages (spec §5).
    public static final int MAX_DOCS_PER_APP = 100;
    /// One page's content, in UTF-8 bytes (spec §5).
    public static final int MAX_DOC_BYTES = 512 * 1024;
    /// The whole payload's content, in UTF-8 bytes (spec §5).
    public static final int MAX_PAYLOAD_BYTES = 4 << 20;

    private SyncAppDocs() {
    }

    public static TxOperation<SyncAppDocsCommand, ReplaceResult> of(AppDocRepository repo) {
        return TxOperation.<SyncAppDocsCommand, ReplaceResult>named("SyncAppDocs")
                .validate(SyncAppDocs::validate)
                .authorize(cmd -> Checks.checkApplicationAccess(Auth.current(), cmd.applicationId(), cmd.applicationCode()))
                .execute((scoped, cmd, _) -> repo.replaceForApplication(scoped.dbTx(), cmd.applicationId(), inputs(cmd), Instant.now()));
    }

    /// The spec §5 validation table, in order; the first failing page aborts.
    /// Validate runs it for its errors; execute runs it again for its result —
    /// the envelope's phases carry nothing between them, and the parse is cheap.
    private static void validate(SyncAppDocsCommand cmd) {
        inputs(cmd);
    }

    /// Wire pages → validated store inputs (parsed slug, resolved title), or
    /// the first failing page's error, in payload order.
    private static List<AppDoc.Input> inputs(SyncAppDocsCommand cmd) {
        UseCaseException.requireNonBlank(cmd.applicationCode(), "APPLICATION_CODE_REQUIRED", "Application code is required");
        if (cmd.docs().size() > MAX_DOCS_PER_APP) {
            throw UseCaseException.validation("TOO_MANY_DOCS",
                    "an application may sync at most " + MAX_DOCS_PER_APP + " documentation pages");
        }
        Set<String> seen = new HashSet<>();
        long total = 0;
        var out = new ArrayList<AppDoc.Input>(cmd.docs().size());
        for (SyncAppDocInput d : cmd.docs()) {
            AppDocSlug slug = AppDocSlug.parse(d.slug());
            if (!seen.add(slug.value())) {
                throw UseCaseException.validation("SLUG_DUPLICATE", "doc slug " + slug.value() + " appears more than once");
            }
            int bytes = d.content().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_DOC_BYTES) {
                throw UseCaseException.validation("DOC_TOO_LARGE", "doc " + slug.value() + " exceeds 512KB");
            }
            total += bytes;
            if (total > MAX_PAYLOAD_BYTES) {
                throw UseCaseException.validation("PAYLOAD_TOO_LARGE", "documentation sync exceeds 4MB total");
            }
            out.add(new AppDoc.Input(slug, AppDoc.titleFor(d.title(), d.content(), slug), d.content()));
        }
        return List.copyOf(out);
    }
}
