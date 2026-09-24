package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.result.Result;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// An HTTP route path pattern (spec `function-registry.md` §5.2-5.3):
/// literal segments, `{param}` segments and a trailing `*` (rest) segment.
///
/// @param value    the pattern exactly as parsed
/// @param segments the parsed segments, left to right
public record RoutePattern(String value, List<Segment> segments) implements Comparable<RoutePattern> {

    private static final int MAX_LENGTH = 1024;
    private static final Pattern LITERAL = Pattern.compile("^[A-Za-z0-9._~-]+$");
    private static final Pattern PARAM_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    public RoutePattern {
        Objects.requireNonNull(value, "value");
        segments = List.copyOf(segments);
    }

    /// A path pattern segment: a literal (`invoices`), a named parameter
    /// (`{id}`) or the trailing rest wildcard (`*`).
    public sealed interface Segment permits Literal, Param, Rest {
    }

    /// A byte-for-byte literal segment, compared case-sensitively against
    /// the raw (undecoded) request segment.
    public record Literal(String value) implements Segment {
        public Literal {
            Objects.requireNonNull(value, "value");
        }
    }

    /// A named parameter segment; its captured value is percent-decoded.
    public record Param(String name) implements Segment {
        public Param {
            Objects.requireNonNull(name, "name");
        }
    }

    /// The trailing `*` — matches zero or more remaining path segments.
    public record Rest() implements Segment {
    }

    /// One match: the pattern that matched and the params it captured, in
    /// declaration order.
    public record Match(RoutePattern pattern, Map<String, String> params) {
        public Match {
            Objects.requireNonNull(pattern, "pattern");
            params = Map.copyOf(params);
        }
    }

    /// The message [#parse] throws on any malformed input, exposed so
    /// [Manifest]'s collecting parser can reuse it via [#tryParse] without
    /// catching this class's exception (`CONVENTIONS.md` §8).
    public static final String INVALID_MESSAGE =
            "route path must start with '/' and contain only literal, {param} or trailing '*' segments";

    /// Malformed input: the code and message [#parse] raises as a
    /// `UseCaseException` (`CONVENTIONS.md` §8) — carried here so a caller
    /// outside an operation's validate/authorize phases can switch on
    /// [#check] instead of catching that exception for control flow.
    public record Invalid(String code, String message) {
    }

