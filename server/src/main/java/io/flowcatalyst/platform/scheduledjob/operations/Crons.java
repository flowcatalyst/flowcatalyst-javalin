package io.flowcatalyst.platform.scheduledjob.operations;

import io.flowcatalyst.platform.scheduledjob.cron.CronExpression;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.List;

/// The cron-list rule shared by create, update and sync (spec §5): at
/// least one expression, each one parsing under the six-field ruling.
final class Crons {

    private Crons() {
    }

    /// Parses every expression of a command's list.
    ///
    /// @throws UseCaseException validation `CRONS_REQUIRED` (null or empty),
    ///                          `INVALID_CRON` | `CRON_INVALID_SHAPE` (see [CronExpression#parse])
    static List<CronExpression> parseAll(List<String> crons) {
        if (crons == null || crons.isEmpty()) {
            throw UseCaseException.validation("CRONS_REQUIRED", "at least one cron expression is required");
        }
        return crons.stream().map(CronExpression::parse).toList();
    }
}
