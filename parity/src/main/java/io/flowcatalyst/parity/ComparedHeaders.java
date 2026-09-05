package io.flowcatalyst.parity;

import java.util.List;

/// The headers compared, by name, and nothing else (parity-harness spec §4).
/// `Date`, `Server`, `Content-Length`, `Transfer-Encoding` and anything else
/// are never captured into a [StepRecord] at all.
public final class ComparedHeaders {

    /// The wire-format oracle: a body diff on the wrong content type is
    /// usually the whole story, so it has to agree.
    public static final String CONTENT_TYPE = "Content-Type";

    /// Redirect targets (OAuth authorize, the 302 a client follows) — compared
    /// after normalisation (base URL, captured values).
    public static final String LOCATION = "Location";

    /// The 401 challenge shape (`Bearer error="invalid_token", ...`).
    public static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    /// Rate-limit backoff: presence only (Normaliser masks the value) — the
    /// exact seconds are a policy detail, not a wire contract.
    public static final String RETRY_AFTER = "Retry-After";

    /// Cache directives on static/semi-static responses (the OpenAPI document, JWKS).
    public static final String CACHE_CONTROL = "Cache-Control";

    /// The session cookie: name + attributes compared, the value masked
    /// (Normaliser rule 5) since it is itself the JWT captured elsewhere.
    public static final String SET_COOKIE = "Set-Cookie";

    /// Every header a [StepRecord] keeps.
    public static final List<String> NAMES = List.of(
            CONTENT_TYPE, LOCATION, WWW_AUTHENTICATE, RETRY_AFTER, CACHE_CONTROL, SET_COOKIE);

    private ComparedHeaders() {
    }
}
