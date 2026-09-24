package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.FcdevFunctionsFixture;
import io.flowcatalyst.fcdev.StartCommand;
import io.flowcatalyst.fnhost.FnHost;
import io.flowcatalyst.fnhost.load.FixtureJars;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.adminPost;
import static io.flowcatalyst.fcdev.FcdevFunctionsFixture.obj;
import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end `fcdev fn` CLI tests against the REAL dev stack
/// ([FcdevFunctionsFixture], the same harness `StartFunctionsIntegrationTest`
/// (E1) boots) — not [FakePlatform]. Drives the CLI exactly as a developer
/// would: no `--client-id`/`--client-secret`/`--platform-url`/`--host-url`
/// flags at all, only `XDG_DATA_HOME` pointed at the fixture's state dir so
/// [FnCredentials] finds the REAL `fn-cli.json` the boot wrote (tier 3 of
/// its own resolution order).
///
/// Covers (spec §2, §4): publish → promote --wait → invoke (200); deploy
/// twice with the identical jar (second call promotes the existing version,
/// no error); config set/get and secret set/list/delete round trips, the
/// secret value asserted ABSENT from every captured writer; `invoke
/// --webhook` with the right secret (200) and without it (401) against a
/// real `webhook` endpoint on the real function host.
@SuppressWarnings("deprecation")
class FnCliEndToEndTest {

    private final FcdevFunctionsFixture fixture = new FcdevFunctionsFixture();
    /// Keeps the host's reconcile loop running eagerly during the test so a
    /// `fn promote --wait`/`fn deploy --wait` never has to wait out the
    /// real background interval — a test concern, not a production one.
    private Thread reconcilePump;
    private final AtomicBoolean stopPump = new AtomicBoolean();

    @AfterEach
    void shutdown() throws Exception {
        stopPump.set(true);
        if (reconcilePump != null) reconcilePump.join(5000);
        fixture.close();
    }

    private Map<String, String> cliEnv() {
        // The ONLY thing a real `fcdev fn` invocation needs pointed anywhere
        // non-default: XDG_DATA_HOME, so DevPaths resolves to the SAME root
        // the fixture's boot used, and therefore finds the REAL fn-cli.json.
        return Map.of("XDG_DATA_HOME", fixture.root.toString());
    }

