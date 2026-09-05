package io.flowcatalyst.platform.process;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `msg_processes` whose `status` or `source` column holds a
/// value [ProcessStatus#parse] / [ProcessSource#parse] does not recognise
/// (X-06: never a silent default). Carries the offending row's id.
public final class CorruptProcessException extends CorruptRowException {

    public CorruptProcessException(String processId, Throwable cause) {
        super("process", processId, cause);
    }
}
