package io.flowcatalyst.fcdev;

import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/// picocli factory that hands every command, mixin and subcommand the same
/// [DevEnv] — the one fcdev was started with, or the map a test supplies —
/// by preferring a `(DevEnv)` constructor and falling back to picocli's
/// default (no-arg) factory.
public record EnvFactory(DevEnv env) implements IFactory {

    @Override
    public <K> K create(Class<K> cls) throws Exception {
        try {
            var ctor = cls.getDeclaredConstructor(DevEnv.class);
            return ctor.newInstance(env);
        } catch (NoSuchMethodException _) {
            return CommandLine.defaultFactory().create(cls);
        }
    }
}
