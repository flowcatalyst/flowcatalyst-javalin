package io.flowcatalyst.platform.dispatchjob.processing;

import io.flowcatalyst.platform.dispatchjob.DispatchJob;

/// The webhook credentials [SubscriberDelivery] stamps on a delivery
/// (dispatch-seam spec §5 "Delivery request construction"): a bearer token
/// (a static, convenience credential) and/or an HMAC signing secret (the
/// real security boundary). Go's equivalent (`DeliveryCredsResolver`)
/// resolves job → subscription → application → service-account webhook
/// credentials; the Java platform has no `serviceaccount` aggregate yet
/// (`docs/spec/dispatch-seam.md` §15 lists it as unbuilt), so [#none] is the
/// only implementation today — every delivery goes out bare until that
/// aggregate lands and a repository-backed resolver can replace it here.
///
/// Resolution failure degrades to bare delivery with a warning, never a hard
/// failure (spec §5) — [ProcessingApi] enforces that at the call site so a
/// throwing implementation cannot abort a delivery.
public interface DeliveryCredentials {

    /// The credentials to stamp on `job`'s delivery, or [Resolved#NONE] when
    /// the job's subscriber has none configured.
    Resolved resolve(DispatchJob job);

    /// A bearer token and/or signing secret, either or both `null` when not
    /// configured.
    record Resolved(String bearerToken, String signingSecret) {
        public static final Resolved NONE = new Resolved(null, null);

        /// Masked: these are secrets, not diagnostics (CONVENTIONS.md §8).
        @Override
        public String toString() {
            return "Resolved[bearerToken=%s, signingSecret=%s]"
                    .formatted(mask(bearerToken), mask(signingSecret));
        }

        private static String mask(String secret) {
            return secret == null ? "null" : secret.isEmpty() ? "<empty>" : "<redacted>";
        }
    }

    /// The only implementation today (see class doc): every job delivers
    /// bare.
    static DeliveryCredentials none() {
        return job -> Resolved.NONE;
    }
}
