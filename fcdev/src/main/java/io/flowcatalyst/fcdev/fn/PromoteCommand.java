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

/// `fn promote <address> --version <n> [--wait 60s]` (spec §2, §4 E5): waits
/// (polling `GET …/status` once a second) for that version to become
/// `READY`, then `PUT …/aliases/live`; `--wait 0` skips the wait and
/// promotes immediately (the platform's own `VERSION_NOT_READY` conflict is
/// then just an ordinary error). On timeout: each host's `{hostId, state,
/// error}` for that version, and exit 1 — promote is never called. The
/// actual work is [Promoter#promote] — shared with `fn deploy`.
@Command(name = "promote", description = "Promote a version to live, waiting for it to become READY", sortOptions = false)
public final class PromoteCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
    String address;

    @Option(names = "--version", required = true, paramLabel = "<n>", description = "the version to promote")
    int version;

    @Option(names = "--wait", paramLabel = "<duration>", defaultValue = "60s", converter = DurationConverter.class,
            description = "how long to wait for READY before giving up (0 = promote immediately; default: ${DEFAULT-VALUE})")
    Duration wait;

    @Mixin
    AddressOptions addressOpts;

    @Spec
    CommandSpec spec;

    /// Test seams — `docs/spec/function-developer-surface.md`'s "1 s poll"
    /// must be provable without a real 60-second test.
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
            FnClient platform = root.client();
            FunctionApi.PromoteResponse promoted =
                    Promoter.promote(spec, root, platform, addr, version, wait, clockMillis, sleepMillis);
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
            case TEXT -> out.printf("%s: version %d is now live%n", address, promoted.version());
            case JSON -> out.println(Json.write(promoted));
        }
    }
}
