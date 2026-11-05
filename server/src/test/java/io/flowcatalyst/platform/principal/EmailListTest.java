package io.flowcatalyst.platform.principal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// [EmailList#parse] as a pinned table (`docs/spec/principal.md` §3,
/// match-emails): every rule of the class doc has a named row, so a change to
/// the parser must edit one.
class EmailListTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // ── separators ──
                arguments("empty", "  \n\t ", List.of(), List.of()),
                arguments("commas", "a@x.com,b@x.com, c@x.com", List.of("a@x.com", "b@x.com", "c@x.com"), List.of()),
                arguments("semicolons", "a@x.com;b@x.com; c@x.com", List.of("a@x.com", "b@x.com", "c@x.com"), List.of()),
                arguments("newlines tabs spaces", "a@x.com\nb@x.com\r\nc@x.com\td@x.com e@x.com",
                        List.of("a@x.com", "b@x.com", "c@x.com", "d@x.com", "e@x.com"), List.of()),
                arguments("mixed separators and empties", ",;a@x.com,,;\n\nb@x.com;", List.of("a@x.com", "b@x.com"), List.of()),
                // ── normalisation ──
                arguments("lowercases", "Ann@X.COM", List.of("ann@x.com"), List.of()),
                arguments("dedupes keeping first order", "b@x.com a@x.com B@x.com a@x.com", List.of("b@x.com", "a@x.com"), List.of()),
                // ── display names ──
                arguments("name in angle brackets", "Ann Smith <Ann@x.com>", List.of("ann@x.com"), List.of()),
                arguments("quoted name with comma", "\"Name, Jr\" <a@b.com>", List.of("a@b.com"), List.of()),
                arguments("list of angle entries", "Ann Smith <a@x.com>, \"Bob, Jr\" <b@x.com>; Cy <c@x.com>",
                        List.of("a@x.com", "b@x.com", "c@x.com"), List.of()),
                arguments("outlook last-first names", "Smith, Ann <ann@x.com>; Jones, Bob <bob@x.com>",
                        List.of("ann@x.com", "bob@x.com"), List.of()),
                arguments("plain address before a named one", "a@x.com, Smith, Ann <ann@x.com>", List.of("a@x.com", "ann@x.com"), List.of()),
                arguments("plain then named", "a@x.com b@x.com Cy Dee <c@x.com>", List.of("a@x.com", "b@x.com", "c@x.com"), List.of()),
                arguments("quoted entries", "\"a@x.com\", 'b@x.com'", List.of("a@x.com", "b@x.com"), List.of()),
                arguments("angle without name", "<a@x.com>", List.of("a@x.com"), List.of()),
                // ── invalid ──
                arguments("invalid no at", "a@x.com, nope, B@@x.com", List.of("a@x.com"), List.of("nope", "B@@x.com")),
                arguments("invalid deduped keeps case", "Foo foo Foo", List.of(), List.of("Foo", "foo")),
                arguments("invalid bare at", "@ a@ @b", List.of(), List.of("@", "a@", "@b")),
                arguments("invalid angle entry", "Ann <not-an-email>", List.of(), List.of("not-an-email")),
                arguments("order across valid and invalid", "z@x.com junk a@x.com", List.of("z@x.com", "a@x.com"), List.of("junk")),
                // ── Java-only rows: the non-ASCII separators and null ──
                arguments("non-breaking space and line separator", "a@x.com b@x.com c@x.com", List.of("a@x.com", "b@x.com", "c@x.com"), List.of()),
                arguments("null reads as empty", null, List.of(), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void parses(String rule, String in, List<String> wantEmails, List<String> wantInvalid) {
        EmailList got = EmailList.parse(in);
        assertThat(got.emails()).as(rule + ": emails").containsExactlyElementsOf(wantEmails);
        assertThat(got.invalid()).as(rule + ": invalid").containsExactlyElementsOf(wantInvalid);
    }
}
