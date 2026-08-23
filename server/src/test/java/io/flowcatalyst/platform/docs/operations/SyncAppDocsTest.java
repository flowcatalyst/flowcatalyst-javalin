package io.flowcatalyst.platform.docs.operations;

import io.flowcatalyst.platform.docs.AppDoc;
import io.flowcatalyst.platform.docs.AppDocRepository.ReplaceResult;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static io.flowcatalyst.platform.docs.AppDocFixture.DOCS;
import static io.flowcatalyst.platform.docs.AppDocFixture.UOW;
import static io.flowcatalyst.platform.docs.AppDocFixture.application;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// The docs sync through the envelope (spec §5): the validation table in
/// order, application-scoped authorization, title resolution and the
/// declarative round trip.
class SyncAppDocsTest {

    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    private static ReplaceResult runAs(AuthContext ac, SyncAppDocsCommand cmd) {
        return Auth.runAs(ac, () -> SyncAppDocs.of(DOCS).run(UOW, cmd, EC));
    }

    private static SyncAppDocInput doc(String slug, String title, String content) {
        return new SyncAppDocInput(slug, title, content);
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    // ── Round trip ────────────────────────────────────────────────────────

    @Test
    void syncCreatesThenReplacesTheSetInPayloadOrderWithDerivedTitles() {
        var app = application("sync", "Doc Sync App");

        var first = runAs(ANCHOR, new SyncAppDocsCommand(app.id(), app.code(), List.of(
                doc("getting-started", "Getting Started", "# Getting Started\nHello."),
                doc("webhooks", null, "# Webhook Reference\nDetails."))));
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.slugs()).containsExactly("getting-started", "webhooks");

        var second = runAs(ANCHOR, new SyncAppDocsCommand(app.id(), app.code(), List.of(
                doc("  webhooks ", null, "# Webhook Reference v2\nMore."),
                doc("faq", "  ", "Q&A only, no heading"))));
        assertThat(second.created()).isEqualTo(1);
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.deleted()).isEqualTo(1);

        assertThat(DOCS.listByApplication(app.id()))
                .extracting(AppDoc.Summary::slug, AppDoc.Summary::title)
                .containsExactly(
                        tuple("webhooks", "Webhook Reference v2"),
                        tuple("faq", "faq"));
        assertThat(DOCS.findByApplicationAndSlug(app.id(), "faq")).map(AppDoc::content).contains("Q&A only, no heading");
    }

    // ── Validation table (spec §5) ────────────────────────────────────────

    static Stream<Arguments> invalidCommands() {
        String app = EntityType.APPLICATION.generate();
        return Stream.of(
                arguments("blank application code", new SyncAppDocsCommand(app, " ", List.of()), "APPLICATION_CODE_REQUIRED"),
                arguments("more than 100 pages", new SyncAppDocsCommand(app, "x", Collections.nCopies(101, doc("a", null, "x"))), "TOO_MANY_DOCS"),
                arguments("malformed slug", new SyncAppDocsCommand(app, "x", List.of(doc("Not A Slug", null, "x"))), "SLUG_INVALID"),
                arguments("duplicate slug after trim", new SyncAppDocsCommand(app, "x", List.of(doc("a", null, "x"), doc(" a ", null, "y"))), "SLUG_DUPLICATE"),
                arguments("one page over 512 KiB", new SyncAppDocsCommand(app, "x", List.of(doc("big", null, "x".repeat(SyncAppDocs.MAX_DOC_BYTES + 1)))), "DOC_TOO_LARGE"),
                arguments("multi-byte page counted in bytes", new SyncAppDocsCommand(app, "x", List.of(doc("big", null, "é".repeat(SyncAppDocs.MAX_DOC_BYTES / 2 + 1)))), "DOC_TOO_LARGE"),
                arguments("payload over 4 MiB", new SyncAppDocsCommand(app, "x", Stream.iterate(0, i -> i + 1).limit(9)
                        .map(i -> doc("p" + i, null, "x".repeat(SyncAppDocs.MAX_DOC_BYTES))).toList()), "PAYLOAD_TOO_LARGE"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("invalidCommands")
    void validationRefusesBeforeTouchingTheStore(String rule, SyncAppDocsCommand cmd, String code) {
        assertUseCaseError(() -> runAs(ANCHOR, cmd), UseCaseError.Validation.class, code);
        assertThat(DOCS.listByApplication(cmd.applicationId())).as(rule + ": nothing stored").isEmpty();
    }

    @Test
    void earlierPagesDoNotMaskALaterFailure() {
        var app = application("partial", "Partial App");
        assertUseCaseError(() -> runAs(ANCHOR, new SyncAppDocsCommand(app.id(), app.code(), List.of(
                doc("fine", null, "ok"), doc("BAD", null, "x")))), UseCaseError.Validation.class, "SLUG_INVALID");
        assertThat(DOCS.listByApplication(app.id())).as("all-or-nothing").isEmpty();
    }

    // ── Authorization (spec §5) ───────────────────────────────────────────

    @Test
    void aPrincipalWithoutAccessToTheApplicationIsForbidden() {
        var app = application("auth", "Auth App");
        var other = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "svc@x.io",
                List.of(), List.of(), List.of(EntityType.APPLICATION.generate()), false, List.of());
        assertUseCaseError(() -> runAs(other, new SyncAppDocsCommand(app.id(), app.code(), List.of(doc("g", null, "x")))),
                UseCaseError.Authorization.class, "FORBIDDEN");

        var granted = new AuthContext(EntityType.PRINCIPAL.generate(), Scope.CLIENT, "svc@x.io",
                List.of(), List.of(), List.of(app.id()), false, List.of());
        assertThat(runAs(granted, new SyncAppDocsCommand(app.id(), app.code(), List.of(doc("g", null, "x")))).created()).isEqualTo(1);
    }
}
