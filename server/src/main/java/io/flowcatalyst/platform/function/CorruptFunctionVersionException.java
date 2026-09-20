package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.CorruptRowException;

/// A `fn_versions` row whose `manifest` [Manifest#readStored] refuses, or whose
/// `manifest` column is not valid JSON at all (X-06). Thrown by the SINGLE-row readers
/// ([FunctionVersionRepository#findById], [FunctionVersionRepository#findByFunctionAndVersion],
/// [FunctionVersionRepository#findByFunctionAndDigest], [FunctionVersionRepository#lockById]) —
/// mirrors `CorruptSubscriptionException`/`CorruptDispatchJobException`.
///
/// The BATCH readers ([FunctionVersionRepository#findByIds],
/// [FunctionVersionRepository#newestPublishedByFunctions]) deliberately do NOT throw this: the
/// general `CorruptRowException` policy ("a list read that hits one corrupt row fails the whole
/// list") would let one corrupt version take down `DesiredState`'s document for every pool and
/// every OTHER function, and — worse — a live function silently dropped from that document is
/// unloaded by every host that has it loaded (`function-host-reconciler.md` §1.2 step 4). Those
/// two readers instead report each corrupt row via `FunctionVersionRepository.VersionBatch#corrupt`
/// and let the caller decide (see `DesiredState#build`).
public final class CorruptFunctionVersionException extends CorruptRowException {

    public CorruptFunctionVersionException(String versionId, Throwable cause) {
        super("function version", versionId, cause);
    }
}
