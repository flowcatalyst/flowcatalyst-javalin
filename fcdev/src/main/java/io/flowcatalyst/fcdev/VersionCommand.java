package io.flowcatalyst.fcdev;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/// `fcdev version`: `fcdev <version>[ (<rev>)]`. Kept alongside `--version`
/// for parity with earlier fcdev releases.
@Command(name = "version", description = "Print the fcdev version", mixinStandardHelpOptions = true)
public final class VersionCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getOut().println(Version.line());
        return 0;
    }
}
