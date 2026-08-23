package io.flowcatalyst.platform.docs;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.flowcatalyst.platform.docs.AppDocFixture.DOCS;
import static io.flowcatalyst.platform.docs.AppDocFixture.application;
import static io.flowcatalyst.platform.docs.AppDocFixture.input;
import static io.flowcatalyst.platform.docs.AppDocFixture.replace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/// `app_docs` round trips (spec §3): the declarative replace's three
/// outcomes, sync ordering, and the orphan-tolerant spine.
class AppDocRepositoryTest {

    @Test
    void replaceCreatesThenUpdatesInPlaceAndDeletesUnlisted() {
        var app = application("repl", "Replace App");

        var first = replace(app.id(), List.of(
                input("getting-started", "Getting Started", "# Getting Started\nHello."),
                input("webhooks", "Webhook Reference", "# Webhook Reference\nDetails.")));
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.updated()).isZero();
        assertThat(first.deleted()).isZero();
        assertThat(first.slugs()).containsExactly("getting-started", "webhooks");

        AppDoc webhooksBefore = DOCS.findByApplicationAndSlug(app.id(), "webhooks").orElseThrow();
        assertThat(webhooksBefore.id()).startsWith(EntityType.APP_DOC.prefix() + "_");
        assertThat(webhooksBefore.position()).isEqualTo(1);

        var second = replace(app.id(), List.of(
                input("webhooks", "Webhook Reference v2", "# Webhook Reference v2\nMore."),
                input("faq", "FAQ", "# FAQ\nQ&A.")));
        assertThat(second.created()).isEqualTo(1);
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.deleted()).isEqualTo(1);
        assertThat(second.slugs()).containsExactly("webhooks", "faq");

        AppDoc webhooksAfter = DOCS.findByApplicationAndSlug(app.id(), "webhooks").orElseThrow();
        assertThat(webhooksAfter.id()).as("a kept slug keeps its id").isEqualTo(webhooksBefore.id());
        assertThat(webhooksAfter.createdAt()).as("and its created_at").isEqualTo(webhooksBefore.createdAt());
        assertThat(webhooksAfter.updatedAt()).isAfterOrEqualTo(webhooksBefore.updatedAt());
        assertThat(webhooksAfter.title()).isEqualTo("Webhook Reference v2");
        assertThat(webhooksAfter.content()).contains("More.");
        assertThat(webhooksAfter.position()).isZero();

        assertThat(DOCS.findByApplicationAndSlug(app.id(), "getting-started")).as("unlisted slug deleted").isEmpty();
        assertThat(DOCS.listByApplication(app.id()))
                .extracting(AppDoc.Summary::slug, AppDoc.Summary::title)
                .containsExactly(
                        tuple("webhooks", "Webhook Reference v2"),
                        tuple("faq", "FAQ"));
    }

    @Test
    void anEmptyPayloadDeletesEveryPage() {
        var app = application("empty", "Empty App");
        replace(app.id(), List.of(input("a", "A", "a"), input("b", "B", "b")));
        var res = replace(app.id(), List.of());
        assertThat(res.deleted()).isEqualTo(2);
        assertThat(res.slugs()).isEmpty();
        assertThat(DOCS.listByApplication(app.id())).isEmpty();
    }

    @Test
    void listIsInPayloadOrderNotAlphabetical() {
        var app = application("order", "Order App");
        replace(app.id(), List.of(input("zeta", "Z", "z"), input("alpha", "A", "a"), input("mid", "M", "m")));
        assertThat(DOCS.listByApplication(app.id())).extracting(AppDoc.Summary::slug).containsExactly("zeta", "alpha", "mid");
    }

    @Test
    void spineListsEveryApplicationWithPagesIncludingOrphans() {
        var app = application("spine", "Spine App");
        String orphan = EntityType.APPLICATION.generate(); // no tnt_applications row
        replace(app.id(), List.of(input("one", "One", "1")));
        replace(orphan, List.of(input("one", "One", "1")));
        assertThat(DOCS.applicationIdsWithDocs()).contains(app.id(), orphan);
    }

    @Test
    void unknownPageIsEmpty() {
        var app = application("miss", "Miss App");
        assertThat(DOCS.findByApplicationAndSlug(app.id(), "nope")).isEmpty();
    }
}
