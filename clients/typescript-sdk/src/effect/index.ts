/**
 * Effect-flavored entry point for `@flowcatalyst/sdk`.
 *
 * This subpath gives the write-path (events, dispatch jobs, audit logs) the
 * strongest invariant guarantees: tagged errors, layered services, and a
 * compile-time `UnitOfWork` seal. See `docs/effect-usage.md` for the full
 * worked example.
 *
 * Requires `effect` (^4) as a peer dependency. Importing this module from a
 * project that hasn't installed `effect` will fail at module resolution
 * time — the rest of the SDK (`@flowcatalyst/sdk`) does not require it.
 */

export * as usecase from "./usecase/index.js";
