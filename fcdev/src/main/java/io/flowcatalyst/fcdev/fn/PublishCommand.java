package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/// `fn publish <jar> --manifest <file>` (spec §2 row `fn publish`, §4 E4).
/// sha256 of the jar streamed; local mode (no `--artifact-ref`) copies it
/// into the CLI's own artifact store; remote mode requires `--artifact-ref`
/// (`oci://`/`s3://`) and sends `--bundle`'s content as `signatureBundle`.
/// Creates the function when the platform answers 404 for its address
/// (`--no-create` to forbid — exits 1 with the platform's 404 message). The
/// actual work is [Publisher#publish] — shared with `fn deploy`.
@Command(name = "publish", description = "Publish a new version of a function", sortOptions = false)
public final class PublishCommand implements Callable<Integer> {

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

    @Mixin
    AddressOptions addressOpts;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        FnCommand root = FnCommand.of(spec);
        return FnCommand.runSafely(spec, () -> {
            String resolvedAddress = addressOpts.resolve(address);
            var opts = new Publisher.Options(jar, manifestFile, artifactRef, bundleFile, client, noCreate);
            Publisher.Outcome outcome = Publisher.publish(spec, root, resolvedAddress, opts);
            print(root, outcome);
            return 0;
        });
    }

    private void print(FnCommand root, Publisher.Outcome outcome) {
        var out = spec.commandLine().getOut();
        switch (root.output()) {
            case TEXT -> out.printf("published %s version %d (%s)%n", outcome.address(), outcome.version(), outcome.digest());
            case JSON -> out.println(Json.write(outcome));
        }
    }
}
