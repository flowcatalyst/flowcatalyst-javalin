package io.flowcatalyst.http;

/// A framework-neutral exception carrying its own HTTP status. The adapter
/// maps it to the `HttpError` envelope named by the status — `BAD_REQUEST`,
/// `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, else `INTERNAL`
/// (`docs/spec/http-seam.md` §4 row 4).
public final class HttpException extends RuntimeException {

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
