package io.flowcatalyst.http;

/// Replaces `io.javalin.http.Cookie` + `io.javalin.http.SameSite`.
/// `maxAge` of `-1` is a session cookie, `0` deletes it.
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
