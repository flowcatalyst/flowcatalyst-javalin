package io.flowcatalyst.platform.serviceaccount;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A row read from `iam_service_accounts` whose `wh_auth_type` column holds a
/// value [WebhookAuthType#parse] does not recognise (spec §11, drift
/// `6cbe708`/X-06: never a silent default — a corrupted auth type silently
/// reappearing as `NONE` would ship an unauthenticated webhook with nothing
/// in the logs to say why). Carries the offending row's id so an operator can
/// find it. A list read that hits one corrupt row fails the whole list, not
/// just that row — mirrors `platform.dispatchjob.CorruptDispatchJobException`
/// exactly, per the task's instruction to copy that pattern.
public final class CorruptServiceAccountException extends CorruptRowException {

    private final String serviceAccountId;

    public CorruptServiceAccountException(String serviceAccountId, Throwable cause) {
        super("service account " + serviceAccountId + " has a corrupt webhook auth type: " + cause.getMessage(),
                "ServiceAccount", serviceAccountId, cause);
        this.serviceAccountId = serviceAccountId;
    }

    public String serviceAccountId() {
        return serviceAccountId;
    }
}
