package io.flowcatalyst.http;

/// A framework-neutral cookie (`docs/spec/http-seam.md` §1), written and
/// read through [Exchange] alone. `maxAge` of `-1` is a session cookie, `0`
/// deletes it.
public record HttpCookie(
        String name,
        String value,
        String path,
        int maxAge,
        boolean httpOnly,
        boolean secure,
        SameSite sameSite) {

    public enum SameSite {
        STRICT, LAX, NONE
    }
}
