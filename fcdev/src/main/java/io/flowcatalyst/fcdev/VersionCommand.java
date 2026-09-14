package io.flowcatalyst.fcdev;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/// `fcdev version`: `fcdev <version>[ (<rev>)]`. Kept alongside `--version`
/// for parity with earlier fcdev releases. No version flag of its own — Go's
/// `newVersionCmd` gets no `Version` field, so cobra adds no `--version` to
/// it either (only `-h/--help`, same as every other subcommand).
@Command(name = "version", description = "Print the fcdev version")
public final class VersionCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getOut().println(Version.line());
        return 0;
    }
}