    /// Validates `raw` without throwing. The `Err` case carries exactly the
    /// code and message [#parse] raises as a `UseCaseException`.
    public static Result<RoutePattern, Invalid> check(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) != '/') return invalid();
        if (raw.length() > MAX_LENGTH) return invalid();
        if (raw.equals("/")) {
            return Result.ok(new RoutePattern(raw, List.of()));
        }
        String[] parts = raw.substring(1).split("/", -1);
        List<Segment> parsed = new ArrayList<>(parts.length);
        Set<String> paramNames = new HashSet<>();
        for (int i = 0; i < parts.length; i++) {
            Segment s;
            switch (parseSegment(parts[i], i == parts.length - 1)) {
                case Result.Ok<Segment, Invalid>(Segment ok) -> s = ok;
                case Result.Err<Segment, Invalid>(Invalid err) -> {
                    return Result.err(err);
                }
            }
            if (s instanceof Param(String name) && !paramNames.add(name)) return invalid();
            parsed.add(s);
        }
        return Result.ok(new RoutePattern(raw, parsed));
    }

    /// Non-throwing companion of [#parse]: empty on any malformed input,
    /// never throws.
    public static Optional<RoutePattern> tryParse(String raw) {
        return switch (check(raw)) {
            case Result.Ok<RoutePattern, Invalid> ok -> Optional.of(ok.value());
            case Result.Err<RoutePattern, Invalid> ignored -> Optional.empty();
        };
    }

    /// @throws UseCaseException validation `ROUTE_PATTERN_INVALID`
    public static RoutePattern parse(String raw) {
        return check(raw).orElseThrow(e -> UseCaseException.validation(e.code(), e.message()));
    }

    private static Result<Segment, Invalid> parseSegment(String part, boolean isLast) {
        if (part.equals("*")) {
            if (!isLast) return invalid();
            return Result.ok(new Rest());
        }
        if (part.length() >= 2 && part.startsWith("{") && part.endsWith("}")) {
            String name = part.substring(1, part.length() - 1);
            if (!PARAM_NAME.matcher(name).matches()) return invalid();
            return Result.ok(new Param(name));
        }
        if (!LITERAL.matcher(part).matches()) return invalid();
        return Result.ok(new Literal(part));
    }

    /// The params on a match, in declaration order; empty `Optional`
    /// otherwise. `path` is the request's raw path, no query string.
    ///
    /// - literals compare byte-for-byte against the **raw** segment (no
    ///   decoding, case-sensitive); a param matches exactly one non-empty
    ///   segment and its value is percent-decoded (UTF-8, `+` is **not** a
    ///   space — this is a path, not a form; a malformed escape or invalid
    ///   UTF-8 sequence means no match);
    /// - `Rest` matches zero or more remaining segments;
    /// - without `Rest`, the segment counts must be equal — a trailing
    ///   slash is an empty last segment and matches nothing but `Rest`;
    /// - `match(null)` is empty.
    public Optional<Map<String, String>> match(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') return Optional.empty();
        String[] parts = path.equals("/") ? new String[0] : path.substring(1).split("/", -1);
        Map<String, String> params = new LinkedHashMap<>();
        int i = 0;
        for (; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment instanceof Rest) {
                return Optional.of(Collections.unmodifiableMap(params));
            }
            if (i >= parts.length) return Optional.empty();
            String raw = parts[i];
            if (raw.isEmpty()) return Optional.empty();
            switch (segment) {
                case Literal(String literal) -> {
                    if (!literal.equals(raw)) return Optional.empty();
                }
                case Param(String name) -> {
                    String decoded = percentDecode(raw);
                    if (decoded == null) return Optional.empty();
                    params.put(name, decoded);
                }
                case Rest ignored -> throw new AssertionError("Rest handled above");
            }
        }
        if (i < parts.length) return Optional.empty();
        return Optional.of(Collections.unmodifiableMap(params));
    }

    /// *More specific first* (spec §5.3): compare segment by segment from
    /// the left, `Literal` before `Param` before `Rest`; if one pattern runs
    /// out first, the shorter (without the trailing segment the other still
    /// has) sorts first. Two patterns that reach the end tied
    /// ([#ambiguousWith] each other, or merely share a segment-kind shape)
    /// break the tie by [#value] — identical strings compare `0`, anything
    /// else compares by string order, so `compareTo` never contradicts
    /// `equals`.
    @Override
    public int compareTo(RoutePattern other) {
        int n = Math.min(segments.size(), other.segments.size());
        for (int i = 0; i < n; i++) {
            int cmp = Integer.compare(rank(segments.get(i)), rank(other.segments.get(i)));
            if (cmp != 0) return cmp;
        }
        if (segments.size() != other.segments.size()) {
            return Integer.compare(segments.size(), other.segments.size());
        }
        return value.equals(other.value) ? 0 : value.compareTo(other.value);
    }

    private static int rank(Segment segment) {
        return switch (segment) {
            case Literal ignored -> 0;
            case Param ignored -> 1;
            case Rest ignored -> 2;
        };
    }

    /// True when the two patterns have the same number of segments and, at
    /// every position, are both the same literal, both params (names
    /// irrelevant) or both `Rest` — exactly the pairs [#compareTo]'s order
    /// cannot separate (spec §5.3).
    public boolean ambiguousWith(RoutePattern other) {
        if (segments.size() != other.segments.size()) return false;
        for (int i = 0; i < segments.size(); i++) {
            Segment a = segments.get(i);
            Segment b = other.segments.get(i);
            boolean same = switch (a) {
                case Literal(String lit) -> b instanceof Literal(String otherLit) && lit.equals(otherLit);
                case Param ignored -> b instanceof Param;
                case Rest ignored -> b instanceof Rest;
            };
            if (!same) return false;
        }
        return true;
    }

    /// Sorts `patterns` by specificity ([#compareTo]) and returns the first
    /// whose [#match] succeeds against `path` — the route the request
    /// resolves to (spec §5.3).
    public static Optional<Match> firstMatch(List<RoutePattern> patterns, String path) {
        return patterns.stream()
                .sorted()
                .flatMap(pattern -> pattern.match(path).map(params -> new Match(pattern, params)).stream())
                .findFirst();
    }

    /// Percent-decodes a single path segment: UTF-8, `+` is left literal (a
    /// path segment, not a form field). Returns `null` on a malformed escape
    /// or an invalid UTF-8 byte sequence — the caller treats that as "no
    /// match", never an exception.
    private static String percentDecode(String raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) return null;
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return null;
                bytes.write((hi << 4) | lo);
                i += 3;
            } else if (c <= 0x7F) {
                bytes.write(c);
                i++;
            } else {
                return null;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static <T> Result<T, Invalid> invalid() {
        return Result.err(new Invalid("ROUTE_PATTERN_INVALID", INVALID_MESSAGE));
    }
}
