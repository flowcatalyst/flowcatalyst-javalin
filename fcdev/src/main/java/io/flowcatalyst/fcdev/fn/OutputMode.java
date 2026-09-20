package io.flowcatalyst.fcdev.fn;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.util.Locale;

/// `--output text|json` (`docs/spec/function-developer-surface.md` §2):
/// `TEXT` is the human-friendly form; `JSON` is one machine-readable object
/// per command on stdout and nothing else on stdout — diagnostics (progress,
/// warnings) go to stderr in either mode.
public enum OutputMode {
    TEXT, JSON;

    public static final class Converter implements ITypeConverter<OutputMode> {
        @Override
        public OutputMode convert(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "text" -> TEXT;
                case "json" -> JSON;
                default -> throw new TypeConversionException("must be 'text' or 'json' (got \"" + value + "\")");
            };
        }
    }
}
