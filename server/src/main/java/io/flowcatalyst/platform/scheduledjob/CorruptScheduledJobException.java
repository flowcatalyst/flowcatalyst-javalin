package io.flowcatalyst.platform.scheduledjob;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `msg_scheduled_jobs` or `msg_scheduled_job_instances`
/// whose `status`/`trigger_kind` column holds a value the relevant enum's
/// `parse` does not recognise (X-06: never a silent default). Carries the
/// offending row's id — `entity()` says which table (`"scheduled job"` or
/// `"scheduled job instance"`).
public final class CorruptScheduledJobException extends CorruptRowException {

    public CorruptScheduledJobException(String entity, String rowId, Throwable cause) {
        super(entity, rowId, cause);
    }
}
