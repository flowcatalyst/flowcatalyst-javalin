package io.flowcatalyst.fcdev;

import picocli.AutoComplete;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.List;
import java.util.concurrent.Callable;

/// `fcdev completion <shell>` (Go `cmd/fcdev`: cobra's built-in `completion`
/// command, auto-registered because `main.go` never sets
/// `CompletionOptions.DisableDefaultCmd`). Go's cobra offers four shells as
/// four sub-subcommands (`completion bash`, `completion zsh`, `completion
/// fish`, `completion powershell`) and, since that parent command carries no
/// `RunE`, both a bare `fcdev completion` and an unrecognised shell name just
/// print the parent's help and exit 0 (verified against Go directly — cobra
/// treats a non-runnable command with leftover args as a help request, not a
/// usage error).
///
/// picocli's `AutoComplete.bash(...)` (the only script generator picocli
/// ships) produces one script that both bash and zsh can load — zsh loads it
/// via `autoload -U bashcompinit && bashcompinit`, the same trick Go's own
/// zsh completion help text recommends as a fallback. There is no picocli
/// equivalent for fish or powershell, so rather than faking one, an
/// unsupported shell is a clear, distinct error (exit 2, fcdev's usage-error
/// code) instead of Go's silent help dump — `docs/spec/fcdev.md` §7a.
@Command(name = "completion", description = "Generate a shell completion script (bash, zsh)")
public final class CompletionCommand implements Callable<Integer> {

    static final List<String> SUPPORTED_SHELLS = List.of("bash", "zsh");

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<shell>", description = "bash or zsh")
    String shell;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        if (shell == null) {
            // Mirrors Go: a bare `fcdev completion` prints this command's own help, exit 0.
            spec.commandLine().usage(out);
            return 0;
        }
        if (!SUPPORTED_SHELLS.contains(shell)) {
            var err = spec.commandLine().getErr();
            err.println("fcdev completion: unsupported shell \"" + shell + "\"");
            err.println("want one of: bash, zsh (fish and powershell are not supported by the Java fcdev)");
            err.flush();
            return 2;
        }
        var root = spec.commandLine().getParent();
        out.print(AutoComplete.bash(root.getCommandName(), root));
        out.flush();
        return 0;
    }
}
