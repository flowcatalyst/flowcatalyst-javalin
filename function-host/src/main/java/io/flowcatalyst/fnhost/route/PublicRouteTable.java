package io.flowcatalyst.fnhost.route;

import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// The public listener's route table (spec `function-public-routes.md` §3):
/// an IMMUTABLE snapshot built once per reconcile from the current
/// [DesiredDocument]'s `publicRoutes`, never recomputed per request — a
/// caller ([io.flowcatalyst.fnhost.reconcile.Reconciler]) swaps its held
/// instance atomically each time the document changes.
///
/// Matching (spec §3 steps 1-3): `hostname` is looked up exactly (already
/// lower-cased, no port — the caller's job, spec: "`Host` header... lower-
/// cased, port stripped"); among that hostname's routes, the LONGEST
/// WHOLE-SEGMENT prefix of the request path wins (`/billing` matches
/// `/billing` and `/billing/x`, never `/billingx` — a segment-by-segment
/// comparison, never [String#startsWith]); the function-path is the request
/// path with the matched prefix's segments removed, `/` for an exact match.
public final class PublicRouteTable {

    public static final PublicRouteTable EMPTY = new PublicRouteTable(Map.of());

    /// hostname → its routes, LONGEST prefix (most segments) first — so
    /// [#match] can simply take the first whose segments prefix the request.
    private final Map<String, List<Entry>> byHost;

    private record Entry(String[] segments, FunctionAddress address) {
    }

    /// One resolved match: the address to invoke and the function-path it
    /// should see (spec §3 step 3).
    public record Match(FunctionAddress address, String functionPath) {
    }

    private PublicRouteTable(Map<String, List<Entry>> byHost) {
        this.byHost = byHost;
    }

    /// Builds a fresh, immutable snapshot from `refs` — the current
    /// document's `publicRoutes` (already validated/deduplicated by the
    /// platform at publish/promote time; this class trusts them as given).
    public static PublicRouteTable of(List<DesiredDocument.PublicRouteRef> refs) {
        if (refs.isEmpty()) {
            return EMPTY;
        }
        Map<String, List<Entry>> byHost = new LinkedHashMap<>();
        for (DesiredDocument.PublicRouteRef ref : refs) {
            String hostname = ref.hostname().toLowerCase(Locale.ROOT);
            String[] segments = splitSegments(ref.pathPrefix());
            byHost.computeIfAbsent(hostname, h -> new ArrayList<>()).add(new Entry(segments, ref.address()));
        }
        Map<String, List<Entry>> sorted = new LinkedHashMap<>();
        for (var e : byHost.entrySet()) {
            List<Entry> entries = new ArrayList<>(e.getValue());
            // Longest (most segments) first — spec §3: "the longest matching prefix wins".
            entries.sort(Comparator.comparingInt((Entry en) -> en.segments().length).reversed());
            sorted.put(e.getKey(), List.copyOf(entries));
        }
        return new PublicRouteTable(Map.copyOf(sorted));
    }

    /// @param hostname    already lower-cased, port stripped (the caller's job)
    /// @param requestPath the raw (undecoded) request path, starting with `/`
    public Optional<Match> match(String hostname, String requestPath) {
        List<Entry> entries = byHost.get(hostname);
        if (entries == null) {
            return Optional.empty();
        }
        String[] pathSegments = splitSegments(requestPath);
        for (Entry entry : entries) {
            if (isWholeSegmentPrefix(entry.segments(), pathSegments)) {
                return Optional.of(new Match(entry.address(), functionPath(entry.segments(), pathSegments)));
            }
        }
        return Optional.empty();
    }

    /// True when every segment of `prefix()` equals the raw (undecoded, byte-
    /// for-byte) segment of `path` at the same position — `/billing` (one
    /// segment `billing`) does NOT prefix `/billingx` (one segment
    /// `billingx`): the comparison is whole-segment, never a string
    /// `startsWith` over the raw path.
    private static boolean isWholeSegmentPrefix(String[] prefix, String[] path) {
        if (prefix.length > path.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (!prefix[i].equals(path[i])) {
                return false;
            }
        }
        return true;
    }

    /// The request path with the matched prefix's segments removed — `/`
    /// when nothing remains (spec §3 step 3: an exact match).
    private static String functionPath(String[] prefix, String[] path) {
        if (prefix.length == path.length) {
            return "/";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = prefix.length; i < path.length; i++) {
            sb.append('/').append(path[i]);
        }
        return sb.toString();
    }

    /// `/` ⇒ zero segments (matches everything, matches nothing left over);
    /// otherwise the `/`-separated segments after the leading slash, raw
    /// (undecoded) — same convention as [io.flowcatalyst.platform.function.RoutePattern#match].
    private static String[] splitSegments(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return new String[0];
        }
        String withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        return withoutLeadingSlash.split("/", -1);
    }
}
