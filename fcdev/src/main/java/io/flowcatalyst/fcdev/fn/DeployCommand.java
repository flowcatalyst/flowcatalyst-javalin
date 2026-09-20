package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.DurationConverter;
import io.flowcatalyst.platform.function.api.FunctionApi;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/// `fn deploy <jar> --manifest <file> [--wait 60s]` (spec §2): publish, then
/// promote — what `fn watch` runs on every change. If publish fails with
/// `VERSION_DIGEST_EXISTS`, `deploy` (unlike bare `fn publish`) promotes the
/// EXISTING version the error names (`details.version` — added server-side
/// so this never parses the message's prose), rather than failing: the same
/// jar deployed twice is a no-op publish followed by an ordinary promote.
@Command(name = "deploy", description = "Publish a new version and promote it to live (publish + promote)", sortOptions = false)
public final class DeployCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", paramLabel = "<jar>", description = "the function's jar file")
    String jar;

    @Parameters(index = "1", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
    String address;

    @Option(names = "--manifest", required = true, paramLabel = "<file>", description = "the manifest JSON file")
    String manifestFile;

    @Option(names = "--artifact-ref", paramLabel = "<ref>", description = "oci://… or s3://… (omit for local mode)")
    String artifactRef;

    @Option(names = "--bundle", paramLabel = "<file>", description = "sigstore bundle file (remote mode only)")
    String bundleFile;

    @Option(names = "--client", paramLabel = "<id>", description = "owning client id, when creating a client-owned function")
    String client;

    @Option(names = "--no-create", description = "fail (exit 1) instead of creating the function when its address is unknown")
    boolean noCreate;

    @Option(names = "--wait", paramLabel = "<duration>", defaultValue = "60s", converter = DurationConverter.class,
            description = "how long to wait for READY before giving up (0 = promote immediately; default: ${DEFAULT-VALUE})")
    Duration wait;

    @Mixin
    AddressOptions addressOpts;

    @Spec
    CommandSpec spec;

    LongSupplier clockMillis = System::currentTimeMillis;
    LongConsumer sleepMillis = ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    @Override
    public Integer call() {
        FnCommand root = FnCommand.of(spec);
        return FnCommand.runSafely(spec, () -> {
            String addr = addressOpts.resolve(address);
            var opts = new Publisher.Options(jar, manifestFile, artifactRef, bundleFile, client, noCreate);

            int version;
            try {
                version = Publisher.publish(spec, root, addr, opts).version();
            } catch (FnClientException e) {
                Object existing = "VERSION_DIGEST_EXISTS".equals(e.code()) ? e.details().get("version") : null;
                if (!(existing instanceof Number n)) {
                    throw e;
                }
                version = n.intValue();
            }

            FnClient platform = root.client();
            FunctionApi.PromoteResponse promoted;
            try {
                promoted = Promoter.promote(spec, root, platform, addr, version, wait, clockMillis, sleepMillis);
            } catch (FnClientException e) {
                // The recovered version (VERSION_DIGEST_EXISTS above, or a fresh
                // publish that happens to already be live) may ALREADY be the
                // live alias — deploying the identical jar a second time in a row
                // is exactly this: promote #1 already made it live, so promote #2
                // has nothing to do. The platform's ALIAS_UNCHANGED conflict is
                // right for an explicit `fn promote` (the user asked for a change
                // that didn't happen); `fn deploy`'s automatic recovery wants
                // "the desired state is already reached", not an error — spec §2's
                // "second call promotes the existing version, no error" is
                // unreachable otherwise once the first deploy already promoted it.
                if (!"ALIAS_UNCHANGED".equals(e.code())) {
                    throw e;
                }
                printAlreadyLive(root, addr, version);
                return 0;
            }
            if (promoted == null) {
                return 1;
            }
            print(root, addr, promoted);
            return 0;
        });
    }

    private void print(FnCommand root, String address, FunctionApi.PromoteResponse promoted) {
        var out = spec.commandLine().getOut();
        switch (root.output()) {
            case TEXT -> out.printf("%s: version %d deployed and live%n", address, promoted.version());
            case JSON -> out.println(Json.write(promoted));
        }
    }

    private void printAlreadyLive(FnCommand root, String address, int version) {
        var out = spec.commandLine().getOut();
        switch (root.output()) {
            case TEXT -> out.printf("%s: version %d deployed and live%n", address, version);
            case JSON -> out.println(Json.write(new FunctionApi.PromoteResponse("live", version, null, null)));
        }
    }
}
