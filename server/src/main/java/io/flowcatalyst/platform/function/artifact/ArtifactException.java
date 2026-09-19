package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;

import java.io.Serial;
import java.util.Objects;

/// Infrastructure outcomes from fetching a function artifact (spec
/// `function-artifacts.md` §2): one checked exception carrying a sealed
/// [Reason], not a family of exception types — every caller maps the same
/// handful of outcomes to its own shape (publish turns one into a
/// validation or 502-class error; the host turns one into a load error in
/// the heartbeat).
public final class ArtifactException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public sealed interface Reason
            permits NotFound, DigestMismatch, TooLarge, UnsupportedScheme, Unauthorized, Transport, BadRef {
    }

    /// The artifact reference resolved to nothing — a registry `404`, or a
    /// `file://` path that is not a regular file.
    public record NotFound() implements Reason {
    }

    /// The bytes received hash to something other than `expected` (spec §2:
    /// the digest is always recomputed from the bytes received, never
    /// trusted from the source).
    public record DigestMismatch(Digest expected, Digest actual) implements Reason {
        public DigestMismatch {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
        }
    }

    /// The transfer was abandoned because it passed `limit` bytes, or a
    /// declared `Content-Length` already exceeded it before a byte was read.
    public record TooLarge(long limit) implements Reason {
    }

    /// [ArtifactStores#forRef] saw a scheme with no store behind it —
    /// `s3://` until package D, or anything unrecognised.
    public record UnsupportedScheme(String scheme) implements Reason {
        public UnsupportedScheme {
            Objects.requireNonNull(scheme, "scheme");
        }
    }

    /// A registry rejected every credential this store had for it (or had none).
    public record Unauthorized() implements Reason {
    }

    /// An I/O error, a timeout, or a non-2xx response none of the other
    /// reasons name.
    public record Transport(Throwable cause) implements Reason {
        public Transport {
            Objects.requireNonNull(cause, "cause");
        }
    }

    /// The artifact reference itself is malformed — a relative `file://`
    /// path, a host component on one, an `oci://` reference carrying a tag
    /// or `@digest`, or no scheme at all.
    public record BadRef(String why) implements Reason {
        public BadRef {
            Objects.requireNonNull(why, "why");
        }
    }

    private final Reason reason;

    public ArtifactException(Reason reason) {
        super(message(reason));
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ArtifactException(Reason reason, Throwable cause) {
        super(message(reason), cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return switch (reason) {
            case NotFound _ -> "artifact not found";
            case DigestMismatch(var expected, var actual) ->
                    "digest mismatch: expected " + expected.value() + " but got " + actual.value();
            case TooLarge(var limit) -> "artifact exceeds the " + limit + "-byte limit";
            case UnsupportedScheme(var scheme) -> "unsupported artifact reference scheme: " + scheme;
            case Unauthorized _ -> "registry rejected every credential";
            case Transport(var cause) -> "transport failure: " + cause.getMessage();
            case BadRef(var why) -> "malformed artifact reference: " + why;
        };
    }
}
