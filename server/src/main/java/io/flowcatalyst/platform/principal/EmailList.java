package io.flowcatalyst.platform.principal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A pasted list of email addresses, cleaned (`docs/spec/principal.md` §3,
/// match-emails). The one parser for "addresses a person copied from a mail
/// client or a spreadsheet": commas, semicolons and any whitespace separate
/// entries, and the `Ann Smith <ann@x.com>` form contributes only the address.
///
/// The rules, in the order they apply:
///
/// | Input | Becomes |
/// |---|---|
/// | `Name <addr>` | `addr`; the display name is dropped |
/// | `"Name, Jr" <addr>` | `addr`; a quoted name may contain commas |
/// | `Smith, Ann <ann@x.com>; Jones, Bob <bob@x.com>` | both addresses — commas do **not** end a display name; only `;` or a new line does (Outlook writes last-first names this way) |
/// | a word before `<addr>`, since the last `;`/new line, without `@` | dropped as part of the name, not reported |
/// | an entry wrapped in `"` or `'` | unwrapped |
/// | exactly one `@`, not first or last | an address: lower-cased, de-duplicated in first-seen order |
/// | anything else non-blank | `invalid`: trimmed, original case, de-duplicated (case-sensitively) |
///
/// @param emails  the addresses, lower-cased, in first-seen order, no duplicates
/// @param invalid the entries that are not a single `local@domain`, in first-seen order, no duplicates
public record EmailList(List<String> emails, List<String> invalid) {

    /// The address part of a `Name <addr>` entry.
    private static final Pattern ANGLE_ENTRY = Pattern.compile("<([^<>]*)>");

    public EmailList {
        emails = List.copyOf(Objects.requireNonNull(emails, "emails"));
        invalid = List.copyOf(Objects.requireNonNull(invalid, "invalid"));
    }

    /// Parses pasted text; `null` reads as empty. Never throws.
    public static EmailList parse(String text) {
        var acc = new Accumulator();
        if (text != null) {
            int pos = 0;
            Matcher m = ANGLE_ENTRY.matcher(text);
            while (m.find()) {
                acc.addPlain(stripDisplayName(text.substring(pos, m.start())));
                acc.add(m.group(1));
                pos = m.end();
            }
            acc.addPlain(text.substring(pos));
        }
        return new EmailList(List.copyOf(acc.emails), List.copyOf(acc.invalid));
    }

    /// Collects entries with first-seen de-duplication on both lists.
    private static final class Accumulator {
        private final LinkedHashSet<String> emails = new LinkedHashSet<>();
        private final LinkedHashSet<String> invalid = new LinkedHashSet<>();

        void addPlain(String s) {
            for (String field : fields(s)) {
                add(field);
            }
        }

        void add(String raw) {
            String entry = trimSpace(trimQuotes(trimSpace(raw)));
            if (entry.isEmpty()) {
                return;
            }
            int at = entry.indexOf('@');
            boolean single = at >= 0 && entry.indexOf('@', at + 1) < 0;
            if (!single || entry.startsWith("@") || entry.endsWith("@")) {
                invalid.add(entry);
                return;
            }
            emails.add(entry.toLowerCase(Locale.ROOT));
        }
    }

    /// Removes the display name that sits directly before an `<addr>`: a
    /// quoted string (which may contain commas), else every word since the
    /// last `;`/new line that is not itself an address. Commas do not end the
    /// name: Outlook writes `Smith, Ann <ann@x.com>; ...`, so `Smith` is part
    /// of it. A stray non-address word there is dropped rather than reported.
    private static String stripDisplayName(String pre) {
        String trimmed = trimRight(pre);
        if (trimmed.endsWith("\"")) {
            int k = trimmed.substring(0, trimmed.length() - 1).lastIndexOf('"');
            if (k >= 0) {
                return trimmed.substring(0, k);
            }
        }
        int cut = Math.max(trimmed.lastIndexOf(';'), trimmed.lastIndexOf('\n'));
        String head = trimmed.substring(0, cut + 1);
        List<String> addrs = new ArrayList<>();
        for (String w : fields(trimmed.substring(cut + 1))) {
            if (w.contains("@")) {
                addrs.add(w);
            }
        }
        return head + " " + String.join(" ", addrs);
    }

    /// The non-empty runs between separators.
    private static List<String> fields(String s) {
        List<String> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            if (isSeparator(s.charAt(i))) {
                if (start >= 0) {
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            out.add(s.substring(start));
        }
        return out;
    }

    /// `,` `;` and the whitespace a paste can carry, including the
    /// non-breaking space and the Unicode line/paragraph separators.
    private static boolean isSeparator(char c) {
        return switch (c) {
            case ',', ';', ' ', '\t', '\n', '\r', '\u000B', '\f', '\u0085', ' ', ' ', ' ' -> true;
            default -> false;
        };
    }

    /// Unicode `White_Space` — wider than [Character#isWhitespace], which
    /// excludes the non-breaking spaces a paste from a web page often carries.
    private static boolean isSpace(char c) {
        return switch (c) {
            case '\t', '\n', '\u000B', '\f', '\r', ' ', '\u0085', ' ', ' ', ' ' -> true;
            default -> Character.getType(c) == Character.SPACE_SEPARATOR;
        };
    }

    private static String trimSpace(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && isSpace(s.charAt(b))) b++;
        while (e > b && isSpace(s.charAt(e - 1))) e--;
        return s.substring(b, e);
    }

    private static String trimQuotes(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && isQuote(s.charAt(b))) b++;
        while (e > b && isQuote(s.charAt(e - 1))) e--;
        return s.substring(b, e);
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'';
    }

    /// Trailing ` `, tab, CR and LF only (the separator before an `<addr>`).
    private static String trimRight(String s) {
        int e = s.length();
        while (e > 0 && " \t\r\n".indexOf(s.charAt(e - 1)) >= 0) e--;
        return s.substring(0, e);
    }
}