    @Test
    void publishPromoteInvokeDeployConfigSecretAndWebhookAllRoundTrip(@TempDir Path work) throws Exception {
        StartCommand.Started started = fixture.boot(Map.of());
        FnHost host = fixture.inProcessHost();
        reconcilePump = Thread.ofPlatform().name("reconcile-pump").start(() -> {
            while (!stopPump.get()) {
                host.triggerReconcile();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        // ── application infrastructure: the fn-cli client cannot create an
        //    application (no ADMIN_APPLICATION_CREATE) — provisioned by an
        //    ANCHOR fixture, same discipline as StartFunctionsIntegrationTest's
        //    own ANCHOR use; never a load-bearing assertion by itself. The
        //    service account's signing secret IS load-bearing below (webhook). ──
        String run = Long.toUnsignedString(System.nanoTime(), 36);
        String appCode = "fncliet" + run;
        String[] anchor = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
        var createdApp = adminPost(started.apiPort(), anchor, "/api/applications",
                obj("code", appCode, "name", appCode, "type", "APPLICATION"), 201);
        String appId = createdApp.path("id").asText();
        // `/provision-service-account` never returns the signing secret (see the
        // final report's `--webhook` finding); `POST /api/service-accounts`
        // does, ONE TIME, on creation — the app's function endpoints resolve
        // THIS service account's secret (`DesiredState#signingSecretFor` keys
        // on `f.applicationId()`).
        var serviceAccount = adminPost(started.apiPort(), anchor, "/api/service-accounts",
                obj("code", "sa-" + run, "name", "sa-" + run, "applicationId", appId), 201);
        String signingSecret = serviceAccount.path("webhook").path("signingSecret").asText();
        assertThat(signingSecret).as("service-account creation must return a real signing secret").isNotBlank();

        String address = appCode + ".default.sample";
        Path jar = work.resolve("e2e-fn.jar");
        FixtureJars.builder().source("fixture.e2e.SampleFn", """
                package fixture.e2e;
                import io.flowcatalyst.function.*;
                public final class SampleFn implements Function {
                    public Result handle(Request in, FunctionContext ctx) throws Exception {
                        return Result.json(200, "{\\"path\\":\\"" + in.path() + "\\"}");
                    }
                }
                """).build(jar);
        Path manifest = work.resolve("manifest.json");
        Files.writeString(manifest, """
                {"runtime":"jvm","entrypoint":"fixture.e2e.SampleFn","pool":"default","warm":false,
                 "endpoints":[{"path":"/hello","auth":"none"},{"path":"/hook","auth":"webhook"}]}
                """);

        // ── publish → promote --wait → invoke (200) ──
        var publish = FnCliTestSupport.run(cliEnv(), "fn", "publish", jar.toString(), address,
                "--manifest", manifest.toString());
        assertThat(publish.exit()).as(publish.out() + publish.err()).isZero();

        var promote = FnCliTestSupport.run(cliEnv(), "fn", "promote", address, "--version", "1", "--wait", "20s");
        assertThat(promote.exit()).as(promote.out() + promote.err()).isZero();

        // The version reaching READY (what `promote --wait` waits for) is not
        // the same moment the host has actually loaded the newly-live alias
        // for serving — that needs one more reconcile tick, which the pump
        // above supplies, but not necessarily synchronously with promote
        // returning; so the very first invoke is retried briefly.
        FnCliTestSupport.Run invoke = awaitInvokeOk(address, "/hello");
        assertThat(invoke.out()).contains("HTTP 200").contains("/hello");

        // ── deploy twice with the SAME jar: second call promotes the
        //    existing version, no error (VERSION_DIGEST_EXISTS recovery). ──
        var deploy1 = FnCliTestSupport.run(cliEnv(), "fn", "deploy", jar.toString(), address,
                "--manifest", manifest.toString(), "--wait", "20s");
        assertThat(deploy1.exit()).as(deploy1.out() + deploy1.err()).isZero();
        var deploy2 = FnCliTestSupport.run(cliEnv(), "fn", "deploy", jar.toString(), address,
                "--manifest", manifest.toString(), "--wait", "20s");
        assertThat(deploy2.exit()).as("re-deploying the identical jar must not fail: " + deploy2.err()).isZero();
        assertThat(deploy2.err()).isEmpty();

        // ── config set/get round trip ──
        var configSet = FnCliTestSupport.run(cliEnv(), "fn", "config", "set", address, "GREETING=hello");
        assertThat(configSet.exit()).as(configSet.err()).isZero();
        var configGet = FnCliTestSupport.run(cliEnv(), "fn", "config", "get", address);
        assertThat(configGet.exit()).as(configGet.err()).isZero();
        assertThat(configGet.out()).contains("GREETING=hello");

        // ── secret set/list/delete round trip — the value absent from
        //    EVERY captured writer, in every step. ──
        String secretValue = "s3cr3t-e2e-" + run;
        var secretSet = FnCliTestSupport.runWithStdin(cliEnv(), secretValue + "\n",
                "fn", "secret", "set", address, "API_KEY");
        assertThat(secretSet.exit()).as(secretSet.err()).isZero();
        assertThat(secretSet.out()).doesNotContain(secretValue);
        assertThat(secretSet.err()).doesNotContain(secretValue);

        var secretList = FnCliTestSupport.run(cliEnv(), "fn", "secret", "list", address);
        assertThat(secretList.exit()).as(secretList.err()).isZero();
        assertThat(secretList.out()).contains("API_KEY");
        assertThat(secretList.out()).doesNotContain(secretValue);
        assertThat(secretList.err()).doesNotContain(secretValue);

        var secretDelete = FnCliTestSupport.run(cliEnv(), "fn", "secret", "delete", address, "API_KEY");
        assertThat(secretDelete.exit()).as(secretDelete.err()).isZero();
        assertThat(secretDelete.out()).doesNotContain(secretValue);
        assertThat(secretDelete.err()).doesNotContain(secretValue);

        var secretListAfterDelete = FnCliTestSupport.run(cliEnv(), "fn", "secret", "list", address);
        assertThat(secretListAfterDelete.out()).doesNotContain("API_KEY");

        // ── invoke --webhook: right secret 200, no --webhook 401 (webhook
        //    endpoints accept POST only — function-invocation.md §3) ──
        var webhookOk = awaitInvokeOk(address, "/hook", "--method", "POST", "--webhook", "--signing-secret", signingSecret);
        assertThat(webhookOk.out()).contains("HTTP 200");

        var webhookMissing = FnCliTestSupport.run(cliEnv(), "fn", "invoke", address, "--path", "/hook", "--method", "POST");
        assertThat(webhookMissing.exit()).isEqualTo(1);
        assertThat(webhookMissing.out()).contains("HTTP 401");

        // ── a versioned call carries the CLI's own bearer token automatically: the same
        // `webhook` endpoint that just answered 401 unsigned is reachable as `<address>:1`
        // with NO signature — only because the token (function-publisher holds
        // `version:invoke`) is attached. Mutant: never add the Authorization header.
        var versioned = FnCliTestSupport.run(cliEnv(), "fn", "invoke", address + ":1", "--path", "/hook",
                "--method", "POST");
        assertThat(versioned.exit()).as(versioned.out() + versioned.err()).isZero();
        assertThat(versioned.out()).contains("HTTP 200");
    }

    /// spec `function-manifest-authoring.md` M2.3: `fn validate`'s three exit
    /// codes against a real platform (the manifest/check route it calls) — 0
    /// for a valid manifest, 1 for an invalid one, and 0 (not 1) again for a
    /// valid manifest a declared config key is missing for, with a warning
    /// line naming it. No jar, no promote: `fn validate` never publishes.
    @Test
    void validateExitCodesMatchValidityNotSettingsMissing() throws Exception {
        StartCommand.Started started = fixture.boot(Map.of());

        String run = Long.toUnsignedString(System.nanoTime(), 36);
        String appCode = "fncliv" + run;
        String[] anchor = {
                Authenticator.TEST_PRINCIPAL, EntityType.PRINCIPAL.generate(),
                Authenticator.TEST_SCOPE, "ANCHOR",
                Authenticator.TEST_PERMISSIONS, "platform:*:*:*"};
        adminPost(started.apiPort(), anchor, "/api/applications",
                obj("code", appCode, "name", appCode, "type", "APPLICATION"), 201);
        adminPost(started.apiPort(), anchor, "/api/functions",
                obj("applicationCode", appCode, "serviceName", "default", "name", "sample", "runtime", "jvm"), 201);
        String address = appCode + ".default.sample";

        Path validManifest = Files.createTempFile("fn-validate-valid-", ".json");
        Files.writeString(validManifest, """
                {"runtime":"jvm","entrypoint":"fixture.e2e.SampleFn","pool":"default","warm":false,
                 "endpoints":[{"path":"/hello","auth":"none"}]}
                """);
        var valid = FnCliTestSupport.run(cliEnv(), "fn", "validate", address, "--manifest", validManifest.toString());
        assertThat(valid.exit()).as("mutant: a valid manifest exits non-zero: " + valid.out() + valid.err()).isZero();
        assertThat(valid.out()).as("nothing has ever been promoted: the pool would be created")
                .contains("pool (create)");

        Path invalidManifest = Files.createTempFile("fn-validate-invalid-", ".json");
        Files.writeString(invalidManifest, """
                {"runtime":"cobol","entrypoint":"fixture.e2e.SampleFn"}
                """);
        var invalid = FnCliTestSupport.run(cliEnv(), "fn", "validate", address, "--manifest", invalidManifest.toString());
        assertThat(invalid.exit()).as("mutant: an invalid manifest exits zero").isEqualTo(1);
        assertThat(invalid.out()).contains("RUNTIME_INVALID");

        Path missingSettingManifest = Files.createTempFile("fn-validate-missing-setting-", ".json");
        Files.writeString(missingSettingManifest, """
                {"runtime":"jvm","entrypoint":"fixture.e2e.SampleFn","pool":"default","warm":false,
                 "endpoints":[{"path":"/hello","auth":"none"}],"config":["API_KEY"]}
                """);
        var missingSetting = FnCliTestSupport.run(cliEnv(), "fn", "validate", address,
                "--manifest", missingSettingManifest.toString());
        assertThat(missingSetting.exit())
                .as("mutant: settingsMissing alone fails the command: " + missingSetting.out()).isZero();
        assertThat(missingSetting.out()).as("mutant: the missing key is not surfaced").contains("settings missing")
                .contains("API_KEY");
    }

    /// Retries `fn invoke` briefly: the reconcile pump runs concurrently with
    /// the CLI's own commands, so a version reaching `READY` (what `promote
    /// --wait` waits for) can win a race against the host actually finishing
    /// the NEXT reconcile that loads the newly-live alias for serving. Bounded
    /// (2 s of 50 ms polls) — never an unbounded wait.
    private FnCliTestSupport.Run awaitInvokeOk(String address, String path, String... extraArgs) throws InterruptedException {
        var args = new java.util.ArrayList<String>(java.util.List.of("fn", "invoke", address, "--path", path));
        args.addAll(java.util.List.of(extraArgs));
        FnCliTestSupport.Run last = null;
        for (int i = 0; i < 40; i++) {
            last = FnCliTestSupport.run(cliEnv(), args.toArray(new String[0]));
            if (last.exit() == 0) {
                return last;
            }
            Thread.sleep(50);
        }
        assertThat(last).as("invoke never reached HTTP 200").isNotNull();
        assertThat(last.exit()).as(last.out() + last.err()).isZero();
        return last;
    }
}
