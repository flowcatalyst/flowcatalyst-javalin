package io.flowcatalyst.platform.dispatch;

import io.flowcatalyst.platform.shared.dispatch.DispatchQueueName;
import io.flowcatalyst.platform.shared.dispatch.QueuePriority;
import io.flowcatalyst.server.Env;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [DispatchQueueSettings#resolve]: the startup refusal (spec §3 "Risks to
/// pin with tests") and the SQS/Postgres queue-URI derivation.
class DispatchQueueSettingsTest {

    /// Pins the startup refusal: SQS with a blank prefix must never reach
    /// the point of composing a queue literally named `FC-{env}-...`. The
    /// message names the offending setting so an operator does not have to
    /// guess.
    @Test
    void sqsWithABlankPrefixRefusesToStart() {
        Env env = Env.load(Map.of("FC_DISPATCH_QUEUE_TYPE", "SQS"));

        assertThatThrownBy(() -> DispatchQueueSettings.resolve(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_DISPATCH_QUEUE_PREFIX")
                .hasMessageContaining("FC_DISPATCH_QUEUE_TYPE=SQS");
    }

    @Test
    void sqsTypeIsCaseInsensitive() {
        Env env = Env.load(Map.of("FC_DISPATCH_QUEUE_TYPE", "sqs", "FC_DISPATCH_QUEUE_PREFIX", "FC-staging",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.eu-west-1.amazonaws.com/123456789012/fc-dispatch"));

        assertThat(DispatchQueueSettings.resolve(env).sqs()).isTrue();
    }

    /// The same class of failure the prefix check guards, and the reason it is
    /// a startup refusal rather than a fallback: `queueUriFor` interpolates the
    /// account and region straight into the URL, so a blank one composes
    /// `https://sqs..amazonaws.com//FC-staging-acme-DEFAULT.fifo`.
    /// `QueueFactory.resolveScheme` still classifies that as SQS — the host
    /// starts `sqs.` and contains `.amazonaws.` — so the router would build a
    /// consumer against a nonsense endpoint and fail at poll time, repeatedly,
    /// instead of the deployment failing once at boot.
    @Test
    void sqsWithNoUsableQueueUrlRefusesToStartRatherThanComposeANonsenseEndpoint() {
        Env env = Env.load(Map.of("FC_DISPATCH_QUEUE_TYPE", "SQS", "FC_DISPATCH_QUEUE_PREFIX", "FC-staging"));

        assertThatThrownBy(() -> DispatchQueueSettings.resolve(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account id")
                .hasMessageContaining("region");
    }

    /// An explicit region is not enough on its own — the account id has only
    /// one source, so a missing `FC_DISPATCH_QUEUE_URL` is still fatal.
    @Test
    void anExplicitRegionDoesNotExcuseAMissingAccountId() {
        Env env = Env.load(Map.of("FC_DISPATCH_QUEUE_TYPE", "SQS", "FC_DISPATCH_QUEUE_PREFIX", "FC-staging",
                "FC_DISPATCH_QUEUE_REGION", "eu-west-1"));

        assertThatThrownBy(() -> DispatchQueueSettings.resolve(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account id");
    }

    @Test
    void blankOrUnsetTypeResolvesToPostgresAndNeedsNoPrefix() {
        Env env = Env.load(Map.of("FC_DATABASE_URL", "postgresql://u@h:5432/db"));

        DispatchQueueSettings settings = DispatchQueueSettings.resolve(env);

        assertThat(settings.sqs()).isFalse();
        assertThat(settings.databaseUrl()).isEqualTo("postgresql://u@h:5432/db");
    }

    @Test
    void postgresQueueUriIsTheDatabaseUrlRegardlessOfTheComposedName() {
        Env env = Env.load(Map.of("FC_DATABASE_URL", "postgresql://u@h:5432/db"));
        DispatchQueueSettings settings = DispatchQueueSettings.resolve(env);
        var name = DispatchQueueName.compose("FC-staging", "acme", QueuePriority.DEFAULT, false);

        assertThat(settings.queueUriFor(name)).isEqualTo("postgresql://u@h:5432/db");
    }

    /// The account id and region both come from `FC_DISPATCH_QUEUE_URL` when
    /// `FC_DISPATCH_QUEUE_REGION` is unset — [SqsQueue#regionFromUrl] reused
    /// rather than a second parser.
    @Test
    void sqsAccountAndRegionAreParsedFromTheQueueUrlWhenRegionIsUnset() {
        Env env = Env.load(Map.of(
                "FC_DISPATCH_QUEUE_TYPE", "SQS",
                "FC_DISPATCH_QUEUE_PREFIX", "FC-staging",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123456789012/fc-dispatch"));

        DispatchQueueSettings settings = DispatchQueueSettings.resolve(env);

        assertThat(settings.sqsAccountId()).isEqualTo("123456789012");
        assertThat(settings.sqsRegion()).isEqualTo("us-east-1");
    }

    /// `FC_DISPATCH_QUEUE_REGION`, when set, overrides whatever the URL's
    /// own host would have parsed to.
    @Test
    void explicitRegionOverridesTheUrlDerivedOne() {
        Env env = Env.load(Map.of(
                "FC_DISPATCH_QUEUE_TYPE", "SQS",
                "FC_DISPATCH_QUEUE_PREFIX", "FC-staging",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123456789012/fc-dispatch",
                "FC_DISPATCH_QUEUE_REGION", "eu-west-1"));

        assertThat(DispatchQueueSettings.resolve(env).sqsRegion()).isEqualTo("eu-west-1");
    }

    @Test
    void sqsQueueUriIsComposedFromAccountAndRegion() {
        Env env = Env.load(Map.of(
                "FC_DISPATCH_QUEUE_TYPE", "SQS",
                "FC_DISPATCH_QUEUE_PREFIX", "FC-staging",
                "FC_DISPATCH_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123456789012/fc-dispatch"));
        DispatchQueueSettings settings = DispatchQueueSettings.resolve(env);
        var name = DispatchQueueName.compose("FC-staging", "acme", QueuePriority.DEFAULT, true);

        assertThat(settings.queueUriFor(name))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/FC-staging-acme-DEFAULT.fifo");
    }
}
